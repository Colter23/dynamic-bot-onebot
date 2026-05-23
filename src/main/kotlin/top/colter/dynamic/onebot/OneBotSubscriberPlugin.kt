package top.colter.dynamic.onebot

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.MessageEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.MessageSinkPlugin

public class OneBotSubscriberPlugin : MessageSinkPlugin {
    private val pluginId: String = "onebot-subscriber"

    private var config: OneBotConfig = OneBotConfig()
    private var gateway: OneBotGateway = NoopOneBotGateway()
    private var running: Boolean = false

    override fun init() {
        config = DefaultConfigService.loadOrCreate(pluginId) { OneBotConfig() }
        println("pluginId=onebot-subscriber action=init configPath=${DefaultConfigService.resolvePath(pluginId).toAbsolutePath()}")
    }

    override fun start() {
        if (running) return
        running = true
        gateway = OneBotClientGateway(config)
        gateway.connect(::onIncomingMessage)
        println("pluginId=onebot-subscriber action=start url=${config.url}")
    }

    override fun stop() {
        if (!running) return
        running = false
        runBlocking { gateway.close() }
        println("pluginId=onebot-subscriber action=stop")
    }

    override fun cleanup() {
        println("pluginId=onebot-subscriber action=cleanup")
    }

    override suspend fun onMessage(event: MessageEvent) {
        if (!running) return

        val message = event.message
        val payload = OneBotMessageFormatter.format(message)

        message.subscriber.forEach { subscriber ->
            val route = TargetRoute.fromSubscriber(subscriber)
            if (route is TargetRoute.Unsupported) {
                println(
                    "pluginId=onebot-subscriber eventId=${message.id} targetId=${subscriber.userId} action=route result=skipped reason=${route.reason}"
                )
                return@forEach
            }

            runCatching {
                when (route) {
                    is TargetRoute.Group -> gateway.sendGroupMessage(route.groupId, payload)
                    is TargetRoute.User -> gateway.sendPrivateMessage(route.userId, payload)
                    is TargetRoute.Unsupported -> Unit
                }
            }.onFailure {
                println(
                    "pluginId=onebot-subscriber eventId=${message.id} targetId=${subscriber.userId} action=send result=failed error=${it.message}"
                )
            }
        }
    }

    override suspend fun onCommandResult(event: CommandResultEvent) {
        if (!running || event.target.platform != "onebot") return
        val payload = OneBotMessageFormatter.format(event.chain)

        runCatching {
            when (event.target.chatType) {
                ChatType.GROUP -> gateway.sendGroupMessage(event.target.chatId.toLong(), payload)
                ChatType.PRIVATE -> gateway.sendPrivateMessage(event.target.chatId.toLong(), payload)
            }
        }.onFailure {
            println("pluginId=onebot-subscriber traceId=${event.inReplyTo} action=send_command_result result=failed error=${it.message}")
        }
    }

    private fun onIncomingMessage(incoming: OneBotIncomingMessage) {
        if (!running) return

        val commandEvent = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = pluginId,
            incoming = incoming,
        ) ?: return

        commandEvent.broadcast()
    }
}
