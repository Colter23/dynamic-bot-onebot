package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType

public sealed interface TargetRoute {
    public data class Group(val groupId: Long) : TargetRoute
    public data class User(val userId: Long) : TargetRoute
    public data class Unsupported(val reason: String) : TargetRoute

    public companion object {
        public fun fromSubscriber(subscriber: Subscriber): TargetRoute {
            val targetId = subscriber.userId.toLongOrNull() ?: return Unsupported("invalid_target_id")
            return when (subscriber.type ?: SubscriberType.OTHER) {
                SubscriberType.GROUP, SubscriberType.CHANNEL -> Group(targetId)
                SubscriberType.USER -> User(targetId)
                SubscriberType.OTHER -> Unsupported("unsupported_target_type_${subscriber.type}")
            }
        }
    }
}
