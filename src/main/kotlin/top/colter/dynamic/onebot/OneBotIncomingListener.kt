package top.colter.dynamic.onebot

import cn.evole.onebot.client.annotations.SubscribeEvent
import cn.evole.onebot.client.interfaces.Listener
import cn.evole.onebot.sdk.event.message.GroupMessageEvent
import cn.evole.onebot.sdk.event.message.PrivateMessageEvent

internal class OneBotIncomingListener(
    private val onIncomingMessage: (OneBotIncomingMessage) -> Unit,
) : Listener {

    @SubscribeEvent(internal = false)
    public fun onGroupMessage(event: GroupMessageEvent) {
        val text = event.rawMessage ?: event.message ?: ""
        onIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.GROUP,
                chatId = event.groupId.toString(),
                senderId = event.userId.toString(),
                text = text,
            )
        )
    }

    @SubscribeEvent(internal = false)
    public fun onPrivateMessage(event: PrivateMessageEvent) {
        val text = event.rawMessage ?: event.message ?: ""
        onIncomingMessage(
            OneBotIncomingMessage(
                chatType = OneBotChatType.PRIVATE,
                chatId = event.userId.toString(),
                senderId = event.userId.toString(),
                text = text,
            )
        )
    }
}
