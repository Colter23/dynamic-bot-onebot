package top.colter.dynamic.onebot

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import top.colter.dynamic.core.data.IncomingMessageSegment

/** 将 OneBot JSON 卡片中的明确跳转地址标准化为通用入站链接段。 */
internal object OneBotJsonCardLinkExtractor {
    private val urlKeys: List<String> = listOf(
        "qqdocurl",
        "url",
        "jumpUrl",
        "jump_url",
        "shareUrl",
        "share_url",
        "targetUrl",
        "target_url",
    )
    private val titleKeys: List<String> = listOf("title", "name")

    fun extract(payload: String?, rawPayload: String): IncomingMessageSegment.Link? {
        val source = payload?.trim().orEmpty()
        if (source.isEmpty() || source.length > MAX_CARD_JSON_LENGTH) return null

        val root = runCatching { JsonParser.parseString(source).asJsonObject }.getOrNull() ?: return null
        val card = cardObjects(root)
        val url = card.asSequence()
            .flatMap { objectNode -> urlKeys.asSequence().mapNotNull { objectNode.stringValue(it) } }
            .mapNotNull(::normalizeHttpUrl)
            .firstOrNull()
            ?: return null
        val title = card.asSequence()
            .flatMap { objectNode -> titleKeys.asSequence().mapNotNull { objectNode.stringValue(it) } }
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_TITLE_LENGTH)

        return IncomingMessageSegment.Link(
            url = url,
            title = title,
            rawPayload = rawPayload,
        )
    }

    private fun cardObjects(root: JsonObject): Sequence<JsonObject> = sequence {
        val meta = root.objectValue("meta")
        meta?.entrySet()
            ?.asSequence()
            ?.mapNotNull { (_, value) -> value.asObjectOrNull() }
            ?.forEach { yield(it) }
        meta?.let { yield(it) }
        root.objectValue("data")?.let { yield(it) }
        yield(root)
    }

    private fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.asObjectOrNull()

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun normalizeHttpUrl(value: String): String? {
        val normalized = value.trim().replace("&amp;", "&")
        if (normalized.isEmpty() || normalized.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        return normalized.takeIf {
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }
    }

    private const val MAX_CARD_JSON_LENGTH: Int = 64 * 1024
    private const val MAX_URL_LENGTH: Int = 2_048
    private const val MAX_TITLE_LENGTH: Int = 200
}
