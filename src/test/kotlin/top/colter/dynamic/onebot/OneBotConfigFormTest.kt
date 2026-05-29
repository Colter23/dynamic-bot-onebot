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
        val reconnectField = OneBotConfigForm.spec.fields.single { it.path == "reconnect" }
        val reconnectMaxTimesField = OneBotConfigForm.spec.fields.single { it.path == "reconnectMaxTimes" }

        assertTrue(tokenField.secret)
        assertTrue(tokenField.restartRequired)
        assertEquals(1, portField.min)
        assertEquals(65_535, portField.max)
        assertTrue(reconnectField.description.contains("仅正向 WebSocket 生效"))
        assertEquals(0, reconnectMaxTimesField.min)
        assertTrue(reconnectMaxTimesField.description.contains("0 表示不重连"))
    }

    @Test
    fun `default config should keep forward websocket reconnect enabled`() {
        val config = OneBotConfig()

        assertTrue(config.reconnect)
        assertEquals(5, config.reconnectIntervalSeconds)
        assertEquals(3, config.reconnectMaxTimes)
    }

    @Test
    fun `plugin apply should validate and request restart`() {
        val plugin = OneBotGatewayPlugin()

        val result = plugin.applyConfig(OneBotConfig(url = "ws://127.0.0.1:6701"))

        assertTrue(result.changed)
        assertTrue(result.restartRequired)
        assertEquals(listOf("OneBot 插件"), result.restartTargets)
        val error = assertFailsWith<IllegalArgumentException> {
            plugin.applyConfig(OneBotConfig(mode = OneBotConnectionMode.REVERSE_WS, host = "", port = 6701))
        }
        assertEquals("反向 WebSocket 监听地址不能为空", error.message)
    }

    @Test
    fun `config validate should report Chinese messages`() {
        val error = assertFailsWith<IllegalArgumentException> {
            OneBotConfigForm.validate(OneBotConfig(port = 70_000))
        }

        assertEquals("反向 WebSocket 端口必须在 1 到 65535 之间", error.message)
    }
}
