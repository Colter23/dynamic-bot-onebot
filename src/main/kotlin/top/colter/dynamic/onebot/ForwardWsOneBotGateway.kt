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

    private var client: OneBotClient? = null

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (client != null) return

        val botConfig = BotConfig(config.url, config.accessToken).apply {
            isReconnect = config.reconnect
            reconnectInterval = config.reconnectIntervalSeconds
            reconnectMaxTimes = config.reconnectMaxTimes
            if (config.botId > 0) {
                botId = config.botId
            }
        }
        client = OneBotClient.create(botConfig, OneBotIncomingListener(onIncomingMessage)).open()
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

    override suspend fun listGroups(): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot().getGroupList()
            action.requireOk("get_group_list")
            action?.data.orEmpty().map { group ->
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
            action.requireOk("get_friend_list")
            action?.data.orEmpty().map { friend ->
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
        client?.close()
        client = null
    }

    private fun requireBot(): Bot {
        return client?.bot ?: error("onebot_forward_ws_not_connected")
    }
}
