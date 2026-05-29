package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

internal sealed interface OneBotTarget {
    data class Group(val groupId: Long) : OneBotTarget
    data class User(val userId: Long) : OneBotTarget
    data class Unsupported(val reason: String) : OneBotTarget

    companion object {
        fun fromAddress(target: TargetAddress): OneBotTarget {
            val targetId = target.externalId.toLongOrNull() ?: return Unsupported("invalid_target_id")
            return when (target.kind) {
                TargetKind.GROUP -> Group(targetId)
                TargetKind.USER -> User(targetId)
                else -> Unsupported("unsupported_target_type_${target.kind}")
            }
        }
    }
}
