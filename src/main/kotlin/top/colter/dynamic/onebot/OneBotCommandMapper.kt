package top.colter.dynamic.onebot

import java.util.UUID
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.event.CommandEvent

public object OneBotCommandMapper {
    public fun toCommandEvent(
        sourcePlugin: String,
        incoming: OneBotIncomingMessage,
    ): CommandEvent? {
        if (incoming.text.isBlank()) return null

        val chatType = when (incoming.chatType) {
            OneBotChatType.GROUP -> ChatType.GROUP
            OneBotChatType.PRIVATE -> ChatType.PRIVATE
        }

        return CommandEvent(
            sourcePlugin = sourcePlugin,
            context = CommandContext(
                platform = "onebot",
                chatType = chatType,
                chatId = incoming.chatId,
                senderId = incoming.senderId,
            ),
            rawText = incoming.text,
            traceId = UUID.randomUUID().toString(),
        )
    }
}
