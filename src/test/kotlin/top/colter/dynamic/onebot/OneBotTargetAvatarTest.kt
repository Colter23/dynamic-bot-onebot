package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.TargetKind

class OneBotTargetAvatarTest {
    @Test
    fun oneBotTargetAvatarShouldBuildGroupAndUserUrls() {
        val group = oneBotTargetAvatar(TargetKind.GROUP, "10001")
        val user = oneBotTargetAvatar(TargetKind.USER, "20002")

        assertEquals("https://p.qlogo.cn/gh/10001/10001/100", group?.uri)
        assertEquals(MediaKind.AVATAR, group?.kind)
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=20002&s=100", user?.uri)
        assertEquals(MediaKind.AVATAR, user?.kind)
    }

    @Test
    fun oneBotTargetAvatarShouldRejectUnsupportedOrNonNumericTargets() {
        assertNull(oneBotTargetAvatar(TargetKind.GROUP, "group-a"))
        assertNull(oneBotTargetAvatar(TargetKind.CHANNEL, "10001"))
    }
}
