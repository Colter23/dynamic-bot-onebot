package top.colter.dynamic.onebot

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.MessageTarget
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.tools.logger

public class OneBotGatewayPlugin : MessageSinkPlugin, ConfigurablePlugin<OneBotConfig> {

    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private var running: Boolean = false

    override val configId: String = ONEBOT_PLUGIN_ID
    override val configName: String = "OneBot 网关"
    override val configDescription: String = "OneBot 连接与消息投递配置。"
    override val configClass = OneBotConfig::class
    override val configFormSpec = OneBotConfigForm.spec

    override fun init() {
        config = DefaultConfigService.loadOrCreate(ONEBOT_PLUGIN_ID) { OneBotConfig() }
        logger.info {
            "pluginId=$ONEBOT_PLUGIN_ID action=init configPath=${DefaultConfigService.resolvePath(ONEBOT_PLUGIN_ID).toAbsolutePath()}"
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
                "pluginId=$ONEBOT_PLUGIN_ID action=start result=failed mode=${config.mode} endpoint=${config.endpointLabel()}"
            }
            throw it
        }
        running = true

        logger.info {
            "pluginId=$ONEBOT_PLUGIN_ID action=start mode=${config.mode} endpoint=${config.endpointLabel()}"
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        runBlocking { gateway.close() }
        gateway = NoopOneBotGateway()
        logger.info { "pluginId=$ONEBOT_PLUGIN_ID action=stop" }
    }

    override fun cleanup() {
        logger.info { "pluginId=$ONEBOT_PLUGIN_ID action=cleanup" }
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
        val payloads = OneBotMessageMapper.toArrayMessages(message)

        message.targets
            .filter { it.platformId == ONEBOT_PLUGIN_ID || it.platformId == "onebot" }
            .forEach { messageTarget ->
                when (val target = OneBotTarget.fromMessageTarget(messageTarget)) {
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
                            "pluginId=$ONEBOT_PLUGIN_ID eventId=${message.id} targetId=${messageTarget.targetId} action=route result=skipped reason=${target.reason}"
                        }
                    }
                }
            }
    }

    override suspend fun onCommandResult(event: CommandResultEvent) {
        if (!running || event.target.platform != "onebot") return

        val payload = OneBotMessageMapper.toArrayMessage(event.chain)
        runCatching {
            when (event.target.chatType) {
                ChatType.GROUP -> gateway.sendGroupMessage(event.target.chatId.toLong(), payload)
                ChatType.PRIVATE -> gateway.sendPrivateMessage(event.target.chatId.toLong(), payload)
                ChatType.CHANNEL -> logger.warn {
                    "pluginId=$ONEBOT_PLUGIN_ID traceId=${event.inReplyTo} action=send_command_result result=skipped reason=unsupported_channel target=${event.target.chatId}"
                }
            }
        }.onFailure {
            logger.warn(it) {
                "pluginId=$ONEBOT_PLUGIN_ID traceId=${event.inReplyTo} action=send_command_result result=failed target=${event.target.chatType}:${event.target.chatId}"
            }
        }
    }

    private suspend fun sendMessage(eventId: Long, target: MessageTarget, action: suspend () -> Unit) {
        runCatching { action() }
            .onSuccess { MessageDeliveryRepository.markSent(eventId, target) }
            .onFailure {
                MessageDeliveryRepository.markFailed(eventId, target, it.message)
                logger.warn(it) {
                    "pluginId=$ONEBOT_PLUGIN_ID eventId=$eventId targetId=${target.targetId} action=send result=failed error=${it.message}"
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
}
