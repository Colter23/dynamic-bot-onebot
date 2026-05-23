package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent

public object OneBotMessageFormatter {
    public fun format(message: Message): String {
        return format(message.chain)
    }

    public fun format(chain: List<MessageChain>): String {
        val lines: MutableList<String> = mutableListOf()
        var firstImageUrl: String = ""

        chain.forEach { messageChain ->
            messageChain.content.forEach { content ->
                when (content) {
                    is MessageContent.Text -> if (content.text.isNotBlank()) lines += content.text
                    is MessageContent.At -> lines += content.text.ifBlank { "@${content.targetId}" }
                    is MessageContent.AtAll -> lines += content.text.ifBlank { "@all" }
                    is MessageContent.Image -> {
                        if (firstImageUrl.isBlank()) firstImageUrl = content.image.url
                        if (content.text.isNotBlank()) lines += content.text
                    }
                    is MessageContent.Video -> if (content.text.isNotBlank()) lines += content.text
                    is MessageContent.Audio -> if (content.text.isNotBlank()) lines += content.text
                    is MessageContent.Reply -> if (content.text.isNotBlank()) lines += content.text
                    is MessageContent.Forward -> if (content.text.isNotBlank()) lines += content.text
                }
            }
        }

        val text = if (lines.isNotEmpty()) lines.joinToString("\n") else "(empty)"
        return if (firstImageUrl.isNotBlank()) "[CQ:image,file=$firstImageUrl]\n$text" else text
    }
}
