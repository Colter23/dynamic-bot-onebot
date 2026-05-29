package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.enums.MsgType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    }

    @Test
    fun `unsupported route for channel target`() {
        val route = OneBotTarget.fromAddress(demoTarget(kind = TargetKind.CHANNEL, externalId = "123456"))
        assertTrue(route is OneBotTarget.Unsupported)
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
        assertEquals("(empty)", segments.single().data["text"])
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
    fun `format local image as base64 file for remote onebot`() {
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

            assertEquals("base64://AQID", payload[0].asJsonObject["data"].asJsonObject["file"].asString)
        } finally {
            Files.deleteIfExists(image)
        }
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
            platformId = "onebot",
            kind = kind,
            externalId = externalId,
        )
    }
}
