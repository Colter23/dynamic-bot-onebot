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
import top.colter.dynamic.core.plugin.MessageSinkAccount
import top.colter.dynamic.core.plugin.MessageSinkAccountState
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()

public class OneBotGatewayPlugin : AccountRoutedMessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

    private var pluginId: String = ONEBOT_PLUGIN_ID
    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private lateinit var commandPublisher: CommandPublisher
    private var running: Boolean = false

    override val configId: String
        get() = pluginId
    override val configName: String = "OneBot 网关"
    override val configDescription: String = "OneBot 连接、账号路由与消息投递配置。"
    override val configClass = OneBotConfig::class
    override val configFormSpec = OneBotConfigForm.spec
    override val platformId: PlatformId = PlatformId.of("onebot")
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        commandPublisher = context.commandPublisher
        config = context.configService.loadOrCreate(pluginId, OneBotConfigForm.migrations) { OneBotConfig() }
        OneBotConfigForm.validate(config)
        logger.info { "OneBot 配置已加载：pluginId=$pluginId，accounts=${config.enabledAccounts().size}" }
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

        logger.info {
            "OneBot 已启动：mode=${config.mode} endpoint=${config.endpointLabel()} accounts=${config.enabledAccounts().joinToString { it.accountId }}"
        }
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

    override fun routingPolicy(): MessageSinkRoutingPolicy = config.routingPolicy

    override suspend fun listMessageSinkAccounts(target: TargetAddress?): List<MessageSinkAccount> {
        if (target != null) {
            if (target.platformId != platformId) return emptyList()
            if (target.kind !in supportedTargetKinds) return emptyList()
        }
        val readyAccountIds = if (running) gateway.availableAccountIds() else emptySet()
        return config.normalizedAccounts().map { account ->
            MessageSinkAccount(
                accountId = account.accountId,
                name = account.displayName,
                enabled = account.enabled,
                state = if (account.enabled && account.accountId in readyAccountIds) {
                    MessageSinkAccountState.READY
                } else {
                    MessageSinkAccountState.UNAVAILABLE
                },
                role = account.role,
            )
        }
    }

    override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
        val accountId = request.target.accountId?.trim()?.takeIf { it.isNotBlank() }
            ?: firstReadyAccountId()
            ?: return MessageSendResult.failed("OneBot 无可用账号")
        return sendMessage(request, accountId)
    }

    override suspend fun sendMessage(
        request: MessageDeliveryRequest,
        accountId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("OneBot 未运行")
        if (accountId !in config.enabledAccounts().map { it.accountId }) {
            return MessageSendResult.failed("OneBot 账号未配置：$accountId", retryable = false)
        }

        val message = request.message
        val payloads = OneBotMessageMapper.toJsonArrayMessages(message)

        return when (val target = OneBotTarget.fromAddress(request.target)) {
            is OneBotTarget.Group -> sendPayloads(accountId, payloads, "OneBot 消息发送失败") { payload ->
                gateway.sendGroupMessage(accountId, target.groupId, payload)
            }
            is OneBotTarget.User -> sendPayloads(accountId, payloads, "OneBot 消息发送失败") { payload ->
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

    override suspend fun sendCommandResult(request: CommandResultSendRequest): MessageSendResult {
        val accountId = request.target.address.accountId?.trim()?.takeIf { it.isNotBlank() }
            ?: firstReadyAccountId()
            ?: return MessageSendResult.failed("OneBot 无可用账号")
        return sendCommandResult(request, accountId)
    }

    override suspend fun sendCommandResult(
        request: CommandResultSendRequest,
        accountId: String,
    ): MessageSendResult {
        if (!running) return MessageSendResult.failed("OneBot 未运行")
        if (request.target.address.platformId != platformId) {
            return MessageSendResult.failed("目标平台不是 OneBot", retryable = false)
        }

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
            MessageSendResult.sent(sinkMessageId, sinkAccountId = accountId)
        }.getOrElse {
            logger.warn(it) {
                "OneBot 命令结果发送失败：traceId=${request.inReplyTo} target=${request.target.chatType}:${request.target.chatId} accountId=$accountId"
            }
            MessageSendResult.failed(it.message ?: "OneBot 命令结果发送失败")
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest): MessageRecallResult {
        val accountId = request.sinkAccountId?.trim()?.takeIf { it.isNotBlank() }
            ?: request.target.accountId?.trim()?.takeIf { it.isNotBlank() }
            ?: firstReadyAccountId()
            ?: return MessageRecallResult.failed("OneBot 无可用账号")
        return recallMessage(request, accountId)
    }

    override suspend fun recallMessage(request: MessageRecallRequest, accountId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("OneBot 未运行")
        if (request.target.platformId != platformId) {
            return MessageRecallResult.failed("目标平台不是 OneBot")
        }
        return runCatching {
            gateway.recallMessage(accountId, request.sinkMessageId)
            MessageRecallResult.recalled()
        }.getOrElse {
            logger.debug(it) {
                "OneBot 消息撤回失败：target=${request.target.externalId} sinkMessageId=${request.sinkMessageId} accountId=$accountId"
            }
            MessageRecallResult.failed(it.message ?: "OneBot 消息撤回失败")
        }
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val kinds = kind?.let { setOf(it) } ?: supportedTargetKinds
        val accounts = config.enabledAccounts().associateBy { it.accountId }
        val accountIds = gateway.availableAccountIds().filter { it in accounts.keys }
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
        return targets.distinctBy { it.address.stableValue() }
    }

    override suspend fun resolveMessageTarget(address: TargetAddress): MessageTargetCandidate? {
        if (address.platformId != platformId) return null
        if (address.kind !in supportedTargetKinds) return null
        return listMessageTargets(address.kind).firstOrNull { it.address == address }
            ?: MessageTargetCandidate(
                address = address,
                name = address.externalId,
                avatar = oneBotTargetAvatar(address.kind, address.externalId),
            )
    }

    private suspend fun sendPayloads(
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
            MessageSendResult.sent(sinkMessageId, sinkAccountId = accountId)
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
        accounts: Map<String, OneBotAccountConfig>,
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
        val autoTargets = perAccount
            .groupBy { it.id }
            .values
            .map { candidates ->
                val first = candidates.first()
                first.toMessageTarget(kind, accountId = null, suffix = "自动选择")
            }
        val accountTargets = perAccount.map { candidate ->
            val accountName = accounts[candidate.accountId]?.displayName ?: candidate.accountId
            candidate.toMessageTarget(kind, accountId = candidate.accountId, suffix = accountName)
        }
        return autoTargets + accountTargets
    }

    private fun OneBotTargetCandidate.toMessageTarget(
        kind: TargetKind,
        accountId: String?,
        suffix: String,
    ): MessageTargetCandidate {
        return MessageTargetCandidate(
            address = TargetAddress.of(
                platformId = platformId.value,
                kind = kind,
                externalId = id,
                accountId = accountId,
            ),
            name = "$name · $suffix",
            avatar = oneBotTargetAvatar(kind, id),
        )
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

    private fun firstReadyAccountId(): String? {
        val available = gateway.availableAccountIds()
        return config.enabledAccounts().firstOrNull { it.accountId in available }?.accountId
    }

    private fun OneBotConfig.endpointLabel(): String {
        return when (mode) {
            OneBotConnectionMode.FORWARD_WS -> enabledAccounts().joinToString { "${it.accountId}@${it.url}" }
            OneBotConnectionMode.REVERSE_WS -> "$host:$port"
        }
    }
}
