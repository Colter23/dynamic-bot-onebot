package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigFieldType

class OneBotConfigFormTest {
    @Test
    fun `form should expose connections secret and restart fields`() {
        val connectionsField = OneBotConfigForm.spec.fields.single { it.path == "connections" }
        val reverseTokenField = OneBotConfigForm.spec.fields.single { it.path == "reverseAccessToken" }
        val portField = OneBotConfigForm.spec.fields.single { it.path == "port" }
        val reconnectField = OneBotConfigForm.spec.fields.single { it.path == "reconnect" }
        val reconnectMaxTimesField = OneBotConfigForm.spec.fields.single { it.path == "reconnectMaxTimes" }
        val hostField = OneBotConfigForm.spec.fields.single { it.path == "host" }

        assertEquals(ConfigFieldType.JSON, connectionsField.type)
        assertEquals("ONEBOT_CONNECTION_TABLE", connectionsField.component)
        assertTrue(connectionsField.restartRequired)
        assertEquals(listOf(OneBotConnectionMode.FORWARD_WS.name), connectionsField.visibleWhen?.values)
        assertTrue(reverseTokenField.secret)
        assertTrue(reverseTokenField.restartRequired)
        assertEquals(listOf(OneBotConnectionMode.REVERSE_WS.name), reverseTokenField.visibleWhen?.values)
        assertEquals(1, portField.min)
        assertEquals(65_535, portField.max)
        assertEquals(listOf(OneBotConnectionMode.REVERSE_WS.name), hostField.visibleWhen?.values)
        assertTrue(reconnectField.description.contains("仅正向 WebSocket 生效"))
        assertEquals(0, reconnectMaxTimesField.min)
        assertTrue(reconnectMaxTimesField.description.contains("0 表示不重连"))
    }

    @Test
    fun `default config should keep one forward websocket connection and reconnect enabled`() {
        val config = OneBotConfig()

        assertEquals(listOf("ws://127.0.0.1:6700"), config.connections.map { it.url })
        assertEquals(listOf(""), config.connections.map { it.accessToken })
        assertEquals(listOf(""), config.connections.map { it.name })
        assertTrue(config.reconnect)
        assertEquals(5, config.reconnectIntervalSeconds)
        assertEquals(3, config.reconnectMaxTimes)
    }

    @Test
    fun `plugin apply should validate and request restart`() {
        val plugin = OneBotGatewayPlugin()

        val result = plugin.applyConfig(
            OneBotConfig(
                connections = listOf(OneBotForwardConnectionConfig(url = "ws://127.0.0.1:6701")),
            ),
        )

        assertTrue(result.changed)
        assertTrue(result.restartRequired)
        assertEquals(listOf("OneBot 插件"), result.restartTargets)
        val error = assertFailsWith<IllegalArgumentException> {
            plugin.applyConfig(
                OneBotConfig(
                    mode = OneBotConnectionMode.REVERSE_WS,
                    host = "",
                ),
            )
        }
        assertEquals("反向 WebSocket 监听地址不能为空", error.message)
    }

    @Test
    fun `config validate should reject invalid connections`() {
        val noEnabledConnection = assertFailsWith<IllegalArgumentException> {
            OneBotConfigForm.validate(
                OneBotConfig(
                    connections = listOf(OneBotForwardConnectionConfig(enabled = false)),
                ),
            )
        }
        assertEquals("至少需要启用一个正向 WebSocket 连接", noEnabledConnection.message)

        val blankUrl = assertFailsWith<IllegalArgumentException> {
            OneBotConfigForm.validate(
                OneBotConfig(
                    connections = listOf(OneBotForwardConnectionConfig(url = " ")),
                ),
            )
        }
        assertEquals("正向连接[0].url 不能为空", blankUrl.message)
    }

    @Test
    fun `enabled connections should trim marker url and token`() {
        val config = OneBotConfig(
            connections = listOf(
                OneBotForwardConnectionConfig(
                    url = "  ws://127.0.0.1:6701  ",
                    accessToken = " token ",
                    name = " 主连接 ",
                ),
            ),
        )

        assertEquals(
            listOf(
                OneBotForwardConnectionConfig(
                    url = "ws://127.0.0.1:6701",
                    accessToken = "token",
                    name = "主连接",
                ),
            ),
            config.enabledConnections(),
        )
    }
}
