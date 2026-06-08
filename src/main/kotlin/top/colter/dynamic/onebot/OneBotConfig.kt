package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFieldVisibility
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

public data class OneBotConfig(
    val mode: OneBotConnectionMode = OneBotConnectionMode.FORWARD_WS,
    val connections: List<OneBotForwardConnectionConfig> = listOf(OneBotForwardConnectionConfig()),
    val host: String = "127.0.0.1",
    val port: Int = 6701,
    val reverseAccessToken: String = "",
    val reconnect: Boolean = true,
    val reconnectIntervalSeconds: Int = 5,
    val reconnectMaxTimes: Int = 3,
    val localImageBase64MaxBytes: Long = 5L * 1024L * 1024L,
)

public data class OneBotForwardConnectionConfig(
    val url: String = "ws://127.0.0.1:6700",
    val accessToken: String = "",
    val name: String = "",
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
                description = "选择 OneBot 客户端和本插件的连接方向。\n正向是插件去连 OneBot 客户端；反向是 OneBot 客户端来连本插件。",
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
                description = "需要主动连接的 OneBot WebSocket 地址。\n可配置多个连接；账号 ID 和名称会在连接成功后自动识别。",
                component = "ONEBOT_CONNECTION_TABLE",
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
                metadata = mapOf(
                    "example" to """
                        [
                          {"name":"本机 NapCat","url":"ws://127.0.0.1:6700","accessToken":"","enabled":true},
                          {"name":"备用连接","url":"ws://127.0.0.1:6702","accessToken":"token","enabled":true}
                        ]
                    """.trimIndent(),
                ),
            ),
            ConfigFieldSpec(
                path = "host",
                label = "反向 WebSocket 监听地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                description = "反向 WebSocket 服务监听的地址。\n本机使用通常填 127.0.0.1；如果填 0.0.0.0，请务必配置 Token。",
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "port",
                label = "反向 WebSocket 端口",
                type = ConfigFieldType.NUMBER,
                section = "连接",
                description = "反向 WebSocket 服务监听的端口。\nOneBot 客户端需要连接到这个端口。",
                min = 1,
                max = 65_535,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "reverseAccessToken",
                label = "反向连接 Token",
                type = ConfigFieldType.SECRET,
                section = "连接",
                description = "反向连接时校验客户端身份的令牌。\n监听非本机地址时必须填写，避免陌生客户端连入。",
                secret = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = reverseWsOnly(),
            ),
            ConfigFieldSpec(
                path = "localImageBase64MaxBytes",
                label = "小图转 Base64 上限（字节）",
                type = ConfigFieldType.NUMBER,
                section = "消息",
                description = "小于这个大小的本地图片会转成 Base64 发送。\n较大的图片会使用 file URI；设为 0 表示全部使用 file URI。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "重连",
                description = "正向连接断开后是否自动重连。\n反向模式由 OneBot 客户端自己重连，这个开关不会生效。",
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
            ),
            ConfigFieldSpec(
                path = "reconnectIntervalSeconds",
                label = "重连间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                description = "正向连接失败后等多久再试。\n只对正向 WebSocket 生效。",
                min = 1,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                visibleWhen = forwardWsOnly(),
            ),
            ConfigFieldSpec(
                path = "reconnectMaxTimes",
                label = "重连次数上限",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                description = "正向连接最多重试几次。\n设为 0 表示不自动重连。",
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
        require(config.localImageBase64MaxBytes >= 0) { "本地图片 Base64 阈值不能为负数" }

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
                require(config.reverseAccessToken.isNotBlank() || config.host.isLocalBindAddress()) {
                    "反向 WebSocket 监听非本地地址时必须配置 Token"
                }
            }
        }
    }
}

private fun String.isLocalBindAddress(): Boolean {
    val value = trim().lowercase()
    return value == "localhost" ||
        value == "::1" ||
        value == "0:0:0:0:0:0:0:1" ||
        value.startsWith("127.")
}

internal fun OneBotConfig.enabledConnections(): List<OneBotForwardConnectionConfig> {
    return connections
        .map { it.copy(url = it.url.trim(), accessToken = it.accessToken.trim(), name = it.name.trim()) }
        .filter { it.enabled }
}
