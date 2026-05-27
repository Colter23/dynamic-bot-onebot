package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent

public object OneBotMessageMapper {
    public fun toArrayMessage(message: Message): List<ArrayMsg> {
        return toArrayMessage(message.chain)
    }

    public fun toArrayMessages(message: Message): List<List<ArrayMsg>> {
        return toArrayMessages(message.chain)
    }

    public fun toJsonArrayMessage(message: Message): JsonArray {
        return toJsonArray(toArrayMessage(message))
    }

    public fun toJsonArrayMessage(chain: List<MessageChain>): JsonArray {
        return toJsonArray(toArrayMessage(chain))
    }

    public fun toJsonArrayMessages(message: Message): List<JsonArray> {
        return toArrayMessages(message).map { toJsonArray(it) }
    }

    public fun toJsonArrayMessages(chain: List<MessageChain>): List<JsonArray> {
        return toArrayMessages(chain).map { toJsonArray(it) }
    }

    public fun toJsonArray(message: List<ArrayMsg>): JsonArray {
        val array = JsonArray()
        message.forEach { segment ->
            val item = JsonObject()
            item.addProperty("type", segment.type.name)
            item.add("data", JsonObject().apply {
                segment.data.orEmpty().forEach { (key, value) ->
                    addProperty(key, value)
                }
            })
            array.add(item)
        }
        return array
    }

    public fun toArrayMessages(chain: List<MessageChain>): List<List<ArrayMsg>> {
        return chain
            .map { it.toArrayMessage() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(listOf(text("(empty)"))) }
    }

    public fun toArrayMessage(chain: List<MessageChain>): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        toArrayMessages(chain).forEach { segments ->
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
