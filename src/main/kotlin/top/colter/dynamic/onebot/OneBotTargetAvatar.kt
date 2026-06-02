package top.colter.dynamic.onebot

import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.TargetKind

internal fun oneBotTargetAvatar(kind: TargetKind, id: String): MediaRef? {
    val normalizedId = id.trim()
    if (normalizedId.isBlank() || !normalizedId.all { it.isDigit() }) return null
    val uri = when (kind) {
        TargetKind.GROUP -> "https://p.qlogo.cn/gh/$normalizedId/$normalizedId/100"
        TargetKind.USER -> "https://q1.qlogo.cn/g?b=qq&nk=$normalizedId&s=100"
        else -> return null
    }
    return MediaRef(uri = uri, kind = MediaKind.AVATAR)
}
