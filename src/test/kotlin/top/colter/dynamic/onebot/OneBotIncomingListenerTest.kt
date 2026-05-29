package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import cn.evole.onebot.sdk.event.message.GroupMessageEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class OneBotIncomingListenerTest {

    @Test
    fun `group message should prefer array plain text over raw cq text`() {
        var captured: OneBotIncomingMessage? = null
        val listener = OneBotIncomingListener { captured = it }
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
        val listener = OneBotIncomingListener { captured = it }

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

    private fun segment(type: MsgType, vararg data: Pair<String, String>): ArrayMsg {
        return ArrayMsg()
            .setType(type)
            .setData(data.toMap())
    }
}
