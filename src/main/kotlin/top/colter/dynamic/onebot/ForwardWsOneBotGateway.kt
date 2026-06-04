package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ForwardWsOneBotGateway(
    private val config: OneBotConfig,
) : OneBotGateway {

    private val connections: MutableMap<String, AccountConnection> = linkedMapOf()

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (connections.isNotEmpty()) return

        config.enabledAccounts().forEach { account ->
            val client = OneBotClient.create(
                BotConfig(account.url, config.accessToken).apply {
                    isReconnect = config.reconnect
                    reconnectInterval = config.reconnectIntervalSeconds
                    reconnectMaxTimes = config.reconnectMaxTimes
                    account.accountId.toLongOrNull()?.let { botId = it }
                },
                OneBotIncomingListener(
                    onIncomingMessage = onIncomingMessage,
                    botAccountIdProvider = { runtimeBotAccountId(account.accountId) },
                ),
            ).open()
            connections[account.accountId] = AccountConnection(account, client)
        }
    }

    override fun availableAccountIds(): Set<String> = connections.keys

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
        val active = connections.values.toList()
        connections.clear()
        active.forEach { connection ->
            withContext(Dispatchers.IO) {
                connection.client.close()
            }
        }
    }

    private fun requireBot(accountId: String): Bot {
        return connections[accountId]?.client?.bot
            ?: error("OneBot 正向连接尚未就绪：accountId=$accountId")
    }

    private fun runtimeBotAccountId(accountId: String): String {
        return connections[accountId]?.client?.bot?.selfId?.takeIf { it > 0 }?.toString()
            ?: accountId
    }

    private data class AccountConnection(
        val account: OneBotAccountConfig,
        val client: OneBotClient,
    )
}
