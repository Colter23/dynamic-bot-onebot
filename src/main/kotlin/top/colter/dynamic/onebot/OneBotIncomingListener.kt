package top.colter.dynamic.onebot

import cn.evole.onebot.client.annotations.SubscribeEvent
import cn.evole.onebot.client.interfaces.Listener
import cn.evole.onebot.sdk.entity.ArrayMsg
import cn.evole.onebot.sdk.enums.MsgType
import cn.evole.onebot.sdk.event.message.GroupMessageEvent
import cn.evole.onebot.sdk.event.message.MessageEvent
import cn.evole.onebot.sdk.event.message.PrivateMessageEvent

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
                mentionedAccountIds = event.mentionedAccountIds(),
            )
        )
    }

    private fun MessageEvent.botAccountId(): String? {
        return selfId.takeIf { it > 0 }?.toString() ?: botAccountIdProvider()
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
            else -> ""
        }
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
        return replace(CQ_AT_REGEX) { match ->
            val target = match.groupValues[1].trim()
            if (target == "all") "@all" else "@$target"
        }.replace(CQ_CODE_REGEX, "")
    }

    private companion object {
        val CQ_AT_REGEX: Regex = Regex("""\[CQ:at,[^\]]*qq=([^,\]]+)[^\]]*]""")
        val CQ_CODE_REGEX: Regex = Regex("""\[CQ:[^\]]+]""")
    }
}
