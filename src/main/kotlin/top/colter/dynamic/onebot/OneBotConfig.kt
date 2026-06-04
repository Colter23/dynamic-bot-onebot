package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFieldVisibility
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

public data class OneBotConfig(
    val mode: OneBotConnectionMode = OneBotConnectionMode.FORWARD_WS,
    val connections: List<OneBotForwardConnectionConfig> = listOf(OneBotForwardConnectionConfig()),
    val host: String = "0.0.0.0",
    val port: Int = 6701,
    val accessToken: String = "",
    val reconnect: Boolean = true,
    val reconnectIntervalSeconds: Int = 5,
    val reconnectMaxTimes: Int = 3,
)

public data class OneBotForwardConnectionConfig(
    val url: String = "ws://127.0.0.1:6700",
    val enabled: Boolean = true,
)

public enum class OneBotConnectionMode {
    FORWARD_WS,
    REVERSE_WS,
}

public object OneBotConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "OneBot 网关",
        description = "OneBot 连接与消息投递配置；账号会在连接后自动识别。",
        fields = listOf(
            ConfigFieldSpec(
                path = "mode",
                label = "连接模式",
                type = ConfigFieldType.SELECT,
                section = "连接",
                options = listOf(
                    top.colter.dynamic.core.config.ConfigFieldOption(OneBotConnectionMode.FORWARD_WS.name, "正向 WebSocket"),
                    top.colter.dynamic.core.config.ConfigFieldOption(OneBotConnectionMode.REVERSE_WS.name, "反向 WebSocket"),
                ),
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "connections",
                label = "正向连接",
                type = ConfigFieldType.JSON,
                section = "连接",
                description = "正向 WebSocket 连接列表；账号 ID 和名称会在连接后由协议自动识别。",
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
                metadata = mapOf(
                    "example" to """
                        [
                          {"url":"ws://127.0.0.1:6700","enabled":true},
                          {"url":"ws://127.0.0.1:6702","enabled":true}
                        ]
                    """.trimIndent(),
                ),
            ),
            ConfigFieldSpec(
                path = "host",
                label = "反向 WebSocket 监听地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "port",
                label = "反向 WebSocket 端口",
                type = ConfigFieldType.NUMBER,
                section = "连接",
                min = 1,
                max = 65_535,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "accessToken",
                label = "访问 Token",
                type = ConfigFieldType.SECRET,
                section = "连接",
                secret = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "重连",
                description = "仅正向 WebSocket 生效；反向 WebSocket 由 OneBot 客户端重新连接。",
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
            ),
            ConfigFieldSpec(
                path = "reconnectIntervalSeconds",
                label = "重连间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                min = 1,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
            ),
            ConfigFieldSpec(
                path = "reconnectMaxTimes",
                label = "最大重连次数",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                description = "0 表示不重连。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
            ),
        ),
    )

    private fun forwardWsOnly(): ConfigFieldVisibility = ConfigFieldVisibility(
        path = "mode",
        values = listOf(OneBotConnectionMode.FORWARD_WS.name),
    )

    private fun reverseWsOnly(): ConfigFieldVisibility = ConfigFieldVisibility(
        path = "mode",
        values = listOf(OneBotConnectionMode.REVERSE_WS.name),
    )

    public fun validate(config: OneBotConfig) {
        require(config.port in 1..65_535) { "反向 WebSocket 端口必须在 1 到 65535 之间" }
        require(config.reconnectIntervalSeconds >= 1) { "重连间隔不能小于 1 秒" }
        require(config.reconnectMaxTimes >= 0) { "最大重连次数不能为负数" }

        when (config.mode) {
            OneBotConnectionMode.FORWARD_WS -> {
                val enabledConnections = config.enabledConnections()
                require(enabledConnections.isNotEmpty()) { "至少需要启用一个正向 WebSocket 连接" }
                enabledConnections.forEachIndexed { index, connection ->
                    require(connection.url.isNotBlank()) { "正向连接[$index].url 不能为空" }
                }
            }
            OneBotConnectionMode.REVERSE_WS -> {
                require(config.host.isNotBlank()) { "反向 WebSocket 监听地址不能为空" }
            }
        }
    }
}

internal fun OneBotConfig.enabledConnections(): List<OneBotForwardConnectionConfig> {
    return connections
        .map { it.copy(url = it.url.trim()) }
        .filter { it.enabled }
}
