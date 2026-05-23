package top.colter.dynamic.onebot

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
    public suspend fun sendPrivateMessage(userId: Long, message: String)
    public suspend fun sendGroupMessage(groupId: Long, message: String)
    public suspend fun close()
}

public class NoopOneBotGateway : OneBotGateway {
    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
    }

    override suspend fun sendPrivateMessage(userId: Long, message: String) {
    }

    override suspend fun sendGroupMessage(groupId: Long, message: String) {
    }

    override suspend fun close() {
    }
}
