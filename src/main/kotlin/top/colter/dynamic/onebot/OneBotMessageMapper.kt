package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent

public sealed interface OneBotSendUnit {
    public data class Normal(val message: JsonArray) : OneBotSendUnit
    public data class Forward(val messages: List<Map<String, Any>>) : OneBotSendUnit
}

public object OneBotMessageMapper {
    public fun toSendUnits(message: Message): List<OneBotSendUnit> {
        return toSendUnits(message.batches)
    }

    public fun toSendUnits(batches: List<MessageBatch>): List<OneBotSendUnit> {
        val units = mutableListOf<OneBotSendUnit>()
        val normalBatches = mutableListOf<MessageBatch>()

        fun flushNormal() {
            if (normalBatches.isEmpty()) return
            toJsonArrayMessages(normalBatches).forEach { payload ->
                units += OneBotSendUnit.Normal(payload)
            }
            normalBatches.clear()
        }

        batches.forEach { batch ->
            val current = mutableListOf<MessageContent>()

            fun flushBatch() {
                if (current.isNotEmpty()) {
                    normalBatches += MessageBatch(current.toList())
                    current.clear()
                }
            }

            batch.content.forEach { content ->
                when (content) {
                    is MessageContent.Forward -> {
                        flushBatch()
                        flushNormal()
                        units += OneBotSendUnit.Forward(content.toOneBotForwardNodes())
                    }
                    else -> current += content
                }
            }
            flushBatch()
        }
        flushNormal()

        return units.ifEmpty {
            listOf(OneBotSendUnit.Normal(toJsonArray(listOf(text(EMPTY_MESSAGE_TEXT)))))
        }
    }

    public fun toArrayMessage(message: Message): List<ArrayMsg> {
        return toArrayMessage(message.batches)
    }

    public fun toArrayMessages(message: Message): List<List<ArrayMsg>> {
        return toArrayMessages(message.batches)
    }

    public fun toJsonArrayMessage(message: Message): JsonArray {
        return toJsonArray(toArrayMessage(message))
    }

    public fun toJsonArrayMessage(batches: List<MessageBatch>): JsonArray {
        return toJsonArray(toArrayMessage(batches))
    }

    public fun toJsonArrayMessages(message: Message): List<JsonArray> {
        return toArrayMessages(message).map { toJsonArray(it) }
    }

    public fun toJsonArrayMessages(batches: List<MessageBatch>): List<JsonArray> {
        return toArrayMessages(batches).map { toJsonArray(it) }
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

    public fun toArrayMessages(batches: List<MessageBatch>): List<List<ArrayMsg>> {
        return batches
            .map { it.toArrayMessage() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(listOf(text(EMPTY_MESSAGE_TEXT))) }
    }

    public fun toArrayMessage(batches: List<MessageBatch>): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        toArrayMessages(batches).forEach { segments ->
            if (result.isNotEmpty()) {
                result += text("\n")
            }
            result += segments
        }
        return result.ifEmpty { listOf(text(EMPTY_MESSAGE_TEXT)) }
    }

    private fun MessageBatch.toArrayMessage(): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        content.forEach { item ->
            when (item) {
                is MessageContent.Text -> result.addText(item.fallbackText)
                is MessageContent.Mention -> {
                    result.addText(item.fallbackText)
                    result += segment(MsgType.at, "qq" to item.target.externalId)
                }
                is MessageContent.MentionAll -> {
                    result.addText(item.fallbackText)
                    result += segment(MsgType.at, "qq" to "all")
                }
                is MessageContent.Image -> {
                    result += segment(MsgType.image, "file" to item.image.uri.toOneBotImageFile())
                    result.addText(item.fallbackText)
                }
                is MessageContent.Video -> {
                    result += segment(MsgType.video, "file" to item.video.uri.toOneBotFileUri())
                    result.addText(item.fallbackText)
                }
                is MessageContent.Audio -> {
                    result += segment(MsgType.record, "file" to item.audio.uri.toOneBotFileUri())
                    result.addText(item.fallbackText)
                }
                is MessageContent.Reply -> {
                    result += segment(MsgType.reply, "id" to item.messageId)
                    result.addText(item.fallbackText)
                }
                is MessageContent.Forward -> {
                    result.addText(item.fallbackText)
                }
            }
        }
        return result
    }

    private fun MessageContent.Forward.toOneBotForwardNodes(): List<Map<String, Any>> {
        return nodes.map { node ->
            mapOf(
                "type" to "node",
                "data" to buildMap<String, Any> {
                    put("name", node.senderName)
                    put("uin", node.senderId)
                    put("content", toJsonArrayMessage(node.batches))
                    put("time", node.time)
                },
            )
        }
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

    private fun String.toOneBotImageFile(): String {
        return toOneBotFileUri()
    }

    private fun String.toOneBotFileUri(): String {
        val value = trim()
        if (value.isBlank()) return value
        WINDOWS_ABSOLUTE_PATH.matchEntire(value)?.let { match ->
            val drive = match.groupValues[1].uppercase()
            val path = match.groupValues[2].replace('\\', '/')
            return URI("file", "", "/$drive:/$path", null).toString()
        }
        if (value.startsWith("\\\\")) {
            return value.replace('\\', '/').let { "file:$it" }
        }
        if (value.hasUriScheme()) return value
        return try {
            val path = Paths.get(value)
            if (path.isAbsolute) path.toUri().toString() else value
        } catch (_: InvalidPathException) {
            value
        }
    }

    private fun String.hasUriScheme(): Boolean {
        return URI_SCHEME.matches(takeWhile { it != '/' && it != '\\' })
    }

    private const val EMPTY_MESSAGE_TEXT: String = "（空消息）"
    private val WINDOWS_ABSOLUTE_PATH: Regex = Regex("""^([a-zA-Z]):[\\/](.+)$""")
    private val URI_SCHEME: Regex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:.*$""")
}
