package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType

internal sealed interface OneBotTarget {
    data class Group(val groupId: Long) : OneBotTarget
    data class User(val userId: Long) : OneBotTarget
    data class Unsupported(val reason: String) : OneBotTarget

    companion object {
        fun fromSubscriber(subscriber: Subscriber): OneBotTarget {
            val targetId = subscriber.userId.toLongOrNull() ?: return Unsupported("invalid_target_id")
            return when (subscriber.type) {
                SubscriberType.GROUP -> Group(targetId)
                SubscriberType.USER -> User(targetId)
                SubscriberType.CHANNEL -> Unsupported("unsupported_target_type_CHANNEL")
                SubscriberType.OTHER, null -> Unsupported("unsupported_target_type_${subscriber.type ?: "null"}")
            }
        }
    }
}
