package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent

public object OneBotMessageMapper {
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
            .ifEmpty { listOf(listOf(text("(empty)"))) }
    }

    public fun toArrayMessage(batches: List<MessageBatch>): List<ArrayMsg> {
        val result = mutableListOf<ArrayMsg>()
        toArrayMessages(batches).forEach { segments ->
            if (result.isNotEmpty()) {
                result += text("\n")
            }
            result += segments
        }
        return result.ifEmpty { listOf(text("(empty)")) }
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

    private fun String.toOneBotImageFile(): String {
        return localReadablePath()
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { path -> "base64://${Base64.getEncoder().encodeToString(Files.readAllBytes(path))}" }
            ?: toOneBotFileUri()
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

    private fun String.localReadablePath(): Path? {
        val value = trim()
        if (value.isBlank() || isRemoteUri()) return null
        return try {
            val uri = runCatching { URI(value) }.getOrNull()
            when {
                uri != null && uri.scheme.equals("file", ignoreCase = true) -> Paths.get(uri)
                uri != null && uri.scheme != null -> null
                else -> Paths.get(URLDecoder.decode(value, StandardCharsets.UTF_8))
            }?.toAbsolutePath()?.normalize()
        } catch (_: Exception) {
            null
        }
    }

    private fun String.isRemoteUri(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("base64://", ignoreCase = true)
    }

    private fun String.hasUriScheme(): Boolean {
        return URI_SCHEME.matches(takeWhile { it != '/' && it != '\\' })
    }

    private val WINDOWS_ABSOLUTE_PATH: Regex = Regex("""^([a-zA-Z]):[\\/](.+)$""")
    private val URI_SCHEME: Regex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:.*$""")
}
