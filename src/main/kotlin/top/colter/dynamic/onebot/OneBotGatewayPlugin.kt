package top.colter.dynamic.onebot

import com.google.gson.JsonArray
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()
private val QQ_PLATFORM_ID = PlatformId.of("qq")

public class OneBotGatewayPlugin : AccountRoutedMessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

    private var pluginId: String = ONEBOT_PLUGIN_ID
    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private lateinit var commandPublisher: CommandPublisher
    private var running: Boolean = false

    override val configId: String
        get() = pluginId
    override val configName: String = "OneBot 网关"
    override val configDescription: String = "OneBot 连接与消息投递配置。"
    override val configClass = OneBotConfig::class
    override val configFormSpec = OneBotConfigForm.spec

    override val transportId: String = "onebot"
    override val transportName: String = "OneBot"
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(QQ_PLATFORM_ID)
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        commandPublisher = context.commandPublisher
        config = context.configService.loadOrCreate(pluginId) { OneBotConfig() }
        OneBotConfigForm.validate(config)
        logger.info { "OneBot 配置已加载：pluginId=$pluginId，mode=${config.mode}" }
    }

    override suspend fun onStart() {
        if (running) return

        OneBotConfigForm.validate(config)
        gateway = OneBotGatewayFactory.create(config)
        runCatching {
            gateway.connect(::onIncomingMessage)
        }.onFailure {
            gateway.close()
            gateway = NoopOneBotGateway()
            logger.error(it) { "OneBot 启动失败：mode=${config.mode} endpoint=${config.endpointLabel()}" }
            throw it
        }
        running = true

        logger.info { "OneBot 已启动：mode=${config.mode} endpoint=${config.endpointLabel()}" }
    }

    override suspend fun onStop() {
        if (!running) return
        running = false
        gateway.close()
        gateway = NoopOneBotGateway()
        logger.info { "OneBot 已停止" }
    }

    override fun currentConfig(): OneBotConfig = config

    override fun applyConfig(next: OneBotConfig): ConfigApplyResult {
        OneBotConfigForm.validate(next)
        val changed = next != config
        config = next
        return ConfigApplyResult(
            changed = changed,
            restartRequired = changed,
            restartTargets = if (changed) listOf("OneBot 插件") else emptyList(),
            message = if (changed) {
                "OneBot 配置已保存；请重启 OneBot 插件以重新连接"
            } else {
                "OneBot 配置未变化"
            },
        )
    }

    override suspend fun listMessageSinkRoutes(target: TargetAddress?): List<MessageSinkRoute> {
        if (target != null && !supportsTarget(target)) return emptyList()
        val state = if (running) MessageSinkRouteState.READY else MessageSinkRouteState.UNAVAILABLE
        return gateway.availableAccounts().map { account ->
            account.toRoute(state)
        }
    }

    override suspend fun sendMessage(
        request: MessageDeliveryRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("OneBot 未运行")
        if (!supportsTarget(request.target)) {
            return MessageSendResult.failed("目标平台不是 QQ 或类型不受支持", retryable = false)
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("OneBot 路线 ID 无效：$routeId", retryable = false)

        val message = request.message
        val payloads = OneBotMessageMapper.toJsonArrayMessages(message)

        return when (val target = OneBotTarget.fromAddress(request.target)) {
            is OneBotTarget.Group -> sendPayloads(routeId, accountId, payloads, "OneBot 消息发送失败") { payload ->
                gateway.sendGroupMessage(accountId, target.groupId, payload)
            }
            is OneBotTarget.User -> sendPayloads(routeId, accountId, payloads, "OneBot 消息发送失败") { payload ->
                gateway.sendPrivateMessage(accountId, target.userId, payload)
            }
            is OneBotTarget.Unsupported -> {
                logger.warn {
                    "跳过 OneBot 目标：messageId=${message.id} targetId=${request.target.externalId} reason=${target.reason}"
                }
                MessageSendResult.failed(target.reason, retryable = false)
            }
        }
    }

    override suspend fun sendCommandResult(
        request: CommandResultSendRequest,
        routeId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("OneBot 未运行")
        if (!supportsTarget(request.target.address)) {
            return MessageSendResult.failed("目标平台不是 QQ 或类型不受支持", retryable = false)
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageSendResult.failed("OneBot 路线 ID 无效：$routeId", retryable = false)

        val payload = OneBotMessageMapper.toJsonArrayMessage(request.chain)
        return runCatching {
            val sinkMessageId = when (request.target.chatType) {
                TargetKind.GROUP -> gateway.sendGroupMessage(accountId, request.target.chatId.toLong(), payload)
                TargetKind.USER -> gateway.sendPrivateMessage(accountId, request.target.chatId.toLong(), payload)
                else -> {
                    logger.warn {
                        "跳过 OneBot 命令结果：traceId=${request.inReplyTo}，不支持目标=${request.target.chatType}:${request.target.chatId}"
                    }
                    return MessageSendResult.failed("OneBot 不支持目标类型：${request.target.chatType}", retryable = false)
                }
            }
            MessageSendResult.sent(sinkMessageId, sinkRouteId = routeId, sinkAccountId = accountId)
        }.getOrElse {
            logger.warn(it) {
                "OneBot 命令结果发送失败：traceId=${request.inReplyTo} target=${request.target.chatType}:${request.target.chatId} routeId=$routeId"
            }
            MessageSendResult.failed(it.message ?: "OneBot 命令结果发送失败")
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest, routeId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("OneBot 未运行")
        if (!supportsTarget(request.target)) {
            return MessageRecallResult.failed("目标平台不是 QQ 或类型不受支持")
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageRecallResult.failed("OneBot 路线 ID 无效：$routeId")
        return runCatching {
            gateway.recallMessage(accountId, request.sinkMessageId)
            MessageRecallResult.recalled()
        }.getOrElse {
            logger.debug(it) {
                "OneBot 消息撤回失败：target=${request.target.externalId} sinkMessageId=${request.sinkMessageId} routeId=$routeId"
            }
            MessageRecallResult.failed(it.message ?: "OneBot 消息撤回失败")
        }
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val kinds = kind?.let { setOf(it) } ?: supportedTargetKinds
        val accounts = gateway.availableAccounts().associateBy { it.accountId }
        val accountIds = accounts.keys.sorted()
        val targets = mutableListOf<MessageTargetCandidate>()

        if (TargetKind.GROUP in kinds) {
            targets += listTargetsForAccounts(accountIds, accounts, TargetKind.GROUP) { accountId ->
                gateway.listGroups(accountId)
            }
        }
        if (TargetKind.USER in kinds) {
            targets += listTargetsForAccounts(accountIds, accounts, TargetKind.USER) { accountId ->
                gateway.listFriends(accountId)
            }
        }
        return targets.sortedWith(compareBy<MessageTargetCandidate> { it.address.kind.name }.thenBy { it.name })
    }

    override suspend fun resolveMessageTarget(address: TargetAddress): MessageTargetCandidate? {
        if (!supportsTarget(address)) return null
        return listMessageTargets(address.kind).firstOrNull { it.address.stableValue() == address.stableValue() }
            ?: MessageTargetCandidate(
                address = address.copy(accountId = address.accountId?.trim()?.takeIf { it.isNotBlank() }),
                name = address.externalId,
                avatar = oneBotTargetAvatar(address.kind, address.externalId),
            )
    }

    private suspend fun sendPayloads(
        routeId: String,
        accountId: String,
        payloads: List<JsonArray>,
        failureLabel: String,
        send: suspend (JsonArray) -> String?,
    ): MessageSendResult {
        var sentCount = 0
        var sinkMessageId: String? = null
        return runCatching {
            payloads.forEach { payload ->
                send(payload)?.let { sinkMessageId = it }
                sentCount += 1
            }
            MessageSendResult.sent(sinkMessageId, sinkRouteId = routeId, sinkAccountId = accountId)
        }.getOrElse {
            MessageSendResult.failed(
                reason = it.message ?: failureLabel,
                retryable = sentCount == 0,
                partialSent = sentCount > 0,
            )
        }
    }

    private suspend fun listTargetsForAccounts(
        accountIds: List<String>,
        accounts: Map<String, OneBotRuntimeAccount>,
        kind: TargetKind,
        list: suspend (String) -> List<OneBotTargetCandidate>,
    ): List<MessageTargetCandidate> {
        val perAccount = buildList {
            accountIds.forEach { accountId ->
                addAll(
                    runCatching { list(accountId) }
                        .getOrElse {
                            logger.warn(it) { "OneBot 目标列表读取失败：accountId=$accountId kind=$kind" }
                            emptyList()
                        },
                )
            }
        }
        return perAccount
            .groupBy { it.id }
            .values
            .map { candidates ->
                val first = candidates.first()
                MessageTargetCandidate(
                    address = TargetAddress.of(
                        platformId = QQ_PLATFORM_ID.value,
                        kind = kind,
                        externalId = first.id,
                    ),
                    name = first.name,
                    avatar = oneBotTargetAvatar(kind, first.id),
                    sources = candidates
                        .mapNotNull { candidate -> accounts[candidate.accountId]?.toRoute()?.toTargetSource() }
                        .distinctBy { it.routeId },
                )
            }
    }

    private fun OneBotRuntimeAccount.toRoute(
        state: MessageSinkRouteState = MessageSinkRouteState.READY,
    ): MessageSinkRoute = MessageSinkRoute(
        routeId = routeId(accountId),
        transportId = transportId,
        transportName = transportName,
        targetPlatformId = QQ_PLATFORM_ID,
        accountId = accountId,
        accountName = name,
        accountAvatar = oneBotTargetAvatar(TargetKind.USER, accountId),
        enabled = true,
        state = state,
    )

    private fun routeId(accountId: String): String = "$transportId:${QQ_PLATFORM_ID.value}:$accountId"

    private fun accountIdFromRoute(routeId: String): String? {
        val prefix = "$transportId:${QQ_PLATFORM_ID.value}:"
        return routeId.removePrefix(prefix)
            .takeIf { it != routeId }
            ?.takeIf { it.isNotBlank() }
    }

    private fun onIncomingMessage(incoming: OneBotIncomingMessage) {
        if (!running) return

        val commandRequest = OneBotCommandMapper.toCommandRequest(
            sourcePlugin = pluginId,
            incoming = incoming,
        ) ?: return

        runCatching {
            kotlinx.coroutines.runBlocking {
                commandPublisher.publish(commandRequest)
            }
        }.onFailure {
            logger.warn(it) { "OneBot 命令事件提交失败：traceId=${commandRequest.traceId}" }
        }
    }

    private fun OneBotConfig.endpointLabel(): String {
        return when (mode) {
            OneBotConnectionMode.FORWARD_WS -> enabledConnections().joinToString { it.url }
            OneBotConnectionMode.REVERSE_WS -> "$host:$port"
        }
    }
}
