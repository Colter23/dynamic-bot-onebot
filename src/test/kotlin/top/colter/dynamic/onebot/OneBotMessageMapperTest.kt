package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.enums.MsgType
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.MessageTarget
import top.colter.dynamic.core.data.SubscriberType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneBotMessageMapperTest {

    @Test
    fun `route group message`() {
        val route = OneBotTarget.fromMessageTarget(demoTarget(type = SubscriberType.GROUP, targetId = "123456"))
        assertEquals(OneBotTarget.Group(123456), route)
    }

    @Test
    fun `route user message`() {
        val route = OneBotTarget.fromMessageTarget(demoTarget(type = SubscriberType.USER, targetId = "654321"))
        assertEquals(OneBotTarget.User(654321), route)
    }

    @Test
    fun `unsupported route for invalid target`() {
        val route = OneBotTarget.fromMessageTarget(demoTarget(type = SubscriberType.GROUP, targetId = ""))
        assertTrue(route is OneBotTarget.Unsupported)
    }

    @Test
    fun `unsupported route for channel target`() {
        val route = OneBotTarget.fromMessageTarget(demoTarget(type = SubscriberType.CHANNEL, targetId = "123456"))
        assertTrue(route is OneBotTarget.Unsupported)
    }

    @Test
    fun `format image and text as array message`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(fallbackText = "", image = LazyImage("https://example.com/a.png")),
                MessageContent.Text("测试动态"),
            )
        )

        val segments = OneBotMessageMapper.toArrayMessage(message)

        assertEquals(MsgType.image, segments[0].type)
        assertEquals("https://example.com/a.png", segments[0].data["file"])
        assertEquals(MsgType.text, segments[1].type)
        assertEquals("测试动态", segments[1].data["text"])
    }

    @Test
    fun `format at and at all as onebot at segments`() {
        val message = demoMessage(
            listOf(
                MessageContent.Mention(fallbackText = "hi ", targetId = "10001"),
                MessageContent.MentionAll(fallbackText = ""),
            )
        )

        val segments = OneBotMessageMapper.toArrayMessage(message)

        assertEquals(MsgType.text, segments[0].type)
        assertEquals("hi ", segments[0].data["text"])
        assertEquals(MsgType.at, segments[1].type)
        assertEquals("10001", segments[1].data["qq"])
        assertEquals(MsgType.at, segments[2].type)
        assertEquals("all", segments[2].data["qq"])
    }

    @Test
    fun `format empty chain as non empty text message`() {
        val segments = OneBotMessageMapper.toArrayMessage(listOf(MessageChain(emptyList())))

        assertEquals(1, segments.size)
        assertEquals(MsgType.text, segments.single().type)
        assertEquals("(empty)", segments.single().data["text"])
    }

    @Test
    fun `format multiple chains as separate array messages`() {
        val messages = OneBotMessageMapper.toArrayMessages(
            listOf(
                MessageChain(listOf(MessageContent.Text("first"))),
                MessageChain(listOf(MessageContent.Text("second"))),
            )
        )

        assertEquals(2, messages.size)
        assertEquals("first", messages[0].single().data["text"])
        assertEquals("second", messages[1].single().data["text"])
    }

    @Test
    fun `format json array message for onebot json overload`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(fallbackText = "", image = LazyImage("file:///tmp/draw.png")),
                MessageContent.Text("hello"),
            )
        )

        val payload = OneBotMessageMapper.toJsonArrayMessage(message)

        assertEquals(2, payload.size())
        assertEquals("image", payload[0].asJsonObject["type"].asString)
        assertEquals("file:///tmp/draw.png", payload[0].asJsonObject["data"].asJsonObject["file"].asString)
        assertEquals("text", payload[1].asJsonObject["type"].asString)
        assertEquals("hello", payload[1].asJsonObject["data"].asJsonObject["text"].asString)
    }

    private fun demoMessage(contents: List<MessageContent>): Message {
        return Message(
            id = 1,
            time = 1710000000,
            targets = listOf(demoTarget(SubscriberType.GROUP, "123456")),
            chain = listOf(MessageChain(content = contents)),
        )
    }

    private fun demoTarget(type: SubscriberType, targetId: String): MessageTarget {
        return MessageTarget(
            platformId = "onebot",
            type = type,
            targetId = targetId,
        )
    }
}
