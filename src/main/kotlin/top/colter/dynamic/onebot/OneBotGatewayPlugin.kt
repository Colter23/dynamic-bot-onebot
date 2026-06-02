package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()

public class OneBotGatewayPlugin : MessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

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
    override val platformId: PlatformId = PlatformId.of("onebot")
    override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        commandPublisher = context.commandPublisher
        config = context.configService.loadOrCreate(pluginId) { OneBotConfig() }
        logger.info { "OneBot 配置已加载：pluginId=$pluginId" }
    }

    override suspend fun onStart() {
        if (running) return

        gateway = OneBotGatewayFactory.create(config)
        runCatching {
            gateway.connect(::onIncomingMessage)
        }.onFailure {
            gateway.close()
            gateway = NoopOneBotGateway()
            logger.error(it) {
                "OneBot 启动失败：mode=${config.mode} endpoint=${config.endpointLabel()}"
            }
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

    override suspend fun sendMessage(request: MessageDeliveryRequest): MessageSendResult {
        if (!running) return MessageSendResult.failed("OneBot 未运行")

        val message = request.message
        val payloads = OneBotMessageMapper.toJsonArrayMessages(message)

        return when (val target = OneBotTarget.fromAddress(request.target)) {
            is OneBotTarget.Group -> sendMessage {
                var sinkMessageId: String? = null
                for (payload in payloads) {
                    gateway.sendGroupMessage(target.groupId, payload)?.let { sinkMessageId = it }
                }
                sinkMessageId
            }
            is OneBotTarget.User -> sendMessage {
                var sinkMessageId: String? = null
                for (payload in payloads) {
                    gateway.sendPrivateMessage(target.userId, payload)?.let { sinkMessageId = it }
                }
                sinkMessageId
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
        if (!running) return MessageSendResult.failed("OneBot 未运行")
        if (request.target.address.platformId != platformId) {
            return MessageSendResult.failed("目标平台不是 OneBot", retryable = false)
        }

        val payload = OneBotMessageMapper.toJsonArrayMessage(request.chain)
        return runCatching {
            val sinkMessageId = when (request.target.chatType) {
                TargetKind.GROUP -> gateway.sendGroupMessage(request.target.chatId.toLong(), payload)
                TargetKind.USER -> gateway.sendPrivateMessage(request.target.chatId.toLong(), payload)
                else -> {
                    logger.warn {
                    "跳过 OneBot 命令结果：traceId=${request.inReplyTo}，不支持的目标=${request.target.chatType}:${request.target.chatId}"
                    }
                    null
                }
            }
            MessageSendResult.sent(sinkMessageId)
        }.getOrElse {
            logger.warn(it) {
                "OneBot 命令结果发送失败：traceId=${request.inReplyTo} target=${request.target.chatType}:${request.target.chatId}"
            }
            MessageSendResult.failed(it.message ?: "OneBot 命令结果发送失败")
        }
    }

    override suspend fun recallMessage(request: MessageRecallRequest): MessageRecallResult {
        if (!running) return MessageRecallResult.failed("OneBot 未运行")
        if (request.target.platformId != platformId) {
            return MessageRecallResult.failed("目标平台不是 OneBot")
        }
        return runCatching {
            gateway.recallMessage(request.sinkMessageId)
            MessageRecallResult.recalled()
        }.getOrElse {
            logger.debug(it) {
                "OneBot 消息撤回失败：target=${request.target.externalId} sinkMessageId=${request.sinkMessageId}"
            }
            MessageRecallResult.failed(it.message ?: "OneBot 消息撤回失败")
        }
    }

    override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val kinds = kind?.let { setOf(it) } ?: supportedTargetKinds
        return buildList {
            if (TargetKind.GROUP in kinds) {
                addAll(gateway.listGroups().map { it.toCandidate(TargetKind.GROUP) })
            }
            if (TargetKind.USER in kinds) {
                addAll(gateway.listFriends().map { it.toCandidate(TargetKind.USER) })
            }
        }
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

    private suspend fun sendMessage(action: suspend () -> String?): MessageSendResult {
        return runCatching {
            MessageSendResult.sent(action())
        }.getOrElse {
            MessageSendResult.failed(it.message ?: "OneBot 消息发送失败")
        }
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
            OneBotConnectionMode.FORWARD_WS -> url
            OneBotConnectionMode.REVERSE_WS -> "$host:$port"
        }
    }

    private fun OneBotTargetCandidate.toCandidate(kind: TargetKind): MessageTargetCandidate {
        return MessageTargetCandidate(
            address = TargetAddress.of(
                platformId = platformId.value,
                kind = kind,
                externalId = id,
            ),
            name = name,
            avatar = oneBotTargetAvatar(kind, id),
        )
    }
}
