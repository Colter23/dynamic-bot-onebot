package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageReference
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

public object OneBotCommandMapper {
    private val QQ_PLATFORM_ID: PlatformId = PlatformId.of("qq")

    public fun toIncomingMessage(incoming: OneBotIncomingMessage): IncomingMessage {
        val targetKind = incoming.targetKind()
        return IncomingMessage(
            platformId = QQ_PLATFORM_ID,
            target = TargetAddress.of(
                platformId = QQ_PLATFORM_ID.value,
                kind = targetKind,
                externalId = incoming.chatId,
                accountId = incoming.botAccountId,
            ),
            senderId = incoming.senderId,
            botAccountId = incoming.botAccountId,
            messageId = incoming.messageId,
            replyTo = incoming.replyToReference(),
            timestamp = incoming.timestamp,
            text = incoming.text,
            segments = incoming.segments,
            rawFormat = incoming.rawFormat,
            rawPayload = incoming.rawPayload,
            mentions = incoming.mentionedAccountIds,
        )
    }

    private fun OneBotIncomingMessage.targetKind(): TargetKind {
        return when (chatType) {
            OneBotChatType.GROUP -> TargetKind.GROUP
            OneBotChatType.PRIVATE -> TargetKind.USER
        }
    }

    private fun OneBotIncomingMessage.replyToReference(): IncomingMessageReference? {
        return segments
            .asSequence()
            .filterIsInstance<IncomingMessageSegment.Reply>()
            .mapNotNull { segment ->
                segment.messageId.trim().takeIf { it.isNotBlank() }?.let { messageId ->
                    IncomingMessageReference(
                        messageId = messageId,
                        rawPayload = segment.rawPayload,
                    )
                }
            }
            .firstOrNull()
    }
}
