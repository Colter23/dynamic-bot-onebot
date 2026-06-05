package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<ForwardWsOneBotGateway>()

internal class ForwardWsOneBotGateway(
    private val config: OneBotConfig,
) : OneBotGateway {

    private val connections: MutableList<ForwardConnection> = mutableListOf()

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (connections.isNotEmpty()) return

        config.enabledConnections().forEachIndexed { index, connection ->
            val clientRef = arrayOfNulls<OneBotClient>(1)
            val connectionRef = arrayOfNulls<ForwardConnection>(1)
            val client = OneBotClient.create(
                BotConfig(connection.url, config.accessToken).apply {
                    isReconnect = config.reconnect
                    reconnectInterval = config.reconnectIntervalSeconds
                    reconnectMaxTimes = config.reconnectMaxTimes
                },
                OneBotIncomingListener(
                    onIncomingMessage = onIncomingMessage,
                    botAccountIdProvider = {
                        connectionRef[0]?.knownAccountId() ?: clientRef[0]?.runtimeAccountId()
                    },
                ),
            ).open()
            clientRef[0] = client
            val runtimeConnection = ForwardConnection(
                connectionId = "forward-$index",
                url = connection.url,
                client = client,
            )
            connectionRef[0] = runtimeConnection
            connections += runtimeConnection
        }
    }

    override suspend fun availableAccounts(): List<OneBotRuntimeAccount> = withContext(Dispatchers.IO) {
        connections
            .mapNotNull { connection -> connection.refreshRuntimeAccount() }
            .distinctBy { it.accountId }
            .sortedBy { it.accountId }
    }

    override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendPrivateMsg(userId, message, false)
            action.requireSendAccepted("send_private_msg", userId)
        }
    }

    override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendGroupMsg(groupId, message, false)
            action.requireSendAccepted("send_group_msg", groupId)
        }
    }

    override suspend fun recallMessage(accountId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val id = messageId.toIntOrNull() ?: error("OneBot 消息 ID 无效：$messageId")
            requireBot(accountId).deleteMsg(id).requireActionAccepted("delete_msg")
        }
    }

    override suspend fun listGroups(accountId: String): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).getGroupList()
            action.requireQueryOk("get_group_list").map { group ->
                val id = group.groupId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = group.groupName?.takeIf { it.isNotBlank() } ?: id,
                    accountId = accountId,
                )
            }
        }
    }

    override suspend fun listFriends(accountId: String): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).getFriendList()
            action.requireQueryOk("get_friend_list").map { friend ->
                val id = friend.userId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = friend.remark?.takeIf { it.isNotBlank() }
                        ?: friend.nickname?.takeIf { it.isNotBlank() }
                        ?: id,
                    accountId = accountId,
                )
            }
        }
    }

    override suspend fun close() {
        val active = connections.toList()
        connections.clear()
        active.forEach { connection ->
            withContext(Dispatchers.IO) {
                runCatching {
                    connection.client.close()
                }.onFailure {
                    logger.warn(it) { "OneBot 正向连接关闭失败：connectionId=${connection.connectionId}，url=${connection.url}" }
                }
            }
        }
    }

    private fun requireBot(accountId: String): Bot {
        return connections.firstNotNullOfOrNull { connection ->
            connection.client.takeIf { connection.knownAccountId() == accountId }?.bot
        } ?: error("OneBot 正向连接尚未就绪：accountId=$accountId")
    }

    private fun ForwardConnection.knownAccountId(): String? {
        return account?.accountId ?: client.runtimeAccountId()
    }

    private fun ForwardConnection.refreshRuntimeAccount(): OneBotRuntimeAccount? {
        client.runtimeAccountId()?.let { accountId ->
            account?.takeIf { it.accountId == accountId }?.let { return it }
            return OneBotRuntimeAccount(accountId = accountId).also { account = it }
        }

        account?.let { return it }

        return runCatching {
            val bot = client.bot ?: error("OneBot Bot 尚未初始化")
            val info = bot.getLoginInfo().requireDataOk("get_login_info")
            val accountId = info.userId.takeIf { it > 0 }?.toString()
                ?: error("OneBot 登录信息缺少有效 user_id")
            OneBotRuntimeAccount(
                accountId = accountId,
                name = info.nickname?.takeIf { it.isNotBlank() } ?: "QQ机器人 $accountId",
            )
        }.onSuccess {
            account = it
            logger.info { "OneBot 正向连接账号已识别：connectionId=$connectionId，accountId=${it.accountId}，name=${it.name}" }
        }.onFailure {
            logger.warn(it) { "OneBot 正向连接账号识别失败：connectionId=$connectionId，url=$url" }
        }.getOrNull()
    }

    private fun OneBotClient.runtimeAccountId(): String? {
        return bot?.selfId?.takeIf { it > 0 }?.toString()
    }

    private data class ForwardConnection(
        val connectionId: String,
        val url: String,
        val client: OneBotClient,
        @Volatile
        var account: OneBotRuntimeAccount? = null,
    )
}
