package top.colter.dynamic.onebot

import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.sdk.action.misc.ActionData
import cn.evole.onebot.sdk.action.misc.ActionList
import cn.evole.onebot.sdk.action.misc.ActionRaw
import cn.evole.onebot.sdk.entity.MsgId
import cn.evole.onebot.sdk.enums.ActionType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.plugin.MessageSinkRouteState

internal const val ONEBOT_PLUGIN_ID: String = "onebot-gateway"

public data class OneBotIncomingMessage(
    val chatType: OneBotChatType,
    val chatId: String,
    val senderId: String,
    val text: String,
    val botAccountId: String? = null,
    val messageId: String = "",
    val timestamp: Long = 0,
    val segments: List<IncomingMessageSegment> = emptyList(),
    val rawFormat: String = "",
    val rawPayload: String = "",
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

public data class OneBotImplementationInfo(
    val appName: String = "",
    val appVersion: String = "",
    val protocolVersion: String = "",
) {
    public val kind: OneBotImplementationKind
        get() = when {
            appName.contains("napcat", ignoreCase = true) -> OneBotImplementationKind.NAPCAT
            appName.contains("llonebot", ignoreCase = true) -> OneBotImplementationKind.LLONEBOT
            appName.contains("llbot", ignoreCase = true) -> OneBotImplementationKind.LLONEBOT
            else -> OneBotImplementationKind.UNKNOWN
        }
}

public enum class OneBotImplementationKind {
    NAPCAT,
    LLONEBOT,
    UNKNOWN,
}

public data class OneBotConnectionHints(
    val sameHostLikely: Boolean = false,
    val signedUrlBaseCandidates: List<String> = emptyList(),
)

public data class OneBotDownloadProbeResult(
    val available: Boolean,
    val reason: String = "",
)

public interface OneBotGateway {
    public fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit)
    public suspend fun availableAccounts(): List<OneBotRuntimeAccount>
    public suspend fun implementationInfo(accountId: String): OneBotImplementationInfo = OneBotImplementationInfo()
    public suspend fun connectionHints(accountId: String, webAdminHost: String, webAdminPort: Int): OneBotConnectionHints =
        OneBotConnectionHints()
    public suspend fun probeDownload(accountId: String, uri: String): OneBotDownloadProbeResult =
        OneBotDownloadProbeResult(available = false, reason = "OneBot 网关不支持媒体探测")
    public suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String?
    public suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String?
    public suspend fun sendPrivateForwardMessage(
        accountId: String,
        userId: Long,
        messages: List<Map<String, Any>>,
    ): String?
    public suspend fun sendGroupForwardMessage(
        accountId: String,
        groupId: Long,
        messages: List<Map<String, Any>>,
    ): String?
    public suspend fun recallMessage(accountId: String, messageId: String)
    public suspend fun listGroups(accountId: String): List<OneBotTargetCandidate>
    public suspend fun listFriends(accountId: String): List<OneBotTargetCandidate>
    public suspend fun close()
}

public class NoopOneBotGateway : OneBotGateway {
    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
    }

    override suspend fun availableAccounts(): List<OneBotRuntimeAccount> = emptyList()

    override suspend fun implementationInfo(accountId: String): OneBotImplementationInfo = OneBotImplementationInfo()

    override suspend fun connectionHints(
        accountId: String,
        webAdminHost: String,
        webAdminPort: Int,
    ): OneBotConnectionHints = OneBotConnectionHints()

    override suspend fun probeDownload(accountId: String, uri: String): OneBotDownloadProbeResult =
        OneBotDownloadProbeResult(available = false, reason = "OneBot 未运行")

    override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? = null

    override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? = null

    override suspend fun sendPrivateForwardMessage(
        accountId: String,
        userId: Long,
        messages: List<Map<String, Any>>,
    ): String? = null

    override suspend fun sendGroupForwardMessage(
        accountId: String,
        groupId: Long,
        messages: List<Map<String, Any>>,
    ): String? = null

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

internal fun parseRecallMessageId(messageId: String): Int {
    val numeric = messageId.trim().toLongOrNull()
        ?: error("OneBot 消息 ID 无效：$messageId")
    if (numeric < Int.MIN_VALUE || numeric > Int.MAX_VALUE) {
        error("OneBot 消息 ID 超出当前客户端库支持范围（int32），无法撤回：$messageId")
    }
    return numeric.toInt()
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
    return data.extractMessageId()
}

internal fun Bot.sendGroupForwardMsgRaw(groupId: Long, messages: List<Map<String, Any>>): ActionData<*>? {
    return customRawRequest(
        ActionType.SEND_GROUP_FORWARD_MSG,
        forwardActionParams("group_id", groupId, messages),
    )
}

internal fun Bot.sendPrivateForwardMsgRaw(userId: Long, messages: List<Map<String, Any>>): ActionData<*>? {
    return customRawRequest(
        ActionType.SEND_PRIVATE_FORWARD_MSG,
        forwardActionParams("user_id", userId, messages),
    )
}

internal fun forwardActionParams(
    targetKey: String,
    targetId: Long,
    messages: List<Map<String, Any>>,
): Map<String, Any> = mapOf(
    targetKey to targetId,
    "messages" to messages,
)

private fun Any?.extractMessageId(): String? {
    return when (this) {
        is MsgId -> messageId.toString()
        is JsonObject -> sequenceOf("message_id", "messageId")
            .mapNotNull { key -> get(key)?.takeIf { !it.isJsonNull } }
            .mapNotNull { element ->
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asLong.toString()
                    element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                        element.asString.trim().takeIf(String::isNotBlank)
                    else -> null
                }
            }
            .firstOrNull()
        is Map<*, *> -> sequenceOf("message_id", "messageId")
            .mapNotNull { key -> this[key] }
            .mapNotNull { value -> value.extractMessageId() ?: value.toMessageIdText() }
            .firstOrNull()
        else -> toMessageIdText()
    }
}

private fun Any?.toMessageIdText(): String? {
    return when (this) {
        is Number -> toLong().toString()
        is String -> trim().takeIf(String::isNotBlank)
        else -> null
    }
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
