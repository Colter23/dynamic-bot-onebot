package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import cn.evole.onebot.sdk.action.misc.ActionData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class OneBotClientGateway(
    private val config: OneBotConfig,
) : OneBotGateway {

    private var client: OneBotClient? = null

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (client != null) return

        val botConfig = BotConfig(config.url, config.accessToken).apply {
            isReconnect = config.reconnect
            reconnectInterval = config.reconnectInterval
            reconnectMaxTimes = config.retryTimes
            if (config.botId > 0) {
                botId = config.botId
            }
        }
        val listener = OneBotIncomingListener(onIncomingMessage)
        client = OneBotClient.create(botConfig, listener).open()
    }

    override suspend fun sendPrivateMessage(userId: Long, message: String) {
        withContext(Dispatchers.IO) {
            val action = requireBot().sendPrivateMsg(userId, message, false)
            verifyResponse(action, "send_private_msg", userId)
        }
    }

    override suspend fun sendGroupMessage(groupId: Long, message: String) {
        withContext(Dispatchers.IO) {
            val action = requireBot().sendGroupMsg(groupId, message, false)
            verifyResponse(action, "send_group_msg", groupId)
        }
    }

    override suspend fun close() {
        client?.close()
        client = null
    }

    private fun requireBot(): Bot {
        return client?.bot ?: error("onebot_client_not_connected")
    }

    private fun verifyResponse(response: ActionData<*>, action: String, targetId: Long) {
        if (!response.status.equals("ok", ignoreCase = true)) {
            error("action=$action targetId=$targetId status=${response.status} retCode=${response.retCode}")
        }
    }
}
