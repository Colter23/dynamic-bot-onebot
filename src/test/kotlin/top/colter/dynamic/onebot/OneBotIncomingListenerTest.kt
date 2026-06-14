package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import cn.evole.onebot.sdk.event.message.GroupMessageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.IncomingMessageSegment

class OneBotIncomingListenerTest {

    @Test
    fun `group message should prefer array plain text over raw cq text`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(onIncomingMessage = { captured = it })
        val event = GroupMessageEvent().apply {
            groupId = 12345L
            userId = 67890L
            rawMessage = "[CQ:image,file=demo.png]/db status"
            arrayMsg = listOf(
                segment(MsgType.image, "file" to "demo.png"),
                segment(MsgType.text, "text" to "/db status"),
            )
        }

        listener.onGroupMessage(event)

        val incoming = requireNotNull(captured)
        assertEquals(OneBotChatType.GROUP, incoming.chatType)
        assertEquals("12345", incoming.chatId)
        assertEquals("67890", incoming.senderId)
        assertEquals("/db status", incoming.text)
    }

    @Test
    fun `message should fall back to raw message and message fields`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(onIncomingMessage = { captured = it })

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                rawMessage = "/db raw"
                message = "/db message"
                arrayMsg = listOf(segment(MsgType.image, "file" to "demo.png"))
            }
        )
        assertEquals("/db raw", requireNotNull(captured).text)

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                rawMessage = ""
                message = "/db message"
                arrayMsg = listOf(segment(MsgType.image, "file" to "demo.png"))
            }
        )
        assertEquals("/db message", requireNotNull(captured).text)
    }

    @Test
    fun `message should preserve mentioned ids and bot account id`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(
            onIncomingMessage = { captured = it },
            botAccountIdProvider = { "42" },
        )

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                arrayMsg = listOf(
                    segment(MsgType.at, "qq" to "42"),
                    segment(MsgType.text, "text" to " https://t.bilibili.com/1"),
                    segment(MsgType.at, "qq" to "all"),
                )
            }
        )

        val incoming = requireNotNull(captured)
        assertEquals("42", incoming.botAccountId)
        assertEquals(setOf("42"), incoming.mentionedAccountIds)
        assertEquals("@42 https://t.bilibili.com/1@all", incoming.text)
    }

    @Test
    fun `message should extract at metadata from raw cq text fallback`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(
            onIncomingMessage = { captured = it },
            botAccountIdProvider = { "42" },
        )

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                rawMessage = "[CQ:at,qq=42] https://t.bilibili.com/1[CQ:image,file=demo.png]"
            }
        )

        val incoming = requireNotNull(captured)
        assertEquals("42", incoming.botAccountId)
        assertEquals(setOf("42"), incoming.mentionedAccountIds)
        assertEquals("@42 https://t.bilibili.com/1", incoming.text)
    }

    @Test
    fun `message should prefer event self id as bot account id`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(
            onIncomingMessage = { captured = it },
            botAccountIdProvider = { "24" },
        )

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                selfId = 42L
                groupId = 1L
                userId = 2L
                arrayMsg = listOf(
                    segment(MsgType.at, "qq" to "42"),
                    segment(MsgType.text, "text" to " https://t.bilibili.com/1"),
                )
            }
        )

        val incoming = requireNotNull(captured)
        assertEquals("42", incoming.botAccountId)
        assertEquals(setOf("42"), incoming.mentionedAccountIds)
    }

    @Test
    fun `array message should map incoming metadata and normalized segments`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(onIncomingMessage = { captured = it })

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                selfId = 42L
                time = 1_710_000_000L
                messageId = 321
                groupId = 1L
                userId = 2L
                arrayMsg = listOf(
                    segment(MsgType.at, "qq" to "42"),
                    segment(MsgType.text, "text" to " hello"),
                    segment(MsgType.image, "file" to "demo.png", "url" to "https://example.com/demo.png"),
                    segment(MsgType.reply, "id" to "123"),
                    segment(MsgType.json, "data" to "{}"),
                )
            }
        )

        val incoming = requireNotNull(captured)
        assertEquals("321", incoming.messageId)
        assertEquals(1_710_000_000L, incoming.timestamp)
        assertEquals("onebot-v11-json", incoming.rawFormat)
        assertTrue(incoming.rawPayload.contains("321"))
        assertIs<IncomingMessageSegment.Mention>(incoming.segments[0])
        assertEquals(" hello", assertIs<IncomingMessageSegment.Text>(incoming.segments[1]).text)
        val image = assertIs<IncomingMessageSegment.Image>(incoming.segments[2])
        assertEquals("demo.png", image.file)
        assertEquals("https://example.com/demo.png", image.url)
        assertEquals("123", assertIs<IncomingMessageSegment.Reply>(incoming.segments[3]).messageId)
        assertEquals("json", assertIs<IncomingMessageSegment.Unknown>(incoming.segments[4]).segmentType)
    }

    @Test
    fun `raw cq message should map known and unknown incoming segments`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(onIncomingMessage = { captured = it })

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                rawMessage =
                    "[CQ:reply,id=55][CQ:at,qq=42]hello[CQ:image,file=a.png,url=https://example.com/a.png][CQ:json,data={}]"
            }
        )

        val segments = requireNotNull(captured).segments
        assertEquals("55", assertIs<IncomingMessageSegment.Reply>(segments[0]).messageId)
        assertEquals("42", assertIs<IncomingMessageSegment.Mention>(segments[1]).targetId)
        assertEquals("hello", assertIs<IncomingMessageSegment.Text>(segments[2]).text)
        assertEquals("a.png", assertIs<IncomingMessageSegment.Image>(segments[3]).file)
        assertEquals("json", assertIs<IncomingMessageSegment.Unknown>(segments[4]).segmentType)
    }

    @Test
    fun `raw cq message should unescape text and segment parameters`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener(onIncomingMessage = { captured = it })

        listener.onGroupMessage(
            GroupMessageEvent().apply {
                groupId = 1L
                userId = 2L
                rawMessage =
                    "hello &amp; &#91;x&#93;[CQ:image,file=a&#44;b.png,url=https://example.com/a&amp;b=1&#44;c=2]tail &#93;"
            }
        )

        val incoming = requireNotNull(captured)
        assertEquals("hello & [x]tail ]", incoming.text)
        val segments = incoming.segments
        assertEquals("hello & [x]", assertIs<IncomingMessageSegment.Text>(segments[0]).text)
        val image = assertIs<IncomingMessageSegment.Image>(segments[1])
        assertEquals("a,b.png", image.file)
        assertEquals("https://example.com/a&b=1,c=2", image.url)
        assertEquals("tail ]", assertIs<IncomingMessageSegment.Text>(segments[2]).text)
    }

    private fun segment(type: MsgType, vararg data: Pair<String, String>): ArrayMsg {
        return ArrayMsg()
            .setType(type)
            .setData(data.toMap())
    }
}
