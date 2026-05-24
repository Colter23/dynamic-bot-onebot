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
) : Listener {

    @SubscribeEvent(internal = false)
    public fun onGroupMessage(event: GroupMessageEvent) {
        onIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = event.groupId.toString(),
                senderId = event.userId.toString(),
                text = event.toCommandText(),
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
            )
        )
    }

    private fun MessageEvent.toCommandText(): String {
        return rawMessage?.takeIf { it.isNotBlank() }
            ?: message?.takeIf { it.isNotBlank() }
            ?: arrayMsg.orEmpty().joinToString("") { it.toPlainText() }
    }

    private fun ArrayMsg.toPlainText(): String {
        return when (type) {
            MsgType.text -> data?.get("text").orEmpty()
            MsgType.at -> data?.get("qq")?.let { if (it == "all") "@all" else "@$it" }.orEmpty()
            else -> ""
        }
    }
}
