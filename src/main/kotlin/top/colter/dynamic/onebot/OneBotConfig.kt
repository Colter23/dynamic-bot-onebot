package top.colter.dynamic.onebot

import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFieldVisibility
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.plugin.MessageSinkAccountRole
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy

public data class OneBotConfig(
    val mode: OneBotConnectionMode = OneBotConnectionMode.FORWARD_WS,
    val accounts: List<OneBotAccountConfig> = listOf(OneBotAccountConfig()),
    val routingPolicy: MessageSinkRoutingPolicy = MessageSinkRoutingPolicy(),
    val host: String = "0.0.0.0",
    val port: Int = 6701,
    val accessToken: String = "",
    val reconnect: Boolean = true,
    val reconnectIntervalSeconds: Int = 5,
    val reconnectMaxTimes: Int = 3,
)

public data class OneBotAccountConfig(
    val accountId: String = "default",
    val name: String = "",
    val enabled: Boolean = true,
    val role: MessageSinkAccountRole = MessageSinkAccountRole.PRIMARY,
    val url: String = "ws://127.0.0.1:6700",
) {
    public val displayName: String
        get() = name.trim().takeIf { it.isNotBlank() } ?: accountId
}

public enum class OneBotConnectionMode {
    FORWARD_WS,
    REVERSE_WS,
}

public object OneBotConfigForm {
    public val migrations: List<ConfigMigration> = listOf(
        ConfigMigration(
            id = "onebot-single-account-to-accounts",
            description = "迁移旧版 url/botId 到 accounts 多账号配置",
        ) {
            if (!contains("accounts")) {
                val legacyUrl = (get("url") as? String)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: OneBotAccountConfig().url
                val legacyBotId = get("botId").toPositiveLongOrNull()
                val accountId = legacyBotId?.toString() ?: OneBotAccountConfig().accountId
                set(
                    "accounts",
                    listOf(
                        mapOf(
                            "accountId" to accountId,
                            "name" to if (legacyBotId == null) "默认机器人" else "机器人 $accountId",
                            "enabled" to true,
                            "role" to MessageSinkAccountRole.PRIMARY.name,
                            "url" to legacyUrl,
                        ),
                    ),
                )
            }
            remove("url")
            remove("botId")
        },
    )

    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "OneBot 网关",
        description = "OneBot 连接、账号路由与消息投递配置。",
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
                path = "accounts",
                label = "机器人账号",
                type = ConfigFieldType.JSON,
                section = "账号",
                description = "账号数组。accountId 建议填写机器人 QQ 号；正向 WebSocket 每个账号需要 url；role 可填 PRIMARY 或 BACKUP。",
                required = true,
                restartRequired = true,
                restartTarget = "OneBot 插件",
                metadata = mapOf(
                    "example" to """
                        [
                          {"accountId":"123456","name":"主机器人","enabled":true,"role":"PRIMARY","url":"ws://127.0.0.1:6700"},
                          {"accountId":"234567","name":"备用机器人","enabled":true,"role":"BACKUP","url":"ws://127.0.0.1:6702"}
                        ]
                    """.trimIndent(),
                ),
            ),
            ConfigFieldSpec(
                path = "routingPolicy.strategy",
                label = "路由策略",
                type = ConfigFieldType.SELECT,
                section = "账号",
                options = listOf(
                    ConfigFieldOption(MessageSinkRoutingStrategy.ROUND_ROBIN.name, "轮询分担"),
                    ConfigFieldOption(MessageSinkRoutingStrategy.PRIMARY_BACKUP.name, "主备切换"),
                ),
                required = true,
                restartRequired = false,
            ),
            ConfigFieldSpec(
                path = "routingPolicy.failureCooldownSeconds",
                label = "失败冷却秒数",
                type = ConfigFieldType.NUMBER,
                section = "账号",
                description = "账号发送失败后，主项目在这段时间内会先跳过该账号。",
                min = 1,
                numberKind = ConfigNumberKind.INTEGER,
                restartRequired = false,
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
                description = "仅正向 WebSocket 生效。",
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
                description = "仅正向 WebSocket 生效；0 表示不重连。",
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
        require(config.routingPolicy.failureCooldownSeconds >= 1) { "失败冷却秒数不能小于 1" }
        val enabledAccounts = config.accounts.filter { it.enabled }
        require(enabledAccounts.isNotEmpty()) { "至少需要启用一个 OneBot 账号" }
        val accountIds = config.accounts.map { it.accountId.trim() }
        require(accountIds.none { it.isBlank() }) { "OneBot 账号 ID 不能为空" }
        val duplicated = accountIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) { "OneBot 账号 ID 不能重复：${duplicated.joinToString(",")}" }

        when (config.mode) {
            OneBotConnectionMode.FORWARD_WS -> {
                enabledAccounts.forEach { account ->
                    require(account.url.isNotBlank()) { "正向 WebSocket 账号 ${account.accountId} 的 url 不能为空" }
                }
            }
            OneBotConnectionMode.REVERSE_WS -> {
                require(config.host.isNotBlank()) { "反向 WebSocket 监听地址不能为空" }
                enabledAccounts.forEach { account ->
                    require(account.accountId.toLongOrNull() != null) {
                        "反向 WebSocket 账号 ${account.accountId} 需要使用机器人 QQ 号作为 accountId"
                    }
                }
            }
        }
    }
}

internal fun OneBotConfig.enabledAccounts(): List<OneBotAccountConfig> {
    return normalizedAccounts()
        .filter { it.enabled }
}

internal fun OneBotConfig.normalizedAccounts(): List<OneBotAccountConfig> {
    return accounts
        .map { it.copy(accountId = it.accountId.trim(), name = it.name.trim(), url = it.url.trim()) }
}

private fun Any?.toPositiveLongOrNull(): Long? {
    val value = when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull()
        else -> null
    } ?: return null
    return value.takeIf { it > 0 }
}
