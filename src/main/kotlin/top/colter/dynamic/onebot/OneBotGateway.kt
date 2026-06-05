package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import cn.evole.onebot.sdk.action.misc.ActionList
import cn.evole.onebot.sdk.action.misc.ActionRaw
import cn.evole.onebot.sdk.entity.MsgId
import com.google.gson.JsonArray
import top.colter.dynamic.core.plugin.MessageSinkRouteState

internal const val ONEBOT_PLUGIN_ID: String = "onebot-gateway"

public data class OneBotIncomingMessage(
    val chatType: OneBotChatType,
    val chatId: String,
    val senderId: String,
    val text: String,
    val botAccountId: String? = null,
    val mentionedAccountIds: Set<String> = emptySet(),
)

public enum class OneBotChatType {
    GROUP,
    PRIVATE,
}

public data class OneBotTargetCandidate(
    val id: String,
    val name: String,
    val accountId: String,
)

public data class OneBotRuntimeAccount(
    val accountId: String,
    val name: String = "QQ机器人 $accountId",
    val state: MessageSinkRouteState = MessageSinkRouteState.READY,
)

public interface OneBotGateway {
    public fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit)
    public suspend fun availableAccounts(): List<OneBotRuntimeAccount>
    public suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String?
    public suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String?
    public suspend fun recallMessage(accountId: String, messageId: String)
    public suspend fun listGroups(accountId: String): List<OneBotTargetCandidate>
    public suspend fun listFriends(accountId: String): List<OneBotTargetCandidate>
    public suspend fun close()
}

public class NoopOneBotGateway : OneBotGateway {
    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
    }

    override suspend fun availableAccounts(): List<OneBotRuntimeAccount> = emptyList()

    override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? = null

    override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? = null

    override suspend fun recallMessage(accountId: String, messageId: String) {
    }

    override suspend fun listGroups(accountId: String): List<OneBotTargetCandidate> = emptyList()

    override suspend fun listFriends(accountId: String): List<OneBotTargetCandidate> = emptyList()

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

internal fun ActionData<*>?.requireSendAccepted(action: String, targetId: Long): String? {
    if (this == null) {
        return null
    }
    if (status.equals("no_response", ignoreCase = true)) {
        return null
    }
    if (!status.equals("ok", ignoreCase = true)) {
        error("OneBot 发送失败：action=$action，targetId=$targetId，status=$status，retCode=$retCode")
    }
    return (data as? MsgId)?.messageId?.toString()
}

internal fun ActionRaw?.requireActionAccepted(action: String) {
    if (this == null) return
    if (status.equals("no_response", ignoreCase = true)) return
    if (!status.equals("ok", ignoreCase = true)) {
        error("OneBot 操作失败：action=$action，status=$status，retCode=$retCode")
    }
}

internal fun <T> ActionList<T>?.requireQueryOk(action: String): List<T> {
    if (this == null) {
        error("OneBot 查询失败：action=$action，原因=未收到响应")
    }
    if (status.equals("no_response", ignoreCase = true)) {
        error("OneBot 查询失败：action=$action，原因=未收到响应")
    }
    if (!status.equals("ok", ignoreCase = true)) {
        error("OneBot 查询失败：action=$action，status=$status，retCode=$retCode")
    }
    return data ?: error("OneBot 查询失败：action=$action，原因=响应数据为空")
}

internal fun <T> ActionData<T>?.requireDataOk(action: String): T {
    if (this == null) {
        error("OneBot 查询失败：action=$action，原因=未收到响应")
    }
    if (status.equals("no_response", ignoreCase = true)) {
        error("OneBot 查询失败：action=$action，原因=未收到响应")
    }
    if (!status.equals("ok", ignoreCase = true)) {
        error("OneBot 查询失败：action=$action，status=$status，retCode=$retCode")
    }
    return data ?: error("OneBot 查询失败：action=$action，原因=响应数据为空")
}
