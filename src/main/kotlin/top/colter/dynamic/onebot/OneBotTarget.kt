package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind

internal sealed interface OneBotTarget {
    data class Group(val groupId: Long) : OneBotTarget
    data class User(val userId: Long) : OneBotTarget
    data class Unsupported(val reason: String) : OneBotTarget

    companion object {
        fun fromAddress(target: TargetAddress): OneBotTarget {
            val targetId = target.externalId.toLongOrNull()
                ?: return Unsupported("OneBot 目标 ID 必须是数字：${target.externalId}")
            return when (target.kind) {
                TargetKind.GROUP -> Group(targetId)
                TargetKind.USER -> User(targetId)
                else -> Unsupported("OneBot 不支持目标类型：${target.kind}")
            }
        }
    }
}
