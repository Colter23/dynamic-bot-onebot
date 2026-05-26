package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec

public data class OneBotConfig(
    val mode: OneBotConnectionMode = OneBotConnectionMode.FORWARD_WS,
    val url: String = "ws://127.0.0.1:6700",
    val host: String = "0.0.0.0",
    val port: Int = 6701,
    val accessToken: String = "",
    val botId: Long = 0,
    val reconnect: Boolean = true,
    val reconnectIntervalSeconds: Int = 5,
    val reconnectMaxTimes: Int = 0,
)

public enum class OneBotConnectionMode {
    FORWARD_WS,
    REVERSE_WS,
}

public object OneBotConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "OneBot 网关",
        description = "OneBot 连接与消息网关设置。",
        fields = listOf(
            ConfigFieldSpec(
                path = "mode",
                label = "连接模式",
                type = ConfigFieldType.SELECT,
                section = "连接",
                options = listOf(
                    ConfigFieldOption(OneBotConnectionMode.FORWARD_WS.name, "正向 WebSocket"),
                    ConfigFieldOption(OneBotConnectionMode.REVERSE_WS.name, "反向 WebSocket"),
                ),
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "url",
                label = "正向 WebSocket 地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "host",
                label = "反向 WebSocket 监听地址",
                type = ConfigFieldType.TEXT,
                section = "连接",
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "port",
                label = "反向 WebSocket 端口",
                type = ConfigFieldType.NUMBER,
                section = "连接",
                min = 1,
                max = 65_535,
                restartRequired = true,
                restartTarget = "OneBot 插件",
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
                path = "botId",
                label = "机器人 ID",
                type = ConfigFieldType.NUMBER,
                section = "连接",
                min = 0,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnect",
                label = "自动重连",
                type = ConfigFieldType.BOOLEAN,
                section = "重连",
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnectIntervalSeconds",
                label = "重连间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                min = 1,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnectMaxTimes",
                label = "最大重连次数",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                min = 0,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
        ),
    )

    public fun validate(config: OneBotConfig) {
        require(config.port in 1..65_535) { "port must be between 1 and 65535" }
        require(config.botId >= 0) { "botId must not be negative" }
        require(config.reconnectIntervalSeconds >= 1) { "reconnectIntervalSeconds must be at least 1" }
        require(config.reconnectMaxTimes >= 0) { "reconnectMaxTimes must not be negative" }
        when (config.mode) {
            OneBotConnectionMode.FORWARD_WS -> require(config.url.isNotBlank()) {
                "url must not be blank in FORWARD_WS mode"
            }
            OneBotConnectionMode.REVERSE_WS -> require(config.host.isNotBlank()) {
                "host must not be blank in REVERSE_WS mode"
            }
        }
    }
}
