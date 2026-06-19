package top.colter.dynamic.onebot

import com.google.gson.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkFeature
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvice
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvisor
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeResult
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()
private val QQ_PLATFORM_ID = PlatformId.of("qq")
private const val SINK_MESSAGE_ID_SEPARATOR = ""  // ASCII Unit Separator，不可能出现在正常消息 ID 中

public class OneBotGatewayPlugin :
    AccountRoutedMessageSinkPlugin,
    MessageSinkMediaDeliveryAdvisor,
    ConfigurablePlugin<OneBotConfig> {

    private var pluginId: String = ONEBOT_PLUGIN_ID
    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private var incomingMessagePublisher: IncomingMessagePublisher = IncomingMessagePublisher { }
    private lateinit var pluginScope: CoroutineScope
    private var incomingScope: CoroutineScope? = null
    private var incomingJob: Job? = null
    private var running: Boolean = false

    override val configId: String
        get() = pluginId
    override val configName: String = "OneBot 网关"
    override val configDescription: String = "OneBot 连接与消息投递配置。"
    override val configClass = OneBotConfig::class
    override val configFormSpec = OneBotConfigForm.spec

    override val transportId: String = "onebot"
    override val transportName: String = "OneBot"
    override val supportedMessageFeatures: Set<MessageSinkFeature> = setOf(MessageSinkFeature.MERGED_FORWARD)
    override val supportedTargetPlatforms: Set<PlatformId> = setOf(QQ_PLATFORM_ID)
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        incomingMessagePublisher = context.incomingMessagePublisher
        pluginScope = context.scope
        config = context.configService.loadOrCreate(pluginId, OneBotConfigForm.migrations) { OneBotConfig() }
        OneBotConfigForm.validate(config)
        logger.info { "OneBot 配置已加载：pluginId=$pluginId，mode=${config.mode}" }
    }

    override suspend fun onStart() {
        if (running) return

        OneBotConfigForm.validate(config)
        gateway = OneBotGatewayFactory.create(config)
        startIncomingScope()
        runCatching {
            gateway.connect(::onIncomingMessage)
        }.onFailure {
            stopIncomingScope()
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
        stopIncomingScope()
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
        return gateway.availableAccounts().map { account ->
            account.toRoute(if (running) account.state else MessageSinkRouteState.UNAVAILABLE)
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
        if (!isAccountReady(accountId)) {
            return MessageSendResult.failed("OneBot 账号不可用：$accountId")
        }

        val message = request.message
        val units = OneBotMessageMapper.toSendUnits(message, forwardSenderUin = accountId)

        return when (val target = OneBotTarget.fromAddress(request.target)) {
            is OneBotTarget.Group -> sendUnits(
                routeId = routeId,
                accountId = accountId,
                units = units,
                failureLabel = "OneBot 消息发送失败",
                sendNormal = { payload -> gateway.sendGroupMessage(accountId, target.groupId, payload) },
                sendForward = { payload -> gateway.sendGroupForwardMessage(accountId, target.groupId, payload) },
            )
            is OneBotTarget.User -> sendUnits(
                routeId = routeId,
                accountId = accountId,
                units = units,
                failureLabel = "OneBot 消息发送失败",
                sendNormal = { payload -> gateway.sendPrivateMessage(accountId, target.userId, payload) },
                sendForward = { payload -> gateway.sendPrivateForwardMessage(accountId, target.userId, payload) },
            )
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
        if (!isAccountReady(accountId)) {
            return MessageSendResult.failed("OneBot 账号不可用：$accountId")
        }

        val units = OneBotMessageMapper.toSendUnits(
            batches = request.chain,
            forwardSenderUin = accountId,
            replyToMessageId = request.inReplyTo,
        )
        return when (val target = OneBotTarget.fromAddress(request.target.address)) {
            is OneBotTarget.Group -> sendUnits(
                routeId = routeId,
                accountId = accountId,
                units = units,
                failureLabel = "OneBot 命令结果发送失败",
                sendNormal = { payload -> gateway.sendGroupMessage(accountId, target.groupId, payload) },
                sendForward = { payload ->
                    gateway.sendGroupForwardMessage(accountId, target.groupId, payload)
                },
            )
            is OneBotTarget.User -> sendUnits(
                routeId = routeId,
                accountId = accountId,
                units = units,
                failureLabel = "OneBot 命令结果发送失败",
                sendNormal = { payload -> gateway.sendPrivateMessage(accountId, target.userId, payload) },
                sendForward = { payload ->
                    gateway.sendPrivateForwardMessage(accountId, target.userId, payload)
                },
            )
            is OneBotTarget.Unsupported -> {
                logger.warn {
                    "跳过 OneBot 命令结果：traceId=${request.inReplyTo}，目标=${request.target.chatType}:${request.target.chatId}，原因=${target.reason}"
                }
                MessageSendResult.failed(target.reason, retryable = false)
            }
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest, routeId: String): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("OneBot 未运行")
        if (!supportsTarget(request.target)) {
            return MessageRecallResult.failed("目标平台不是 QQ 或类型不受支持")
        }
        val accountId = accountIdFromRoute(routeId)
            ?: return MessageRecallResult.failed("OneBot 路线 ID 无效：$routeId")
        if (!isAccountReady(accountId)) {
            return MessageRecallResult.failed("OneBot 账号不可用：$accountId")
        }
        val messageIds = decodeSinkMessageIds(request.sinkMessageId)
        if (messageIds.isEmpty()) {
            return MessageRecallResult.failed("OneBot 消息 ID 为空，无法撤回")
        }
        var recalledAny = false
        var lastError: Throwable? = null
        for (messageId in messageIds) {
            runCatching { gateway.recallMessage(accountId, messageId) }
                .onSuccess { recalledAny = true }
                .onFailure { error ->
                    lastError = error
                    logger.debug(error) {
                        "OneBot 消息分段撤回失败：target=${request.target.externalId} sinkMessageId=$messageId routeId=$routeId"
                    }
                }
        }
        return if (recalledAny) {
            MessageRecallResult.recalled()
        } else {
            MessageRecallResult.failed(lastError?.message ?: "OneBot 消息撤回失败")
        }
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val kinds = kind?.let { setOf(it) } ?: supportedTargetKinds
        val accounts = gateway.availableAccounts()
            .filter { it.state == MessageSinkRouteState.READY }
            .associateBy { it.accountId }
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

    override suspend fun adviseMediaDelivery(
        request: MessageSinkMediaDeliveryAdviceRequest,
    ): MessageSinkMediaDeliveryAdvice {
        if (!running) return MessageSinkMediaDeliveryAdvice()
        val accountId = request.accountIdFromRequest() ?: return MessageSinkMediaDeliveryAdvice()
        val info = runCatching { gateway.implementationInfo(accountId) }.getOrDefault(OneBotImplementationInfo())
        val hints = runCatching {
            gateway.connectionHints(
                accountId = accountId,
                webAdminHost = request.webAdminHost,
                webAdminPort = request.webAdminPort,
            )
        }.getOrDefault(OneBotConnectionHints())
        val localConfidence = when (info.kind) {
            OneBotImplementationKind.NAPCAT -> MessageSinkMediaDeliveryConfidence.UNKNOWN
            OneBotImplementationKind.LLONEBOT -> if (hints.sameHostLikely) {
                MessageSinkMediaDeliveryConfidence.LIKELY
            } else {
                MessageSinkMediaDeliveryConfidence.UNKNOWN
            }
            OneBotImplementationKind.UNKNOWN -> MessageSinkMediaDeliveryConfidence.UNKNOWN
        }
        return MessageSinkMediaDeliveryAdvice(
            clientName = info.appName,
            clientVersion = info.appVersion,
            localFileConfidence = localConfidence,
            signedUrlBaseCandidates = if (request.webAdminEnabled) hints.signedUrlBaseCandidates else emptyList(),
        )
    }

    override suspend fun probeMediaDelivery(
        request: MessageSinkMediaDeliveryProbeRequest,
    ): MessageSinkMediaDeliveryProbeResult {
        if (!running) return MessageSinkMediaDeliveryProbeResult.unknown("OneBot 未运行")
        val accountId = request.accountIdFromRequest() ?: return MessageSinkMediaDeliveryProbeResult.unknown("缺少 OneBot 账号")
        return when (request.method) {
            MessageSinkMediaDeliveryMethod.SIGNED_URL -> probeDownload(accountId, request.uri)
            MessageSinkMediaDeliveryMethod.LOCAL_FILE -> {
                val info = runCatching { gateway.implementationInfo(accountId) }.getOrDefault(OneBotImplementationInfo())
                when (info.kind) {
                    OneBotImplementationKind.NAPCAT -> probeDownload(accountId, request.uri)
                    OneBotImplementationKind.LLONEBOT -> MessageSinkMediaDeliveryProbeResult.unknown(
                        "LLOneBot 无可靠的无副作用本地文件探测接口",
                    )
                    OneBotImplementationKind.UNKNOWN -> MessageSinkMediaDeliveryProbeResult.unknown(
                        "未知 OneBot 客户端，跳过本地文件探测",
                    )
                }
            }
        }
    }

    private fun encodeSinkMessageIds(ids: List<String>): String? {
        val cleaned = ids.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        return cleaned.takeIf { it.isNotEmpty() }?.joinToString(SINK_MESSAGE_ID_SEPARATOR)
    }

    private fun decodeSinkMessageIds(encoded: String?): List<String> {
        return encoded
            ?.split(SINK_MESSAGE_ID_SEPARATOR)
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .orEmpty()
    }

    private suspend fun sendUnits(
        routeId: String,
        accountId: String,
        units: List<OneBotSendUnit>,
        failureLabel: String,
        sendNormal: suspend (JsonArray) -> String?,
        sendForward: suspend (List<Map<String, Any>>) -> String?,
    ): MessageSendResult {
        var sentCount = 0
        val sinkMessageIds = mutableListOf<String>()
        return runCatching {
            units.forEach { unit ->
                when (unit) {
                    is OneBotSendUnit.Normal -> sendNormal(unit.message)?.let { sinkMessageIds += it }
                    is OneBotSendUnit.Forward -> sendForward(unit.messages)?.let { sinkMessageIds += it }
                }
                sentCount += 1
            }
            MessageSendResult.sent(
                sinkMessageId = encodeSinkMessageIds(sinkMessageIds),
                sinkRouteId = routeId,
                sinkAccountId = accountId,
                sinkTransportId = transportId,
                sinkMessageIds = sinkMessageIds,
            )
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

    private fun MessageSinkMediaDeliveryAdviceRequest.accountIdFromRequest(): String? {
        return routeId?.let(::accountIdFromRoute)
            ?: accountId?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun MessageSinkMediaDeliveryProbeRequest.accountIdFromRequest(): String? {
        return routeId?.let(::accountIdFromRoute)
            ?: accountId?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun probeDownload(accountId: String, uri: String): MessageSinkMediaDeliveryProbeResult {
        val result = gateway.probeDownload(accountId, uri)
        return if (result.available) {
            MessageSinkMediaDeliveryProbeResult.available(result.reason)
        } else {
            MessageSinkMediaDeliveryProbeResult.unavailable(result.reason)
        }
    }

    private suspend fun isAccountReady(accountId: String): Boolean {
        return gateway.availableAccounts().any {
            it.accountId == accountId && it.state == MessageSinkRouteState.READY
        }
    }

    private fun onIncomingMessage(incoming: OneBotIncomingMessage) {
        if (!running) return

        val incomingMessage = OneBotCommandMapper.toIncomingMessage(incoming)

        val scope = incomingScope ?: return
        scope.launch {
            if (!running) return@launch
            runCatching {
                incomingMessagePublisher.publish(
                    IncomingMessagePublishRequest(
                        message = incomingMessage,
                        traceId = incoming.messageId,
                        replyToMessageId = incoming.messageId,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn(error) {
                    "OneBot 入站消息提交失败：messageId=${incoming.messageId.ifBlank { "-" }}"
                }
            }
        }
    }

    private fun startIncomingScope() {
        stopIncomingScope()
        val parentJob = pluginScope.coroutineContext[Job]
        val job = if (parentJob != null) SupervisorJob(parentJob) else SupervisorJob()
        incomingJob = job
        incomingScope = CoroutineScope(pluginScope.coroutineContext.minusKey(Job) + job)
    }

    private fun stopIncomingScope() {
        incomingJob?.cancel("OneBot plugin stopped")
        incomingJob = null
        incomingScope = null
    }

    private fun OneBotConfig.endpointLabel(): String {
        return when (mode) {
            OneBotConnectionMode.FORWARD_WS -> enabledConnections()
                .joinToString { it.url }
                .ifBlank { "未启用正向连接" }
            OneBotConnectionMode.REVERSE_WS -> "$host:$port"
        }
    }
}
