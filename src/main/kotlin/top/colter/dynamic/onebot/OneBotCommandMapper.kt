package top.colter.dynamic.onebot

import java.util.UUID
import top.colter.dynamic.core.command.CommandPublishRequest
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.TargetKind

public object OneBotCommandMapper {
    public fun toCommandRequest(
        sourcePlugin: String,
        incoming: OneBotIncomingMessage,
    ): CommandPublishRequest? {
        if (incoming.text.isBlank()) return null

        val targetKind = when (incoming.chatType) {
            OneBotChatType.GROUP -> TargetKind.GROUP
            OneBotChatType.PRIVATE -> TargetKind.USER
        }

        return CommandPublishRequest(
            sourcePlugin = sourcePlugin,
            context = CommandContext.of(
                platform = "onebot",
                kind = targetKind,
                externalId = incoming.chatId,
                senderId = incoming.senderId,
                botAccountId = incoming.botAccountId,
                mentionedAccountIds = incoming.mentionedAccountIds,
            ),
            rawText = incoming.text,
            traceId = UUID.randomUUID().toString(),
        )
    }
}
