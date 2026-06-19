package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetKind

class OneBotCommandMapperTest {

    @Test
    fun `should map group incoming message`() {
        val message = OneBotCommandMapper.toIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = "12345",
                senderId = "67890",
                text = "/db status",
                botAccountId = "42",
                mentionedAccountIds = setOf("42"),
            ),
        )

        assertEquals("qq", message.platformId.value)
        assertEquals(TargetKind.GROUP, message.target.kind)
        assertEquals("12345", message.target.externalId)
        assertEquals("42", message.target.accountId)
        assertEquals("67890", message.senderId)
        assertEquals("42", message.botAccountId)
        assertEquals(setOf("42"), message.mentions)
        assertEquals("/db status", message.text)
    }

    @Test
    fun `should map private incoming message`() {
        val message = OneBotCommandMapper.toIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "hello",
            ),
        )

        assertEquals(TargetKind.USER, message.target.kind)
        assertEquals("hello", message.text)
    }

    @Test
    fun `should lift reply segment to incoming message reference`() {
        val message = OneBotCommandMapper.toIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = "12345",
                senderId = "67890",
                text = "下载原图",
                messageId = "200",
                segments = listOf(
                    IncomingMessageSegment.Reply(
                        messageId = "100",
                        rawPayload = """{"type":"reply","data":{"id":"100"}}""",
                    ),
                    IncomingMessageSegment.Text("下载原图"),
                ),
            ),
        )

        assertEquals("100", message.replyTo?.messageId)
        assertEquals("""{"type":"reply","data":{"id":"100"}}""", message.replyTo?.rawPayload)
    }
}
