package top.colter.dynamic.onebot

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()

public class OneBotGatewayPlugin : MessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private var running: Boolean = false

    override val configId: String = ONEBOT_PLUGIN_ID
    override val configName: String = "OneBot 网关"
    override val configDescription: String = "OneBot 连接与消息投递配置。"
    override val configClass = OneBotConfig::class
    override val configFormSpec = OneBotConfigForm.spec
    override val targetPlatformId: String = "onebot"
    override val supportedTargetTypes: Set<TargetKind> = setOf(TargetKind.GROUP, TargetKind.USER)

    override fun init() {
        config = DefaultConfigService.loadOrCreate(ONEBOT_PLUGIN_ID) { OneBotConfig() }
        logger.info {
            "OneBot 配置已加载：path=${DefaultConfigService.resolvePath(ONEBOT_PLUGIN_ID).toAbsolutePath()}"
        }
    }

    override fun start() {
        if (running) return

        gateway = OneBotGatewayFactory.create(config)
        runCatching {
            gateway.connect(::onIncomingMessage)
        }.onFailure {
            runBlocking { gateway.close() }
            gateway = NoopOneBotGateway()
            logger.error(it) {
                "OneBot 启动失败：mode=${config.mode}，endpoint=${config.endpointLabel()}"
            }
            throw it
        }
        running = true

        logger.info {
            "OneBot 已启动：mode=${config.mode}，endpoint=${config.endpointLabel()}"
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        runBlocking { gateway.close() }
        gateway = NoopOneBotGateway()
        logger.info { "OneBot 已停止" }
    }

    override fun cleanup() {
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
                "OneBot 配置已保存；需要重启 OneBot 插件以重新连接"
            } else {
                "OneBot 配置未变化"
            },
        )
    }

    override suspend fun onMessage(event: MessageEvent) {
        if (!running) return

        val message = event.message
        val payloads = OneBotMessageMapper.toJsonArrayMessages(message)

        message.targets
            .filter { it.platformId.value == ONEBOT_PLUGIN_ID || it.platformId.value == targetPlatformId }
            .forEach { messageTarget ->
                when (val target = OneBotTarget.fromAddress(messageTarget)) {
                    is OneBotTarget.Group -> sendMessage(
                        eventId = message.id,
                        target = messageTarget,
                        action = {
                            payloads.forEach { payload ->
                                gateway.sendGroupMessage(target.groupId, payload)
                            }
                        },
                    )
                    is OneBotTarget.User -> sendMessage(
                        eventId = message.id,
                        target = messageTarget,
                        action = {
                            payloads.forEach { payload ->
                                gateway.sendPrivateMessage(target.userId, payload)
                            }
                        },
                    )
                    is OneBotTarget.Unsupported -> {
                        MessageDeliveryRepository.markFailed(message.id, messageTarget, target.reason)
                        logger.warn {
                            "跳过 OneBot 目标：messageId=${message.id}，targetId=${messageTarget.externalId}，原因=${target.reason}"
                        }
                    }
                }
            }
    }

    override suspend fun onCommandResult(event: CommandResultEvent) {
        if (!running || event.target.platform != "onebot") return

        val payload = OneBotMessageMapper.toJsonArrayMessage(event.chain)
        runCatching {
            when (event.target.chatType) {
                ChatType.GROUP -> gateway.sendGroupMessage(event.target.chatId.toLong(), payload)
                ChatType.PRIVATE -> gateway.sendPrivateMessage(event.target.chatId.toLong(), payload)
                ChatType.CHANNEL -> logger.warn {
                    "跳过 OneBot 命令回执：traceId=${event.inReplyTo}，原因=不支持频道，targetId=${event.target.chatId}"
                }
            }
        }.onFailure {
            logger.warn(it) {
                "OneBot 命令回执发送失败：traceId=${event.inReplyTo}，target=${event.target.chatType}:${event.target.chatId}"
            }
        }
    }

    override suspend fun listMessageTargets(type: TargetKind?): List<MessageTargetCandidate> {
        if (!running) return emptyList()
        val types = type?.let { setOf(it) } ?: supportedTargetTypes
        return buildList {
            if (TargetKind.GROUP in types) {
                addAll(gateway.listGroups().map { it.toCandidate(TargetKind.GROUP) })
            }
            if (TargetKind.USER in types) {
                addAll(gateway.listFriends().map { it.toCandidate(TargetKind.USER) })
            }
        }
    }

    private suspend fun sendMessage(eventId: String, target: TargetAddress, action: suspend () -> Unit) {
        runCatching { action() }
            .onSuccess { MessageDeliveryRepository.markSent(eventId, target) }
            .onFailure {
                MessageDeliveryRepository.markFailed(eventId, target, it.message)
                logger.warn(it) {
                    "OneBot 消息发送失败：messageId=$eventId，targetId=${target.externalId}，原因=${it.message}"
                }
            }
    }

    private fun onIncomingMessage(incoming: OneBotIncomingMessage) {
        if (!running) return

        val commandEvent = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = incoming,
        ) ?: return

        commandEvent.broadcast()
    }

    private fun OneBotConfig.endpointLabel(): String {
        return when (mode) {
            OneBotConnectionMode.FORWARD_WS -> url
            OneBotConnectionMode.REVERSE_WS -> "$host:$port"
        }
    }

    private fun OneBotTargetCandidate.toCandidate(type: TargetKind): MessageTargetCandidate {
        return MessageTargetCandidate(
            platformId = targetPlatformId,
            type = type,
            targetId = id,
            name = name,
        )
    }
}
