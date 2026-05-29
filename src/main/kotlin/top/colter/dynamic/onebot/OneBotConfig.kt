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
    val reconnectMaxTimes: Int = 3,
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
                description = "仅正向 WebSocket 生效；反向 WebSocket 由 OneBot 客户端重新连接。",
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnectIntervalSeconds",
                label = "重连间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                description = "仅正向 WebSocket 生效。",
                min = 1,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
            ConfigFieldSpec(
                path = "reconnectMaxTimes",
                label = "最大重连次数",
                type = ConfigFieldType.NUMBER,
                section = "重连",
                description = "仅正向 WebSocket 生效；0 表示不重连。",
                min = 0,
                restartRequired = true,
                restartTarget = "OneBot 插件",
            ),
        ),
    )

    public fun validate(config: OneBotConfig) {
        require(config.port in 1..65_535) { "反向 WebSocket 端口必须在 1 到 65535 之间" }
        require(config.botId >= 0) { "机器人 ID 不能为负数" }
        require(config.reconnectIntervalSeconds >= 1) { "重连间隔不能小于 1 秒" }
        require(config.reconnectMaxTimes >= 0) { "最大重连次数不能为负数" }
        when (config.mode) {
            OneBotConnectionMode.FORWARD_WS -> require(config.url.isNotBlank()) {
                "正向 WebSocket 地址不能为空"
            }
            OneBotConnectionMode.REVERSE_WS -> require(config.host.isNotBlank()) {
                "反向 WebSocket 监听地址不能为空"
            }
        }
    }
}
