package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.MessageTarget
import top.colter.dynamic.core.data.SubscriberType

internal sealed interface OneBotTarget {
    data class Group(val groupId: Long) : OneBotTarget
    data class User(val userId: Long) : OneBotTarget
    data class Unsupported(val reason: String) : OneBotTarget

    companion object {
        fun fromMessageTarget(target: MessageTarget): OneBotTarget {
            val targetId = target.targetId.toLongOrNull() ?: return Unsupported("invalid_target_id")
            return when (target.type) {
                SubscriberType.GROUP -> Group(targetId)
                SubscriberType.USER -> User(targetId)
                SubscriberType.CHANNEL -> Unsupported("unsupported_target_type_CHANNEL")
                SubscriberType.OTHER -> Unsupported("unsupported_target_type_${target.type}")
            }
        }
    }
}
