package top.colter.dynamic.onebot

import java.util.UUID
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.CommandEvent

public object OneBotCommandMapper {
    public fun toCommandEvent(
        sourcePlugin: String,
        incoming: OneBotIncomingMessage,
    ): CommandEvent? {
        if (incoming.text.isBlank()) return null

        val targetKind = when (incoming.chatType) {
            OneBotChatType.GROUP -> TargetKind.GROUP
            OneBotChatType.PRIVATE -> TargetKind.USER
        }

        return CommandEvent(
            sourcePlugin = sourcePlugin,
            context = CommandContext.of(
                platform = "onebot",
                kind = targetKind,
                externalId = incoming.chatId,
                senderId = incoming.senderId,
            ),
            rawText = incoming.text,
            traceId = UUID.randomUUID().toString(),
        )
    }
}
