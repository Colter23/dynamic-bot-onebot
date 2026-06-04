package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.TargetKind

class OneBotCommandMapperTest {

    @Test
    fun `should map group command input`() {
        val request = OneBotCommandMapper.toCommandRequest(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = "12345",
                senderId = "67890",
                text = "/db status",
                botAccountId = "42",
                mentionedAccountIds = setOf("42"),
            ),
        )

        requireNotNull(request)
        assertEquals(ONEBOT_PLUGIN_ID, request.sourcePlugin)
        assertEquals("qq", request.context.platform)
        assertEquals(TargetKind.GROUP, request.context.chatType)
        assertEquals("12345", request.context.chatId)
        assertEquals("67890", request.context.senderId)
        assertEquals("42", request.context.botAccountId)
        assertEquals(setOf("42"), request.context.mentionedAccountIds)
        assertEquals("/db status", request.rawText)
    }

    @Test
    fun `should pass non command text to central command parser`() {
        val request = OneBotCommandMapper.toCommandRequest(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "hello",
            ),
        )

        requireNotNull(request)
        assertEquals(TargetKind.USER, request.context.chatType)
        assertEquals("hello", request.rawText)
    }

    @Test
    fun `should ignore blank text`() {
        val request = OneBotCommandMapper.toCommandRequest(
            sourcePlugin = ONEBOT_PLUGIN_ID,
            incoming = OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = "11",
                senderId = "22",
                text = "   ",
            ),
        )

        assertNull(request)
    }
}
