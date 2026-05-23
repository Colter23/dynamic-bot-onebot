package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneBotMessageFormatterTest {

    @Test
    fun `route group message`() {
        val route = TargetRoute.fromSubscriber(demoSubscriber(type = SubscriberType.GROUP, userId = "123456"))
        assertEquals(TargetRoute.Group(123456), route)
    }

    @Test
    fun `route user message`() {
        val route = TargetRoute.fromSubscriber(demoSubscriber(type = SubscriberType.USER, userId = "654321"))
        assertEquals(TargetRoute.User(654321), route)
    }

    @Test
    fun `unsupported route for invalid target`() {
        val route = TargetRoute.fromSubscriber(demoSubscriber(type = SubscriberType.GROUP, userId = ""))
        assertTrue(route is TargetRoute.Unsupported)
    }

    @Test
    fun `format with image prefers first image`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(text = "", image = top.colter.dynamic.core.data.LazyImage("https://example.com/a.png")),
                MessageContent.Text("≤‚ ‘∂ØÃ¨"),
            )
        )
        val rendered = OneBotMessageFormatter.format(message)
        assertTrue(rendered.startsWith("[CQ:image,file=https://example.com/a.png]"))
        assertTrue(rendered.contains("≤‚ ‘∂ØÃ¨"))
    }

    @Test
    fun `format without image uses text`() {
        val message = demoMessage(listOf(MessageContent.Text("Demo UP"), MessageContent.Text("≤‚ ‘∂ØÃ¨")))
        val rendered = OneBotMessageFormatter.format(message)
        assertTrue(!rendered.contains("[CQ:image"))
        assertTrue(rendered.contains("Demo UP"))
        assertTrue(rendered.contains("≤‚ ‘∂ØÃ¨"))
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
