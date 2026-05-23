package top.colter.dynamic.onebot

public data class OneBotConfig(
    val url: String = "ws://127.0.0.1:6700",
    val accessToken: String = "",
    val botId: Long = 0,
    val reconnect: Boolean = true,
    val reconnectInterval: Int = 5,
    val retryTimes: Int = 0,
)
