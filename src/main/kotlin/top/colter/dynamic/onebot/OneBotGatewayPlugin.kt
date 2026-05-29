package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventBus
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<OneBotGatewayPlugin>()

public class OneBotGatewayPlugin : MessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

    private var pluginId: String = ONEBOT_PLUGIN_ID
    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private lateinit var eventBus: EventBus
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
        eventBus = context.eventBus
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

    override suspend fun onMessage(event: MessageEvent) {
        if (!running) return

        val message = event.message
        val payloads = OneBotMessageMapper.toJsonArrayMessages(message)

        message.targets
            .filter { it.platformId == platformId }
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
                            "跳过 OneBot 目标：messageId=${message.id} targetId=${messageTarget.externalId} reason=${target.reason}"
                        }
                    }
                }
            }
    }

    override suspend fun onCommandResult(event: CommandResultEvent) {
        if (!running || event.target.address.platformId != platformId) return

        val payload = OneBotMessageMapper.toJsonArrayMessage(event.chain)
        runCatching {
            when (event.target.chatType) {
                TargetKind.GROUP -> gateway.sendGroupMessage(event.target.chatId.toLong(), payload)
                TargetKind.USER -> gateway.sendPrivateMessage(event.target.chatId.toLong(), payload)
                else -> logger.warn {
                    "跳过 OneBot 命令结果：traceId=${event.inReplyTo} unsupportedTarget=${event.target.chatType}:${event.target.chatId}"
                }
            }
        }.onFailure {
            logger.warn(it) {
                "OneBot 命令结果发送失败：traceId=${event.inReplyTo} target=${event.target.chatType}:${event.target.chatId}"
            }
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

    private suspend fun sendMessage(eventId: String, target: TargetAddress, action: suspend () -> Unit) {
        runCatching { action() }
            .onSuccess { MessageDeliveryRepository.markSent(eventId, target) }
            .onFailure {
                MessageDeliveryRepository.markFailed(eventId, target, it.message)
                logger.warn(it) {
                    "OneBot 消息发送失败：messageId=$eventId targetId=${target.externalId} reason=${it.message}"
                }
            }
    }

    private fun onIncomingMessage(incoming: OneBotIncomingMessage) {
        if (!running) return

        val commandEvent = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = pluginId,
            incoming = incoming,
        ) ?: return

        eventBus.broadcast(commandEvent)
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
        )
    }
}
