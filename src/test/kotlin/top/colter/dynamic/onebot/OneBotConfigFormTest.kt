package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneBotConfigFormTest {
    @Test
    fun `form should expose secret token and restart fields`() {
        val tokenField = OneBotConfigForm.spec.fields.single { it.path == "accessToken" }
        val portField = OneBotConfigForm.spec.fields.single { it.path == "port" }

        assertTrue(tokenField.secret)
        assertTrue(tokenField.restartRequired)
        assertEquals(1, portField.min)
        assertEquals(65_535, portField.max)
    }

    @Test
    fun `plugin apply should validate and request restart`() {
        val plugin = OneBotGatewayPlugin()

        val result = plugin.applyConfig(OneBotConfig(url = "ws://127.0.0.1:6701"))

        assertTrue(result.changed)
        assertTrue(result.restartRequired)
        assertEquals(listOf("OneBot 插件"), result.restartTargets)
        assertFailsWith<IllegalArgumentException> {
            plugin.applyConfig(OneBotConfig(mode = OneBotConnectionMode.REVERSE_WS, host = "", port = 6701))
        }
    }
}
