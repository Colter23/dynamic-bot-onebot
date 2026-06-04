package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.MutableConfigDocument
import top.colter.dynamic.core.plugin.MessageSinkAccountRole
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy

class OneBotConfigFormTest {
    @Test
    fun `form should expose accounts routing secret and restart fields`() {
        val accountsField = OneBotConfigForm.spec.fields.single { it.path == "accounts" }
        val tokenField = OneBotConfigForm.spec.fields.single { it.path == "accessToken" }
        val portField = OneBotConfigForm.spec.fields.single { it.path == "port" }
        val reconnectField = OneBotConfigForm.spec.fields.single { it.path == "reconnect" }
        val reconnectMaxTimesField = OneBotConfigForm.spec.fields.single { it.path == "reconnectMaxTimes" }
        val strategyField = OneBotConfigForm.spec.fields.single { it.path == "routingPolicy.strategy" }
        val hostField = OneBotConfigForm.spec.fields.single { it.path == "host" }

        assertEquals(ConfigFieldType.JSON, accountsField.type)
        assertTrue(accountsField.restartRequired)
        assertTrue(tokenField.secret)
        assertTrue(tokenField.restartRequired)
        assertEquals(1, portField.min)
        assertEquals(65_535, portField.max)
        assertEquals(listOf(OneBotConnectionMode.REVERSE_WS.name), hostField.visibleWhen?.values)
        assertEquals(
            setOf(MessageSinkRoutingStrategy.ROUND_ROBIN.name, MessageSinkRoutingStrategy.PRIMARY_BACKUP.name),
            strategyField.options.map { it.value }.toSet(),
        )
        assertTrue(reconnectField.description.contains("仅正向 WebSocket 生效"))
        assertEquals(0, reconnectMaxTimesField.min)
        assertTrue(reconnectMaxTimesField.description.contains("0 表示不重连"))
    }

    @Test
    fun `default config should keep one forward websocket account and reconnect enabled`() {
        val config = OneBotConfig()

        assertEquals(listOf("default"), config.accounts.map { it.accountId })
        assertTrue(config.reconnect)
        assertEquals(5, config.reconnectIntervalSeconds)
        assertEquals(3, config.reconnectMaxTimes)
    }

    @Test
    fun `plugin apply should validate and request restart`() {
        val plugin = OneBotGatewayPlugin()

        val result = plugin.applyConfig(
            OneBotConfig(
                accounts = listOf(
                    OneBotAccountConfig(accountId = "123456", name = "主机器人", url = "ws://127.0.0.1:6701"),
                ),
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
                    accounts = listOf(OneBotAccountConfig(accountId = "123456")),
                ),
            )
        }
        assertEquals("反向 WebSocket 监听地址不能为空", error.message)
    }

    @Test
    fun `config validate should reject invalid accounts`() {
        val duplicated = assertFailsWith<IllegalArgumentException> {
            OneBotConfigForm.validate(
                OneBotConfig(
                    accounts = listOf(
                        OneBotAccountConfig(accountId = "42"),
                        OneBotAccountConfig(accountId = "42", role = MessageSinkAccountRole.BACKUP),
                    ),
                ),
            )
        }
        assertEquals("OneBot 账号 ID 不能重复：42", duplicated.message)

        val reverseAlias = assertFailsWith<IllegalArgumentException> {
            OneBotConfigForm.validate(
                OneBotConfig(
                    mode = OneBotConnectionMode.REVERSE_WS,
                    accounts = listOf(OneBotAccountConfig(accountId = "bot-a")),
                ),
            )
        }
        assertEquals("反向 WebSocket 账号 bot-a 需要使用机器人 QQ 号作为 accountId", reverseAlias.message)
    }

    @Test
    fun `migration should convert legacy single account fields to accounts list`() {
        val document = MapConfigDocument(
            "mode" to OneBotConnectionMode.FORWARD_WS.name,
            "url" to "ws://127.0.0.1:6709",
            "botId" to 123456L,
            "accessToken" to "secret",
        )

        OneBotConfigForm.migrations.forEach { it.apply(document) }

        val accounts = document.get("accounts") as List<*>
        val first = accounts.single() as Map<*, *>
        assertEquals("123456", first["accountId"])
        assertEquals("机器人 123456", first["name"])
        assertEquals(true, first["enabled"])
        assertEquals(MessageSinkAccountRole.PRIMARY.name, first["role"])
        assertEquals("ws://127.0.0.1:6709", first["url"])
        assertTrue(!document.contains("url"))
        assertTrue(!document.contains("botId"))
    }
}

private class MapConfigDocument(
    vararg entries: Pair<String, Any?>,
) : MutableConfigDocument {
    private val values: MutableMap<String, Any?> = linkedMapOf(*entries)

    override fun contains(path: String): Boolean = values.containsKey(path)

    override fun get(path: String): Any? = values[path]

    override fun set(path: String, value: Any?) {
        values[path] = value
    }

    override fun remove(path: String): Boolean = values.remove(path) != null

    override fun move(from: String, to: String, overwrite: Boolean): Boolean {
        if (!contains(from)) return false
        if (overwrite || !contains(to)) {
            values[to] = values[from]
        }
        remove(from)
        return true
    }
}
