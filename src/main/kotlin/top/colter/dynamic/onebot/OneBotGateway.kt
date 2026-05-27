package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import com.google.gson.JsonArray

internal const val ONEBOT_PLUGIN_ID: String = "onebot-gateway"

public data class OneBotIncomingMessage(
    val chatType: OneBotChatType,
    val chatId: String,
    val senderId: String,
    val text: String,
)

public enum class OneBotChatType {
    GROUP,
    PRIVATE,
}

public interface OneBotGateway {
    public fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit)
    public suspend fun sendPrivateMessage(userId: Long, message: JsonArray)
    public suspend fun sendGroupMessage(groupId: Long, message: JsonArray)
    public suspend fun close()
}

public class NoopOneBotGateway : OneBotGateway {
    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
    }

    override suspend fun sendPrivateMessage(userId: Long, message: JsonArray) {
    }

    override suspend fun sendGroupMessage(groupId: Long, message: JsonArray) {
    }

    override suspend fun close() {
    }
}

internal object OneBotGatewayFactory {
    fun create(config: OneBotConfig): OneBotGateway {
        return when (config.mode) {
            OneBotConnectionMode.FORWARD_WS -> ForwardWsOneBotGateway(config)
            OneBotConnectionMode.REVERSE_WS -> ReverseWsOneBotGateway(config)
        }
    }
}

internal fun ActionData<*>.requireOk(action: String, targetId: Long) {
    if (!status.equals("ok", ignoreCase = true)) {
        error("action=$action targetId=$targetId status=$status retCode=$retCode")
    }
}
