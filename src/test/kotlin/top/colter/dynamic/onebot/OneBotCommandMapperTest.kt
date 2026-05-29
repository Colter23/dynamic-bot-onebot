package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.TargetKind

class OneBotCommandMapperTest {

    @Test
    fun `should map group command input`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = "12345",
                senderId = "67890",
                text = "/db status",
            ),
        )

        requireNotNull(event)
        assertEquals(ONEBOT_PLUGIN_ID, event.sourcePlugin)
        assertEquals("onebot", event.context.platform)
        assertEquals(TargetKind.GROUP, event.context.chatType)
        assertEquals("12345", event.context.chatId)
        assertEquals("67890", event.context.senderId)
        assertEquals("/db status", event.rawText)
    }

    @Test
    fun `should pass non command text to central command parser`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "hello",
            ),
        )

        requireNotNull(event)
        assertEquals(TargetKind.USER, event.context.chatType)
        assertEquals("hello", event.rawText)
    }

    @Test
    fun `should ignore blank text`() {
        val event = OneBotCommandMapper.toCommandEvent(
            sourcePlugin = ONEBOT_PLUGIN_ID,
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
