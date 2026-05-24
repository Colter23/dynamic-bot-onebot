package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.enums.MsgType
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneBotMessageMapperTest {

    @Test
    fun `route group message`() {
        val route = OneBotTarget.fromSubscriber(demoSubscriber(type = SubscriberType.GROUP, userId = "123456"))
        assertEquals(OneBotTarget.Group(123456), route)
    }

    @Test
    fun `route user message`() {
        val route = OneBotTarget.fromSubscriber(demoSubscriber(type = SubscriberType.USER, userId = "654321"))
        assertEquals(OneBotTarget.User(654321), route)
    }

    @Test
    fun `unsupported route for invalid target`() {
        val route = OneBotTarget.fromSubscriber(demoSubscriber(type = SubscriberType.GROUP, userId = ""))
        assertTrue(route is OneBotTarget.Unsupported)
    }

    @Test
    fun `unsupported route for channel target`() {
        val route = OneBotTarget.fromSubscriber(demoSubscriber(type = SubscriberType.CHANNEL, userId = "123456"))
        assertTrue(route is OneBotTarget.Unsupported)
    }

    @Test
    fun `format image and text as array message`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(text = "", image = LazyImage("https://example.com/a.png")),
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
                MessageContent.At(text = "hi ", targetId = "10001"),
                MessageContent.AtAll(text = ""),
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

    private fun demoMessage(contents: List<MessageContent>): Message {
        return Message(
            id = 1,
            time = 1710000000,
            subscriber = listOf(demoSubscriber(SubscriberType.GROUP, "123456")),
            chain = listOf(MessageChain(content = contents)),
        )
    }

    private fun demoSubscriber(type: SubscriberType, userId: String): Subscriber {
        return Subscriber(
            id = 1,
            platform = "onebot",
            type = type,
            userId = userId,
            name = "demo",
            state = 1,
            createTime = 0,
            createUser = 0,
        )
    }
}
