package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import cn.evole.onebot.client.utils.ConnectionUtils
import cn.evole.onebot.sdk.websocket.WebSocket
import cn.evole.onebot.sdk.websocket.handshake.ClientHandshake
import cn.evole.onebot.sdk.websocket.server.WebSocketServer
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.tools.logger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

internal class ReverseWsOneBotGateway(
    private val config: OneBotConfig,
) : OneBotGateway {

    private val activeBot = AtomicReference<Bot?>()
    private val activeChannel = AtomicReference<WebSocket?>()

    @Volatile
    private var client: OneBotClient? = null

    @Volatile
    private var server: WebSocketServer? = null

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (server != null) return

        val runtimeClient = OneBotClient.create(
            BotConfig("ws://${config.host}:${config.port}", config.accessToken).apply {
                if (config.botId > 0) {
                    botId = config.botId
                }
            },
            OneBotIncomingListener(onIncomingMessage),
        )
        client = runtimeClient

        server = object : WebSocketServer(InetSocketAddress(config.host, config.port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                if (!isAuthorized(handshake)) {
                    logger.warn {
                        "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=accept result=denied remote=${conn.remoteSocketAddress}"
                    }
                    conn.close(1008, "unauthorized")
                    return
                }

                val selfId = ConnectionUtils.parseSelfId(handshake)
                if (config.botId > 0 && selfId != config.botId) {
                    logger.warn {
                        "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=accept result=denied selfId=$selfId expectedSelfId=${config.botId}"
                    }
                    conn.close(1008, "unexpected_self_id")
                    return
                }

                activeChannel.getAndSet(conn)?.takeIf { previous ->
                    previous != conn && previous.isOpen
                }?.close(1000, "replaced_by_new_reverse_connection")
                activeBot.set(Bot(conn, runtimeClient.actionFactory))

                logger.info {
                    "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=accept result=connected selfId=$selfId remote=${conn.remoteSocketAddress}"
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                runCatching {
                    runtimeClient.msgHandler.handle(message)
                }.onFailure {
                    logger.warn(it) {
                        "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=receive result=failed remote=${conn.remoteSocketAddress}"
                    }
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                if (activeChannel.compareAndSet(conn, null)) {
                    activeBot.set(null)
                }
                logger.info {
                    "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=close code=$code remote=$remote reason=$reason"
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                logger.warn(ex) {
                    "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=connection_error remote=${conn?.remoteSocketAddress}"
                }
            }

            override fun onStart() {
                logger.info {
                    "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=listen host=${config.host} port=${config.port}"
                }
            }
        }.also { it.start() }
    }

    override suspend fun sendPrivateMessage(userId: Long, message: JsonArray) {
        withContext(Dispatchers.IO) {
            val action = requireBot().sendPrivateMsg(userId, message, false)
            action.requireOk("send_private_msg", userId)
        }
    }

    override suspend fun sendGroupMessage(groupId: Long, message: JsonArray) {
        withContext(Dispatchers.IO) {
            val action = requireBot().sendGroupMsg(groupId, message, false)
            action.requireOk("send_group_msg", groupId)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            activeBot.set(null)
            activeChannel.getAndSet(null)?.close(1000, "shutdown")
            runCatching {
                server?.stop(1000, "shutdown")
            }.onFailure {
                logger.warn(it) { "pluginId=$ONEBOT_PLUGIN_ID mode=REVERSE_WS action=stop result=failed" }
            }
            server = null
            client?.eventExecutor?.shutdownNow()
            client?.wsPool?.shutdownNow()
            client = null
        }
    }

    private fun requireBot(): Bot {
        return activeBot.get() ?: error("onebot_reverse_ws_not_connected")
    }

    private fun isAuthorized(handshake: ClientHandshake): Boolean {
        if (config.accessToken.isBlank()) return true
        return tokenCandidates(handshake).any { it == config.accessToken }
    }

    private fun tokenCandidates(handshake: ClientHandshake): Sequence<String> = sequence {
        headerValue(handshake, "Authorization", "authorization")?.let { authorization ->
            yield(authorization.bearerValue())
            yield(authorization)
        }
        headerValue(handshake, "X-Access-Token", "x-access-token")?.let { yield(it) }
        headerValue(handshake, "access_token")?.let { yield(it) }
        queryValue(handshake.resourceDescriptor, "access_token")?.let { yield(it) }
    }

    private fun headerValue(handshake: ClientHandshake, vararg names: String): String? {
        return names.asSequence()
            .mapNotNull { name -> handshake.getFieldValue(name)?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
    }

    private fun String.bearerValue(): String {
        return split(Regex("\\s+"), limit = 2)
            .takeIf { it.size == 2 && it[0].equals("Bearer", ignoreCase = true) }
            ?.get(1)
            ?.trim()
            ?: this
    }

    private fun queryValue(resourceDescriptor: String?, name: String): String? {
        val query = runCatching { URI(resourceDescriptor ?: "").rawQuery }.getOrNull() ?: return null
        return query
            .split("&")
            .asSequence()
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                part.substring(0, index) to part.substring(index + 1)
            }
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    }
}
