package top.colter.dynamic.onebot

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 解析 OneBot 卡片消息（json / xml）中的有效信息。
 *
 * 由于 dynamic-bot-core 的 [IncomingMessageSegment] 没有卡片类型，
 * 解析结果会被映射为 [IncomingMessageSegment.Text] 与 [IncomingMessageSegment.Image]，
 * 以便链接自动解析、渲染等下游逻辑能继续处理。
 */
public data class CardParseResult(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val previewUrl: String? = null,
) {
    /**
     * 是否有任何有效字段。
     */
    public fun isEmpty(): Boolean =
        title.isNullOrBlank() && description.isNullOrBlank() && url.isNullOrBlank() && previewUrl.isNullOrBlank()
}

public object CardParser {

    /**
     * 解析 JSON 卡片。
     *
     * 支持常见的 QQ 小程序 / 分享卡片结构：
     * - meta.detail_1.qqdocurl / url / jumpUrl
     * - meta.detail_1.title / desc
     * - meta.detail_1.preview / cover / icon
     * - 顶层 prompt / title / desc / url 作为兜底
     */
    public fun parseJson(data: String): CardParseResult? {
        return runCatching {
            val root = JsonParser.parseString(data)
            if (!root.isJsonObject) return null
            parseJsonObject(root.asJsonObject)
        }.getOrNull()
    }

    private fun parseJsonObject(root: JsonObject): CardParseResult? {
        val detail: JsonObject? = root.navigate("meta", "detail_1")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: root.navigate("meta", "news")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: root.getAsJsonObject("meta")
            ?: root

        val title = detail?.string("title")
            ?: detail?.string("tag")
            ?: root.string("prompt")
            ?: root.string("title")
        val description = detail?.string("desc")
            ?: detail?.string("description")
            ?: detail?.string("summary")
            ?: root.string("desc")
        val url = detail?.string("qqdocurl")
            ?: detail?.string("jumpUrl")
            ?: detail?.string("url")
            ?: root.string("url")
        val previewUrl = detail?.string("preview")
            ?: detail?.string("cover")
            ?: detail?.string("icon")
            ?: root.string("preview")

        val result = CardParseResult(
            title = title?.trim(),
            description = description?.trim(),
            url = url?.trim(),
            previewUrl = previewUrl?.trim(),
        )
        return result.takeUnless { it.isEmpty() }
    }

    /**
     * 解析 XML 卡片。
     *
     * 支持常见的 QQ XML 分享卡片结构：
     * - <message url="...">
     * - <title>...</title>
     * - <summary>...</summary> / <desc>...</desc>
     * - <picture cover="..."/>
     */
    public fun parseXml(data: String): CardParseResult? {
        return runCatching {
            val title = XML_TITLE.find(data)?.groupValues?.get(1)
            val description = XML_DESC.find(data)?.groupValues?.get(1)
                ?: XML_SUMMARY.find(data)?.groupValues?.get(1)
            val url = XML_MESSAGE_URL.find(data)?.groupValues?.get(1)
                ?: XML_URL.find(data)?.groupValues?.get(1)
                ?: XML_ACTION.find(data)?.groupValues?.get(1)
            val previewUrl = XML_PICTURE_COVER.find(data)?.groupValues?.get(1)
                ?: XML_COVER_ATTR.find(data)?.groupValues?.get(1)

            val result = CardParseResult(
                title = title?.trim(),
                description = description?.trim(),
                url = url?.trim(),
                previewUrl = previewUrl?.trim(),
            )
            result.takeUnless { it.isEmpty() }
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? {
        return get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.navigate(vararg keys: String): JsonElement? {
        var current: JsonElement = this
        for (key in keys) {
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(key) ?: return null
        }
        return current
    }

    private val XML_TITLE = Regex("""<title\b[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val XML_DESC = Regex("""<desc\b[^>]*>([^<]+)</desc>""", RegexOption.IGNORE_CASE)
    private val XML_SUMMARY = Regex("""<summary\b[^>]*>([^<]+)</summary>""", RegexOption.IGNORE_CASE)
    private val XML_URL = Regex("""<url\b[^>]*>([^<]+)</url>""", RegexOption.IGNORE_CASE)
    private val XML_ACTION = Regex("""<action\b[^>]*>([^<]+)</action>""", RegexOption.IGNORE_CASE)
    private val XML_MESSAGE_URL = Regex("""<message\b[^>]*\s+url\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val XML_PICTURE_COVER = Regex("""<picture\b[^>]*\s+cover\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val XML_COVER_ATTR = Regex("""cover\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
}
