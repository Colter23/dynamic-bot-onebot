package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent

public object OneBotMessageMapper {
    public fun toArrayMessage(message: Message): List<ArrayMsg> {
        return toArrayMessage(message.chain)
    }

    public fun toArrayMessage(chain: List<MessageChain>): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        chain.forEach { messageChain ->
            val segments = messageChain.toArrayMessage()
            if (segments.isEmpty()) return@forEach
            if (result.isNotEmpty()) {
                result += text("\n")
            }
            result += segments
        }
        return result.ifEmpty { listOf(text("(empty)")) }
    }

    private fun MessageChain.toArrayMessage(): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        content.forEach { item ->
            when (item) {
                is MessageContent.Text -> result.addText(item.fallbackText)
                is MessageContent.Mention -> {
                    result.addText(item.fallbackText)
                    result += segment(MsgType.at, "qq" to item.targetId)
                }
                is MessageContent.MentionAll -> {
                    result.addText(item.fallbackText)
                    result += segment(MsgType.at, "qq" to "all")
                }
                is MessageContent.Image -> {
                    result += segment(MsgType.image, "file" to item.image.uri)
                    result.addText(item.fallbackText)
                }
                is MessageContent.Video -> {
                    result += segment(MsgType.video, "file" to item.videoUri)
                    result.addText(item.fallbackText)
                }
                is MessageContent.Audio -> {
                    result += segment(MsgType.record, "file" to item.audioUri)
                    result.addText(item.fallbackText)
                }
                is MessageContent.Reply -> {
                    result += segment(MsgType.reply, "id" to item.messageId)
                    result.addText(item.fallbackText)
                }
                is MessageContent.Forward -> {
                    result.addText(item.fallbackText.ifBlank { "[forward messages: ${item.messages.size}]" })
                }
            }
        }
        return result
    }

    private fun MutableList<ArrayMsg>.addText(value: String) {
        if (value.isNotBlank()) {
            add(text(value))
        }
    }

    private fun text(value: String): ArrayMsg = segment(MsgType.text, "text" to value)

    private fun segment(type: MsgType, vararg data: Pair<String, String>): ArrayMsg {
        return ArrayMsg()
            .setType(type)
            .setData(data.toMap())
    }
}
