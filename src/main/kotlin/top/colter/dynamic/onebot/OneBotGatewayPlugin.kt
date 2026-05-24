package top.colter.dynamic.onebot

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.tools.logger

public class OneBotGatewayPlugin : MessageSinkPlugin {

    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private var running: Boolean = false

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

    override suspend fun onMessage(event: MessageEvent) {
        if (!running) return

        val message = event.message
        val payload = OneBotMessageMapper.toArrayMessage(message)

        message.subscriber.forEach { subscriber ->
            when (val target = OneBotTarget.fromSubscriber(subscriber)) {
                is OneBotTarget.Group -> sendMessage(
                    eventId = message.id,
                    targetId = subscriber.userId,
                    action = { gateway.sendGroupMessage(target.groupId, payload) },
                )
                is OneBotTarget.User -> sendMessage(
                    eventId = message.id,
                    targetId = subscriber.userId,
                    action = { gateway.sendPrivateMessage(target.userId, payload) },
                )
                is OneBotTarget.Unsupported -> logger.warn {
                    "pluginId=$ONEBOT_PLUGIN_ID eventId=${message.id} targetId=${subscriber.userId} action=route result=skipped reason=${target.reason}"
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
            }
        }.onFailure {
            logger.warn(it) {
                "pluginId=$ONEBOT_PLUGIN_ID traceId=${event.inReplyTo} action=send_command_result result=failed target=${event.target.chatType}:${event.target.chatId}"
            }
        }
    }

    private suspend fun sendMessage(eventId: Long, targetId: String, action: suspend () -> Unit) {
        runCatching { action() }
            .onFailure {
                logger.warn(it) {
                    "pluginId=$ONEBOT_PLUGIN_ID eventId=$eventId targetId=$targetId action=send result=failed error=${it.message}"
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
