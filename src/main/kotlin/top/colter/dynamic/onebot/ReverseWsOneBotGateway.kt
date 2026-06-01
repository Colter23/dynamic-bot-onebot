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
import top.colter.dynamic.core.tools.loggerFor
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

private val logger = loggerFor<ReverseWsOneBotGateway>()

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
            OneBotIncomingListener(
                onIncomingMessage = onIncomingMessage,
                botAccountIdProvider = { runtimeBotAccountId() },
            ),
        )
        client = runtimeClient

        server = object : WebSocketServer(InetSocketAddress(config.host, config.port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                if (!isAuthorized(handshake)) {
                    logger.warn {
                        "拒绝 OneBot 反向连接：remote=${conn.remoteSocketAddress}，原因=鉴权失败"
                    }
                    conn.close(1008, "unauthorized")
                    return
                }

                val selfId = ConnectionUtils.parseSelfId(handshake)
                if (config.botId > 0 && selfId != config.botId) {
                    logger.warn {
                        "拒绝 OneBot 反向连接：selfId=$selfId，期望=${config.botId}"
                    }
                    conn.close(1008, "unexpected_self_id")
                    return
                }

                activeChannel.getAndSet(conn)?.takeIf { previous ->
                    previous != conn && previous.isOpen
                }?.close(1000, "replaced_by_new_reverse_connection")
                activeBot.set(Bot(conn, runtimeClient.actionFactory))

                logger.info {
                    "OneBot 反向连接已建立：selfId=$selfId，remote=${conn.remoteSocketAddress}"
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                runCatching {
                    runtimeClient.msgHandler.handle(message)
                }.onFailure {
                    logger.warn(it) {
                        "OneBot 消息解析失败：remote=${conn.remoteSocketAddress}"
                    }
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                if (activeChannel.compareAndSet(conn, null)) {
                    activeBot.set(null)
                }
                logger.debug {
                    "OneBot 反向连接已关闭：code=$code，remote=$remote，reason=$reason"
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                logger.warn(ex) {
                    "OneBot 连接异常：remote=${conn?.remoteSocketAddress}"
                }
            }

            override fun onStart() {
                logger.info {
                    "OneBot 反向网关监听中：${config.host}:${config.port}"
                }
            }
        }.also { it.start() }
    }

    override suspend fun sendPrivateMessage(userId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot().sendPrivateMsg(userId, message, false)
            action.requireSendAccepted("send_private_msg", userId)
        }
    }

    override suspend fun sendGroupMessage(groupId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot().sendGroupMsg(groupId, message, false)
            action.requireSendAccepted("send_group_msg", groupId)
        }
    }

    override suspend fun recallMessage(messageId: String) {
        withContext(Dispatchers.IO) {
            val id = messageId.toIntOrNull() ?: error("OneBot 消息 ID 无效：$messageId")
            requireBot().deleteMsg(id).requireActionAccepted("delete_msg")
        }
    }

    override suspend fun listGroups(): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot().getGroupList()
            action.requireQueryOk("get_group_list").map { group ->
                val id = group.groupId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = group.groupName?.takeIf { it.isNotBlank() } ?: id,
                )
            }
        }
    }

    override suspend fun listFriends(): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot().getFriendList()
            action.requireQueryOk("get_friend_list").map { friend ->
                val id = friend.userId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = friend.remark?.takeIf { it.isNotBlank() }
                        ?: friend.nickname?.takeIf { it.isNotBlank() }
                        ?: id,
                )
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            activeBot.set(null)
            activeChannel.getAndSet(null)?.close(1000, "shutdown")
            runCatching {
                server?.stop(1000, "shutdown")
            }.onFailure {
                logger.warn(it) { "OneBot 反向网关停止失败" }
            }
            server = null
            client?.eventExecutor?.shutdownNow()
            client?.wsPool?.shutdownNow()
            client = null
        }
    }

    private fun requireBot(): Bot {
        return activeBot.get() ?: error("OneBot 反向连接尚未建立")
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
                part.take(index) to part.substring(index + 1)
            }
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    }

    private fun runtimeBotAccountId(): String? {
        return config.botId.takeIf { it > 0 }?.toString()
            ?: activeBot.get()?.selfId?.takeIf { it > 0 }?.toString()
    }
}
