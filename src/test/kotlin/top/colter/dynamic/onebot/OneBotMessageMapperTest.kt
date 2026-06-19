package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.enums.MsgType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.ForwardNode
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

class OneBotMessageMapperTest {

    @Test
    fun `route group message`() {
        val route = OneBotTarget.fromAddress(demoTarget(kind = TargetKind.GROUP, externalId = "123456"))
        assertEquals(OneBotTarget.Group(123456), route)
    }

    @Test
    fun `route user message`() {
        val route = OneBotTarget.fromAddress(demoTarget(kind = TargetKind.USER, externalId = "654321"))
        assertEquals(OneBotTarget.User(654321), route)
    }

    @Test
    fun `unsupported route for invalid target`() {
        val route = OneBotTarget.fromAddress(demoTarget(kind = TargetKind.GROUP, externalId = "bad"))
        assertTrue(route is OneBotTarget.Unsupported)
        assertEquals("OneBot 目标 ID 必须是数字：bad", route.reason)
    }

    @Test
    fun `unsupported route for channel target`() {
        val route = OneBotTarget.fromAddress(demoTarget(kind = TargetKind.CHANNEL, externalId = "123456"))
        assertTrue(route is OneBotTarget.Unsupported)
        assertEquals("OneBot 不支持目标类型：CHANNEL", route.reason)
    }

    @Test
    fun `format image and text as array message`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(fallbackText = "", image = MediaRef("https://example.com/a.png", MediaKind.IMAGE)),
                MessageContent.Text("hello"),
            ),
        )

        val segments = OneBotMessageMapper.toArrayMessage(message)

        assertEquals(MsgType.image, segments[0].type)
        assertEquals("https://example.com/a.png", segments[0].data["file"])
        assertEquals(MsgType.text, segments[1].type)
        assertEquals("hello", segments[1].data["text"])
    }

    @Test
    fun `format at and at all as onebot at segments`() {
        val message = demoMessage(
            listOf(
                MessageContent.Mention(
                    fallbackText = "hi ",
                    target = demoTarget(kind = TargetKind.USER, externalId = "10001"),
                ),
                MessageContent.MentionAll(fallbackText = ""),
            ),
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
    fun `format empty batch as non empty text message`() {
        val segments = OneBotMessageMapper.toArrayMessage(listOf(MessageBatch(emptyList())))

        assertEquals(1, segments.size)
        assertEquals(MsgType.text, segments.single().type)
        assertEquals("（空消息）", segments.single().data["text"])
    }

    @Test
    fun `format command result with reply target`() {
        val units = OneBotMessageMapper.toSendUnits(
            batches = listOf(MessageBatch(listOf(MessageContent.Text("pong")))),
            replyToMessageId = "message-1",
        )

        val message = assertIs<OneBotSendUnit.Normal>(units.single()).message
        assertEquals("reply", message[0].asJsonObject["type"].asString)
        assertEquals("message-1", message[0].asJsonObject["data"].asJsonObject["id"].asString)
        assertEquals("text", message[1].asJsonObject["type"].asString)
        assertEquals("pong", message[1].asJsonObject["data"].asJsonObject["text"].asString)
    }

    @Test
    fun `format command result should not duplicate existing reply segment`() {
        val units = OneBotMessageMapper.toSendUnits(
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Reply(fallbackText = "", messageId = "explicit-reply"),
                        MessageContent.Text("pong"),
                    ),
                ),
            ),
            replyToMessageId = "message-1",
        )

        val message = assertIs<OneBotSendUnit.Normal>(units.single()).message
        assertEquals(2, message.size())
        assertEquals("reply", message[0].asJsonObject["type"].asString)
        assertEquals("explicit-reply", message[0].asJsonObject["data"].asJsonObject["id"].asString)
    }

    @Test
    fun `format multiple batches as separate array messages`() {
        val messages = OneBotMessageMapper.toArrayMessages(
            listOf(
                MessageBatch(listOf(MessageContent.Text("first"))),
                MessageBatch(listOf(MessageContent.Text("second"))),
            ),
        )

        assertEquals(2, messages.size)
        assertEquals("first", messages[0].single().data["text"])
        assertEquals("second", messages[1].single().data["text"])
    }

    @Test
    fun `format json array message for onebot json overload`() {
        val message = demoMessage(
            listOf(
                MessageContent.Image(fallbackText = "", image = MediaRef("file:///tmp/draw.png", MediaKind.IMAGE)),
                MessageContent.Text("hello"),
            ),
        )

        val payload = OneBotMessageMapper.toJsonArrayMessage(message)

        assertEquals(2, payload.size())
        assertEquals("image", payload[0].asJsonObject["type"].asString)
        assertEquals("file:///tmp/draw.png", payload[0].asJsonObject["data"].asJsonObject["file"].asString)
        assertEquals("text", payload[1].asJsonObject["type"].asString)
        assertEquals("hello", payload[1].asJsonObject["data"].asJsonObject["text"].asString)
    }

    @Test
    fun `format local image as file uri`() {
        val image = Files.createTempFile("onebot-image", ".png")
        try {
            Files.write(image, byteArrayOf(1, 2, 3))
            val message = demoMessage(
                listOf(
                    MessageContent.Image(
                        fallbackText = "",
                        image = MediaRef(image.toString(), MediaKind.IMAGE),
                    ),
                ),
            )

            val payload = OneBotMessageMapper.toJsonArrayMessage(message)

            assertEquals(image.toUri().toString(), payload[0].asJsonObject["data"].asJsonObject["file"].asString)
        } finally {
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun `keep preencoded base64 image uri`() {
        val image = Files.createTempFile("onebot-image-large", ".png")
        try {
            Files.write(image, byteArrayOf(1, 2, 3))
            val message = demoMessage(
                listOf(
                    MessageContent.Image(
                        fallbackText = "",
                        image = MediaRef("base64://AQID", MediaKind.IMAGE),
                    ),
                ),
            )

            val payload = OneBotMessageMapper.toJsonArrayMessage(message)

            assertEquals("base64://AQID", payload[0].asJsonObject["data"].asJsonObject["file"].asString)
        } finally {
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun `format mixed normal and merged forward send units`() {
        val message = Message(
            id = "forward-1",
            time = 1,
            targets = listOf(demoTarget(TargetKind.GROUP, "123456")),
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Text("before"),
                        MessageContent.Forward(
                            fallbackText = "[合并转发] Demo UP",
                            title = "原始内容",
                            summary = "共 1 条内容",
                            sourceName = "Demo UP",
                            nodes = listOf(
                                ForwardNode(
                                    senderId = "123",
                                    senderName = "Demo UP",
                                    time = 1_710_000_000,
                                    batches = listOf(
                                        MessageBatch(
                                            listOf(
                                                MessageContent.Image(
                                                    fallbackText = "",
                                                    image = MediaRef("https://example.com/a.png", MediaKind.IMAGE),
                                                ),
                                                MessageContent.Video(
                                                    fallbackText = "",
                                                    video = MediaRef("file:///tmp/video.mp4", MediaKind.VIDEO),
                                                ),
                                                MessageContent.Text("node text"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        MessageContent.Text("after"),
                    ),
                ),
            ),
        )

        val units = OneBotMessageMapper.toSendUnits(message, forwardSenderUin = "99887766")

        assertEquals(3, units.size)
        assertEquals("before", assertIs<OneBotSendUnit.Normal>(units[0]).message[0].asJsonObject["data"].asJsonObject["text"].asString)
        val forward = assertIs<OneBotSendUnit.Forward>(units[1])
        val node = forward.messages.single()
        assertEquals("node", node["type"])
        val data = assertIs<Map<*, *>>(node["data"])
        assertEquals("Demo UP", data["name"])
        assertEquals("99887766", data["uin"])
        assertEquals(1_710_000_000L, data["time"])
        val content = assertIs<com.google.gson.JsonArray>(data["content"])
        assertEquals("image", content[0].asJsonObject["type"].asString)
        assertEquals("https://example.com/a.png", content[0].asJsonObject["data"].asJsonObject["file"].asString)
        assertEquals("video", content[1].asJsonObject["type"].asString)
        assertEquals("file:///tmp/video.mp4", content[1].asJsonObject["data"].asJsonObject["file"].asString)
        assertEquals("node text", content[2].asJsonObject["data"].asJsonObject["text"].asString)
        assertEquals("after", assertIs<OneBotSendUnit.Normal>(units[2]).message[0].asJsonObject["data"].asJsonObject["text"].asString)
    }

    @Test
    fun `use sender account id as merged forward uin when provided`() {
        val message = Message(
            id = "forward-large-uid",
            time = 1,
            targets = listOf(demoTarget(TargetKind.GROUP, "123456")),
            batches = listOf(
                MessageBatch(
                    listOf(
                        MessageContent.Forward(
                            fallbackText = "[合并转发] Long UID",
                            title = "原始内容",
                            summary = "共 1 条内容",
                            sourceName = "Long UID",
                            nodes = listOf(
                                ForwardNode(
                                    senderId = "3546838751775383",
                                    senderName = "Long UID",
                                    time = 1_710_000_000,
                                    batches = listOf(MessageBatch(listOf(MessageContent.Text("node text")))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val forward = assertIs<OneBotSendUnit.Forward>(OneBotMessageMapper.toSendUnits(message, forwardSenderUin = "123456789").single())
        val data = assertIs<Map<*, *>>(forward.messages.single()["data"])
        assertEquals("123456789", data["uin"])
    }

    private fun demoMessage(contents: List<MessageContent>): Message {
        return Message(
            id = "1",
            time = 1710000000,
            targets = listOf(demoTarget(TargetKind.GROUP, "123456")),
            batches = listOf(MessageBatch(content = contents)),
        )
    }

    private fun demoTarget(kind: TargetKind, externalId: String): TargetAddress {
        return TargetAddress.of(
            platformId = "qq",
            kind = kind,
            externalId = externalId,
        )
    }
}
