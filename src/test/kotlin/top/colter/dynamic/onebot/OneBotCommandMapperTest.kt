package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.ChatType

class OneBotCommandMapperTest {

    @Test
    fun `should map group command input`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = "onebot-subscriber",
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = "12345",
                senderId = "67890",
                text = "/db status",
            ),
        )

        requireNotNull(event)
        assertEquals("onebot-subscriber", event.sourcePlugin)
        assertEquals("onebot", event.context.platform)
        assertEquals(ChatType.GROUP, event.context.chatType)
        assertEquals("12345", event.context.chatId)
        assertEquals("67890", event.context.senderId)
        assertEquals("/db status", event.rawText)
    }

    @Test
    fun `should pass non command text to central command parser`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = "onebot-subscriber",
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "hello",
            ),
        )

        requireNotNull(event)
        assertEquals(ChatType.PRIVATE, event.context.chatType)
        assertEquals("hello", event.rawText)
    }

    @Test
    fun `should ignore blank text`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = "onebot-subscriber",
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "   ",
            ),
        )

        assertNull(event)
    }
}
