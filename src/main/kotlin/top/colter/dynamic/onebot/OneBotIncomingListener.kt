package top.colter.dynamic.onebot

import cn.evole.onebot.client.annotations.SubscribeEvent
import cn.evole.onebot.client.interfaces.Listener
import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import cn.evole.onebot.sdk.event.message.GroupMessageEvent
import cn.evole.onebot.sdk.event.message.MessageEvent
import cn.evole.onebot.sdk.event.message.PrivateMessageEvent
import com.google.gson.Gson
import top.colter.dynamic.core.data.IncomingMessageSegment

internal class OneBotIncomingListener(
    private val onIncomingMessage: (OneBotIncomingMessage) -> Unit,
    private val botAccountIdProvider: () -> String? = { null },
) : Listener {

    @SubscribeEvent(internal = false)
    public fun onGroupMessage(event: GroupMessageEvent) {
        onIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = event.groupId.toString(),
                senderId = event.userId.toString(),
                text = event.toCommandText(),
                botAccountId = event.botAccountId(),
                messageId = event.messageId.takeIf { it != 0 }?.toString().orEmpty(),
                timestamp = event.eventTimestamp(),
                segments = event.toIncomingSegments(),
                rawFormat = ONEBOT_RAW_FORMAT,
                rawPayload = GSON.toJson(event),
                mentionedAccountIds = event.mentionedAccountIds(),
            )
        )
    }

    @SubscribeEvent(internal = false)
    public fun onPrivateMessage(event: PrivateMessageEvent) {
        onIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = event.userId.toString(),
                senderId = event.userId.toString(),
                text = event.toCommandText(),
                botAccountId = event.botAccountId(),
                messageId = event.messageId.takeIf { it != 0 }?.toString().orEmpty(),
                timestamp = event.eventTimestamp(),
                segments = event.toIncomingSegments(),
                rawFormat = ONEBOT_RAW_FORMAT,
                rawPayload = GSON.toJson(event),
                mentionedAccountIds = event.mentionedAccountIds(),
            )
        )
    }

    private fun MessageEvent.botAccountId(): String? {
        return selfId.takeIf { it > 0 }?.toString() ?: botAccountIdProvider()
    }

    private fun MessageEvent.eventTimestamp(): Long {
        return time.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000
    }

    private fun MessageEvent.toCommandText(): String {
        return arrayMsg.orEmpty()
            .joinToString("") { it.toPlainText() }
            .takeIf { it.isNotBlank() }
            ?: rawMessage?.takeIf { it.isNotBlank() }?.toPlainCqText()
            ?: message?.takeIf { it.isNotBlank() }?.toPlainCqText()
            ?: ""
    }

    private fun ArrayMsg.toPlainText(): String {
        return when (type) {
            MsgType.text -> data?.get("text").orEmpty()
            MsgType.at -> data?.get("qq")?.let { if (it == "all") "@all" else "@$it" }.orEmpty()
            MsgType.json -> CardParser.parseJson(data?.get("data").orEmpty())?.url.orEmpty()
            MsgType.xml -> CardParser.parseXml(data?.get("data").orEmpty())?.url.orEmpty()
            else -> ""
        }
    }

    private fun MessageEvent.toIncomingSegments(): List<IncomingMessageSegment> {
        val fromArray = arrayMsg.orEmpty().flatMap { it.toIncomingSegments() }
        if (fromArray.isNotEmpty()) return fromArray
        return rawMessage?.takeIf { it.isNotBlank() }?.toIncomingSegmentsFromCq()
            ?: message?.takeIf { it.isNotBlank() }?.toIncomingSegmentsFromCq()
            ?: emptyList()
    }

    private fun ArrayMsg.toIncomingSegments(): List<IncomingMessageSegment> {
        val segmentRaw = toRawPayload()
        val values = data.orEmpty()
        return when (type) {
            MsgType.text -> listOf(
                IncomingMessageSegment.Text(
                    text = values["text"].orEmpty(),
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.at -> listOf(
                IncomingMessageSegment.Mention(
                    targetId = values["qq"].orEmpty(),
                    all = values["qq"] == "all",
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.image -> listOf(
                IncomingMessageSegment.Image(
                    file = values["file"].orEmpty(),
                    url = values["url"],
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.video -> listOf(
                IncomingMessageSegment.Video(
                    file = values["file"].orEmpty(),
                    url = values["url"],
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.record -> listOf(
                IncomingMessageSegment.Audio(
                    file = values["file"].orEmpty(),
                    url = values["url"],
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.reply -> listOf(
                IncomingMessageSegment.Reply(
                    messageId = values["id"].orEmpty(),
                    rawPayload = segmentRaw,
                ),
            )
            MsgType.json -> CardParser.parseJson(values["data"].orEmpty())
                ?.toIncomingSegments(rawPayload = segmentRaw)
                ?: listOf(
                    IncomingMessageSegment.Unknown(
                        segmentType = type.name,
                        rawPayload = segmentRaw,
                    ),
                )
            MsgType.xml -> CardParser.parseXml(values["data"].orEmpty())
                ?.toIncomingSegments(rawPayload = segmentRaw)
                ?: listOf(
                    IncomingMessageSegment.Unknown(
                        segmentType = type.name,
                        rawPayload = segmentRaw,
                    ),
                )
            else -> listOf(
                IncomingMessageSegment.Unknown(
                    segmentType = type.name,
                    rawPayload = segmentRaw,
                ),
            )
        }
    }

    private fun CardParseResult.toIncomingSegments(rawPayload: String): List<IncomingMessageSegment> {
        val segments = mutableListOf<IncomingMessageSegment>()
        val summary = buildString {
            append("[卡片]")
            if (!title.isNullOrBlank()) {
                append(" ")
                append(title)
            }
            if (!description.isNullOrBlank()) {
                if (length > 4) append("：")
                append(description)
            }
            if (!url.isNullOrBlank()) {
                if (isNotBlank()) append("\n")
                append(url)
            }
        }
        if (summary.isNotBlank()) {
            segments += IncomingMessageSegment.Text(
                text = summary,
                rawPayload = rawPayload,
            )
        }
        if (!previewUrl.isNullOrBlank()) {
            segments += IncomingMessageSegment.Image(
                file = "",
                url = previewUrl,
                rawPayload = rawPayload,
            )
        }
        return segments
    }

    private fun ArrayMsg.toRawPayload(): String {
        return GSON.toJson(
            mapOf(
                "type" to type.name,
                "data" to data.orEmpty(),
            ),
        )
    }

    private fun MessageEvent.mentionedAccountIds(): Set<String> {
        val fromArray = arrayMsg.orEmpty()
            .asSequence()
            .filter { it.type == MsgType.at }
            .mapNotNull { it.data?.get("qq")?.trim() }
            .filter { it.isNotBlank() && it != "all" }
            .toCollection(linkedSetOf())
        if (fromArray.isNotEmpty()) return fromArray
        return rawMessage?.mentionedAccountIdsFromCq().orEmpty()
            .ifEmpty { message?.mentionedAccountIdsFromCq().orEmpty() }
    }

    private fun String.mentionedAccountIdsFromCq(): Set<String> {
        return CQ_AT_REGEX.findAll(this)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && it != "all" }
            .toCollection(linkedSetOf())
    }

    private fun String.toPlainCqText(): String {
        val cardUrls = this.extractCardUrlsFromCq()
        val body = replace(CQ_AT_REGEX) { match ->
            val target = match.groupValues[1].cqParamUnescape().trim()
            if (target == "all") "@all" else "@$target"
        }.replace(CQ_CODE_REGEX, "")
            .cqTextUnescape()
        return if (cardUrls.isBlank()) body else "$body $cardUrls".trim()
    }

    private fun String.extractCardUrlsFromCq(): String {
        val urls = mutableListOf<String>()
        CQ_JSON_REGEX.findAll(this).forEach { match ->
            CardParser.parseJson(match.groupValues[1].cqParamUnescape())?.url?.let { urls += it }
        }
        CQ_XML_REGEX.findAll(this).forEach { match ->
            CardParser.parseXml(match.groupValues[1].cqParamUnescape())?.url?.let { urls += it }
        }
        return urls.joinToString(" ")
    }

    private fun String.toIncomingSegmentsFromCq(): List<IncomingMessageSegment> {
        val segments = mutableListOf<IncomingMessageSegment>()
        var cursor = 0
        CQ_SEGMENT_REGEX.findAll(this).forEach { match ->
            if (match.range.first > cursor) {
                val text = substring(cursor, match.range.first).cqTextUnescape()
                if (text.isNotEmpty()) {
                    segments += IncomingMessageSegment.Text(text)
                }
            }
            val type = match.groupValues[1]
            val data = parseCqData(match.groupValues[2])
            when (type.lowercase()) {
                "json" -> segments += cardSegments(
                    type = "json",
                    parse = { CardParser.parseJson(data["data"].orEmpty()) },
                    raw = match.value,
                )
                "xml" -> segments += cardSegments(
                    type = "xml",
                    parse = { CardParser.parseXml(data["data"].orEmpty()) },
                    raw = match.value,
                )
                else -> segments += cqSegment(type, data, match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < length) {
            val text = substring(cursor).cqTextUnescape()
            if (text.isNotEmpty()) {
                segments += IncomingMessageSegment.Text(text)
            }
        }
        return segments.ifEmpty { listOf(IncomingMessageSegment.Text(cqTextUnescape())) }
    }

    private fun cardSegments(
        type: String,
        parse: () -> CardParseResult?,
        raw: String,
    ): List<IncomingMessageSegment> {
        return parse()?.toIncomingSegments(rawPayload = raw)
            ?: listOf(
                IncomingMessageSegment.Unknown(
                    segmentType = type,
                    rawPayload = raw,
                ),
            )
    }

    private fun parseCqData(rawParams: String): Map<String, String> {
        val params = rawParams.removePrefix(",")
        if (params.isBlank()) return emptyMap()
        return params
            .split(',')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = part.substring(0, index).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to part.substring(index + 1).cqParamUnescape()
            }
            .toMap()
    }

    private fun String.cqTextUnescape(): String {
        return replace("&#91;", "[")
            .replace("&#93;", "]")
            .replace("&amp;", "&")
    }

    private fun String.cqParamUnescape(): String {
        return replace("&#44;", ",")
            .cqTextUnescape()
    }

    private fun cqSegment(type: String, data: Map<String, String>, raw: String): IncomingMessageSegment {
        return when (type.lowercase()) {
            "at" -> IncomingMessageSegment.Mention(
                targetId = data["qq"].orEmpty(),
                all = data["qq"] == "all",
                rawPayload = raw,
            )
            "image" -> IncomingMessageSegment.Image(
                file = data["file"].orEmpty(),
                url = data["url"],
                rawPayload = raw,
            )
            "video" -> IncomingMessageSegment.Video(
                file = data["file"].orEmpty(),
                url = data["url"],
                rawPayload = raw,
            )
            "record" -> IncomingMessageSegment.Audio(
                file = data["file"].orEmpty(),
                url = data["url"],
                rawPayload = raw,
            )
            "reply" -> IncomingMessageSegment.Reply(
                messageId = data["id"].orEmpty(),
                rawPayload = raw,
            )
            else -> IncomingMessageSegment.Unknown(
                segmentType = type,
                rawPayload = raw,
            )
        }
    }

    private companion object {
        const val ONEBOT_RAW_FORMAT: String = "onebot-v11-json"
        val GSON: Gson = Gson()
        val CQ_AT_REGEX: Regex = Regex("""\[CQ:at,[^\]]*qq=([^,\]]+)[^\]]*]""")
        val CQ_CODE_REGEX: Regex = Regex("""\[CQ:[^\]]+]""")
        val CQ_SEGMENT_REGEX: Regex = Regex("""\[CQ:([a-zA-Z0-9_]+)((?:,[^\]]*)?)]""")
        val CQ_JSON_REGEX: Regex = Regex("""\[CQ:json,data=([^\]]+)]""")
        val CQ_XML_REGEX: Regex = Regex("""\[CQ:xml,data=([^\]]+)]""")
    }
}
