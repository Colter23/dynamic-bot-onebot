package top.colter.dynamic.onebot

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
