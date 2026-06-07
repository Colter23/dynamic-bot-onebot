package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.plugin.MessageSinkRouteState
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
                BotConfig(connection.url, connection.accessToken).apply {
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
                name = connection.name,
                client = client,
            )
            connectionRef[0] = runtimeConnection
            connections += runtimeConnection
        }
    }

    override suspend fun availableAccounts(): List<OneBotRuntimeAccount> = withContext(Dispatchers.IO) {
        connections
            .mapNotNull { connection -> connection.cachedOrRefreshRuntimeAccount() }
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

    override suspend fun sendPrivateForwardMessage(
        accountId: String,
        userId: Long,
        messages: List<Map<String, Any>>,
    ): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendPrivateForwardMsgRaw(userId, messages)
            action.requireSendAccepted("send_private_forward_msg", userId)
        }
    }

    override suspend fun sendGroupForwardMessage(
        accountId: String,
        groupId: Long,
        messages: List<Map<String, Any>>,
    ): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendGroupForwardMsgRaw(groupId, messages)
            action.requireSendAccepted("send_group_forward_msg", groupId)
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
                    logger.warn(it) { "OneBot 正向连接关闭失败：connectionId=${connection.connectionId}，name=${connection.name.ifBlank { "-" }}，url=${connection.url}" }
                }
            }
        }
    }

    private fun requireBot(accountId: String): Bot {
        return connections.firstNotNullOfOrNull { connection ->
            connection.client
                .takeIf { connection.knownAccountId() == accountId && it.isOpenForAction() }
                ?.bot
        } ?: error("OneBot 正向连接尚未就绪：accountId=$accountId")
    }

    private fun ForwardConnection.knownAccountId(): String? {
        return account?.accountId ?: client.runtimeAccountId()
    }

    private fun ForwardConnection.cachedOrRefreshRuntimeAccount(): OneBotRuntimeAccount? {
        val current = account
        if (!client.isOpenForAction()) {
            markUnavailable(IllegalStateException("OneBot 正向连接未连接"), warnWhenAccountUnknown = false)
            return account
        }
        if (current != null && current.state == MessageSinkRouteState.READY) {
            return current
        }
        return refreshRuntimeAccount()
    }

    private fun ForwardConnection.refreshRuntimeAccount(): OneBotRuntimeAccount? {
        return runCatching {
            val bot = client.bot ?: error("OneBot Bot 尚未初始化")
            val info = bot.getLoginInfo().requireDataOk("get_login_info")
            val accountId = info.userId.takeIf { it > 0 }?.toString()
                ?: error("OneBot 登录信息缺少有效 user_id")
            OneBotRuntimeAccount(
                accountId = accountId,
                name = info.nickname?.takeIf { it.isNotBlank() } ?: "QQ机器人 $accountId",
                state = MessageSinkRouteState.READY,
            )
        }.onSuccess {
            val previous = account
            account = it
            if (previous?.accountId != it.accountId) {
                logger.info { "OneBot 正向连接账号已识别：connectionId=$connectionId，name=${name.ifBlank { "-" }}，accountId=${it.accountId}，accountName=${it.name}" }
            } else if (previous.state != MessageSinkRouteState.READY) {
                logger.info { "OneBot 正向连接账号已恢复：connectionId=$connectionId，accountId=${it.accountId}，accountName=${it.name}" }
            }
        }.onFailure {
            markUnavailable(it)
        }.getOrNull() ?: account
    }

    private fun ForwardConnection.markUnavailable(error: Throwable, warnWhenAccountUnknown: Boolean = true) {
        val accountId = account?.accountId ?: client.runtimeAccountId()
        if (accountId == null) {
            logAccountIdentifyFailure(error, warnWhenAccountUnknown)
            return
        }

        val previous = account
        account = (previous ?: OneBotRuntimeAccount(accountId = accountId)).copy(
            state = MessageSinkRouteState.UNAVAILABLE,
        )
        if (previous?.state != MessageSinkRouteState.UNAVAILABLE) {
            logger.warn(error) {
                "OneBot 正向连接账号不可用：connectionId=$connectionId，name=${name.ifBlank { "-" }}，accountId=$accountId，url=$url"
            }
        } else {
            logger.debug(error) {
                "OneBot 正向连接账号仍不可用：connectionId=$connectionId，accountId=$accountId"
            }
        }
    }

    private fun ForwardConnection.logAccountIdentifyFailure(error: Throwable, warn: Boolean) {
        val now = System.currentTimeMillis()
        val failureKey = error.message ?: error::class.qualifiedName ?: error::class.simpleName ?: "unknown"
        val shouldWarn = warn && (lastIdentifyFailureKey != failureKey ||
            now - lastIdentifyFailureLogAt >= IDENTIFY_FAILURE_WARN_INTERVAL_MS)
        lastIdentifyFailureKey = failureKey
        lastIdentifyFailureLogAt = now
        if (shouldWarn) {
            logger.warn(error) {
                "OneBot 正向连接账号识别失败：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url"
            }
        } else {
            logger.debug(error) {
                "OneBot 正向连接账号暂未识别：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url"
            }
        }
    }

    private fun OneBotClient.runtimeAccountId(): String? {
        return bot?.selfId?.takeIf { it > 0 }?.toString()
    }

    private fun OneBotClient.isOpenForAction(): Boolean {
        return runCatching { ws?.isOpen == true }.getOrDefault(false)
    }

    private data class ForwardConnection(
        val connectionId: String,
        val url: String,
        val name: String,
        val client: OneBotClient,
        @Volatile
        var account: OneBotRuntimeAccount? = null,
        @Volatile
        var lastIdentifyFailureLogAt: Long = 0L,
        @Volatile
        var lastIdentifyFailureKey: String? = null,
    )

    private companion object {
        private const val IDENTIFY_FAILURE_WARN_INTERVAL_MS: Long = 5 * 60 * 1000L
    }
}
