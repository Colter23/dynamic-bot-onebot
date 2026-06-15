package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.connection.WSClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import cn.evole.onebot.sdk.websocket.handshake.ServerHandshake
import com.google.gson.JsonArray
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<ForwardWsOneBotGateway>()

internal class ForwardWsOneBotGateway(
    private val config: OneBotConfig,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : OneBotGateway {

    private val connections: MutableList<ForwardConnection> = mutableListOf()
    @Volatile
    private var incomingMessageHandler: ((OneBotIncomingMessage) -> Unit)? = null
    @Volatile
    private var closing: Boolean = false

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (connections.isNotEmpty()) return

        closing = false
        incomingMessageHandler = onIncomingMessage
        config.enabledConnections().forEachIndexed { index, connection ->
            val runtimeConnection = ForwardConnection(
                connectionId = "forward-$index",
                url = connection.url,
                accessToken = connection.accessToken,
                name = connection.name,
            )
            runtimeConnection.client = runtimeConnection.openClient(onIncomingMessage)
            connections += runtimeConnection
        }
    }

    override suspend fun availableAccounts(): List<OneBotRuntimeAccount> = withContext(Dispatchers.IO) {
        connections
            .mapNotNull { connection -> connection.cachedOrRefreshRuntimeAccount() }
            .distinctBy { it.accountId }
            .sortedBy { it.accountId }
    }

    override suspend fun implementationInfo(accountId: String): OneBotImplementationInfo {
        return withContext(Dispatchers.IO) {
            val info = requireBot(accountId).getVersionInfo().requireDataOk("get_version_info")
            OneBotImplementationInfo(
                appName = info.appName.orEmpty(),
                appVersion = info.appVersion.orEmpty().ifBlank { info.version.orEmpty() },
                protocolVersion = info.protocolVersion.orEmpty(),
            )
        }
    }

    override suspend fun connectionHints(
        accountId: String,
        webAdminHost: String,
        webAdminPort: Int,
    ): OneBotConnectionHints {
        return withContext(Dispatchers.IO) {
            val connection = connections.firstOrNull { it.knownAccountId() == accountId }
            val sameHostLikely = oneBotSameHostLikely(oneBotWebSocketHost(connection?.url.orEmpty()))
            OneBotConnectionHints(
                sameHostLikely = sameHostLikely,
                signedUrlBaseCandidates = oneBotSignedUrlBaseCandidates(
                    webAdminHost = webAdminHost,
                    webAdminPort = webAdminPort,
                    sameHostLikely = sameHostLikely,
                ),
            )
        }
    }

    override suspend fun probeDownload(accountId: String, uri: String): OneBotDownloadProbeResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val result = requireBot(accountId).downloadFile(uri).requireDataOk("download_file")
                OneBotDownloadProbeResult(
                    available = !result.file.isNullOrBlank(),
                    reason = result.file.orEmpty(),
                )
            }.getOrElse {
                OneBotDownloadProbeResult(available = false, reason = it.message.orEmpty())
            }
        }
    }

    override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendPrivateMsg(userId, message, false)
            action.requireSendAccepted("send_private_msg", userId)
        }
    }

    override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendGroupMsg(groupId, message, false)
            action.requireSendAccepted("send_group_msg", groupId)
        }
    }

    override suspend fun sendPrivateForwardMessage(
        accountId: String,
        userId: Long,
        messages: List<Map<String, Any>>,
    ): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendPrivateForwardMsgRaw(userId, messages)
            action.requireSendAccepted("send_private_forward_msg", userId)
        }
    }

    override suspend fun sendGroupForwardMessage(
        accountId: String,
        groupId: Long,
        messages: List<Map<String, Any>>,
    ): String? {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).sendGroupForwardMsgRaw(groupId, messages)
            action.requireSendAccepted("send_group_forward_msg", groupId)
        }
    }

    override suspend fun recallMessage(accountId: String, messageId: String) {
        withContext(Dispatchers.IO) {
            val id = parseRecallMessageId(messageId)
            requireBot(accountId).deleteMsg(id).requireActionAccepted("delete_msg")
        }
    }

    override suspend fun listGroups(accountId: String): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).getGroupList()
            action.requireQueryOk("get_group_list").map { group ->
                val id = group.groupId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = group.groupName?.takeIf { it.isNotBlank() } ?: id,
                    accountId = accountId,
                )
            }
        }
    }

    override suspend fun listFriends(accountId: String): List<OneBotTargetCandidate> {
        return withContext(Dispatchers.IO) {
            val action = requireBot(accountId).getFriendList()
            action.requireQueryOk("get_friend_list").map { friend ->
                val id = friend.userId.toString()
                OneBotTargetCandidate(
                    id = id,
                    name = friend.remark?.takeIf { it.isNotBlank() }
                        ?: friend.nickname?.takeIf { it.isNotBlank() }
                        ?: id,
                    accountId = accountId,
                )
            }
        }
    }

    override suspend fun close() {
        closing = true
        incomingMessageHandler = null
        val active = connections.toList()
        connections.clear()
        active.forEach { connection ->
            val client = connection.client
            connection.client = null
            withContext(Dispatchers.IO) {
                closeClientQuickly(connection, client)
            }
        }
    }

    private fun requireBot(accountId: String): Bot {
        return connections.firstNotNullOfOrNull { connection ->
            val client = connection.client ?: return@firstNotNullOfOrNull null
            client
                .takeIf { connection.knownAccountId() == accountId && client.isOpenForAction() }
                ?.bot
        } ?: error("OneBot 正向连接尚未就绪：accountId=$accountId")
    }

    private fun ForwardConnection.knownAccountId(): String? {
        return account?.accountId ?: client?.runtimeAccountId()
    }

    private fun ForwardConnection.cachedOrRefreshRuntimeAccount(): OneBotRuntimeAccount? {
        if (closing) return account
        val current = account
        val currentClient = client
        if (currentClient == null || !currentClient.isOpenForAction()) {
            recordUnavailable()
            val reason = reconnectSuspendedReason
                ?: lastConnectionFailureReason
                ?: "OneBot 正向连接未连接"
            markUnavailable(reason, warnWhenAccountUnknown = false)
            if (reconnectSuspendedReason == null) {
                rebuildClientIfDue()
            }
            return account
        }
        if (current != null && current.state == MessageSinkRouteState.READY) {
            return current
        }
        return refreshRuntimeAccount(currentClient)
    }

    private fun ForwardConnection.refreshRuntimeAccount(client: OneBotClient): OneBotRuntimeAccount? {
        return runCatching {
            val bot = client.bot ?: error("OneBot Bot 尚未初始化")
            val info = bot.getLoginInfo().requireDataOk("get_login_info")
            val accountId = info.userId.takeIf { it > 0 }?.toString()
                ?: error("OneBot 登录信息缺少有效 user_id")
            OneBotRuntimeAccount(
                accountId = accountId,
                name = info.nickname?.takeIf { it.isNotBlank() } ?: "QQ机器人 $accountId",
                state = MessageSinkRouteState.READY,
            )
        }.onSuccess {
            val previous = account
            account = it
            resetReconnectBackoff()
            if (previous?.accountId != it.accountId) {
                logger.info { "OneBot 正向连接账号已识别：connectionId=$connectionId，name=${name.ifBlank { "-" }}，accountId=${it.accountId}，accountName=${it.name}" }
            } else if (previous.state != MessageSinkRouteState.READY) {
                logger.info { "OneBot 正向连接账号已恢复：connectionId=$connectionId，accountId=${it.accountId}，accountName=${it.name}" }
            }
        }.onFailure {
            markUnavailable(it.message ?: "OneBot 登录信息读取失败", it)
        }.getOrNull() ?: account
    }

    private fun ForwardConnection.rebuildClientIfDue() {
        if (closing) return
        if (reconnectSuspendedReason != null) return
        val handler = incomingMessageHandler ?: return
        if (!config.reconnect) return

        synchronized(rebuildLock) {
            if (closing) return
            if (reconnectSuspendedReason != null) return
            val now = nowMillis()
            recordUnavailable(now)
            val nextReconnectAt = nextClientRebuildAt
            if (nextReconnectAt > 0 && now < nextReconnectAt) return

            val attempt = reconnectAttempts + 1
            logReconnectAttempt(attempt)
            val previousClient = client
            closeClientQuickly(this, previousClient)
            client = runCatching {
                openClient(handler)
            }.onSuccess {
                logger.info {
                    "OneBot 正向连接已重新发起：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，attempt=$attempt"
                }
            }.onFailure {
                markUnavailable(it.message ?: "OneBot 正向连接客户端重建失败", it)
            }.getOrNull()
            reconnectAttempts = attempt
            scheduleNextReconnect(now)
        }
    }

    private fun ForwardConnection.openClient(
        onIncomingMessage: (OneBotIncomingMessage) -> Unit,
    ): OneBotClient {
        return OneBotClient.create(
            BotConfig(url, accessToken).apply {
                isReconnect = false
                reconnectInterval = DEFAULT_SDK_RECONNECT_INTERVAL_SECONDS
                reconnectMaxTimes = 0
            },
            OneBotIncomingListener(
                onIncomingMessage = onIncomingMessage,
                botAccountIdProvider = {
                    knownAccountId()
                },
            ),
        ).openObserved(this)
    }

    private fun OneBotClient.openObserved(
        connection: ForwardConnection,
    ): OneBotClient {
        val uri = runCatching {
            URI.create(connection.url)
        }.getOrElse {
            connection.recordClientOpenFailure(it)
            return this
        }
        val ws = ObservedForwardWsClient(
            client = this,
            uri = uri,
            accessToken = connection.accessToken,
            onOpenObserved = {
                connection.recordWebSocketOpen()
            },
            onCloseObserved = { code, reason, remote ->
                connection.recordWebSocketClose(code, reason, remote)
            },
            onErrorObserved = { error ->
                connection.recordWebSocketError(error)
            },
        )
        connection.webSocketOpened = false
        setPrivateField("ws", ws)
        wsPool.execute {
            runCatching {
                ws.connect()
                setPrivateField("bot", ws.createBot())
            }.onFailure {
                connection.recordClientOpenFailure(it)
            }
        }
        return this
    }

    private fun ForwardConnection.recordUnavailable(now: Long = nowMillis()) {
        if (unavailableSinceAt == null) {
            unavailableSinceAt = now
            reconnectAttempts = 0
            scheduleNextReconnect(now)
        }
    }

    private fun ForwardConnection.scheduleNextReconnect(now: Long) {
        nextClientRebuildAt = now + reconnectDelayMillis(reconnectAttempts)
    }

    private fun ForwardConnection.resetReconnectBackoff() {
        unavailableSinceAt = null
        reconnectAttempts = 0
        nextClientRebuildAt = 0L
        lastConnectionFailureReason = null
        reconnectSuspendedReason = null
    }

    private fun reconnectDelayMillis(completedAttempts: Int): Long {
        return RECONNECT_BACKOFF_MILLIS[completedAttempts.coerceIn(0, RECONNECT_BACKOFF_MILLIS.lastIndex)]
    }

    private fun ForwardConnection.logReconnectAttempt(attempt: Int) {
        val nextDelay = reconnectDelayMillis(attempt)
        val message = "OneBot 正向连接不可用，准备自动重连：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，attempt=$attempt，若仍失败下次间隔=${nextDelay.formatDelay()}"
        if (attempt == 1) {
            logger.warn { message }
        } else {
            logger.info { message }
        }
    }

    private fun ForwardConnection.recordWebSocketOpen() {
        webSocketOpened = true
        lastConnectionFailureReason = null
        reconnectSuspendedReason = null
    }

    private fun ForwardConnection.recordWebSocketClose(code: Int, reason: String, remote: Boolean): Boolean {
        val authFailure = oneBotForwardAuthenticationFailureReason(
            code = code,
            reason = reason,
            beforeOpen = !webSocketOpened,
        )
        if (authFailure != null) {
            suspendReconnectForAuthenticationFailure(authFailure)
            return true
        }

        if (reason.isNotBlank()) {
            lastConnectionFailureReason = "OneBot 正向连接已关闭：code=$code，reason=$reason，remote=$remote"
        }
        return false
    }

    private fun ForwardConnection.recordWebSocketError(error: Exception): Boolean {
        val authFailure = oneBotForwardAuthenticationFailureReason(error)
        if (authFailure != null) {
            suspendReconnectForAuthenticationFailure(authFailure)
            return true
        }

        lastConnectionFailureReason = error.message
            ?.takeIf { it.isNotBlank() }
            ?.let { "OneBot 正向连接错误：$it" }
            ?: "OneBot 正向连接错误：${error.javaClass.simpleName}"
        return false
    }

    private fun ForwardConnection.recordClientOpenFailure(error: Throwable) {
        val authFailure = oneBotForwardAuthenticationFailureReason(error)
        if (authFailure != null) {
            suspendReconnectForAuthenticationFailure(authFailure)
            return
        }
        lastConnectionFailureReason = error.message
            ?.takeIf { it.isNotBlank() }
            ?.let { "OneBot 正向连接启动失败：$it" }
            ?: "OneBot 正向连接启动失败：${error.javaClass.simpleName}"
        markUnavailable(lastConnectionFailureReason.orEmpty(), error, warnWhenAccountUnknown = true)
    }

    private fun ForwardConnection.suspendReconnectForAuthenticationFailure(reason: String) {
        val message = "OneBot 正向连接鉴权失败：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，原因=$reason。请检查这里填写的 Token 是否与 OneBot 客户端 access_token 一致；如果客户端未启用 Token，请将这里留空。已暂停该连接的自动重连，修改配置并重启插件后会重新连接。"
        if (reconnectSuspendedReason != message) {
            logger.warn { message }
        }
        reconnectSuspendedReason = message
        lastConnectionFailureReason = message
        nextClientRebuildAt = Long.MAX_VALUE
    }

    private fun Long.formatDelay(): String {
        val seconds = this / MILLIS_PER_SECOND
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3_600 -> "${seconds / 60}分钟"
            else -> "${seconds / 3_600}小时"
        }
    }

    private fun ForwardConnection.markUnavailable(
        reason: String,
        error: Throwable? = null,
        warnWhenAccountUnknown: Boolean = true,
    ) {
        val accountId = account?.accountId ?: client?.runtimeAccountId()
        if (accountId == null) {
            logAccountIdentifyFailure(reason, error, warnWhenAccountUnknown)
            return
        }

        val previous = account
        account = (previous ?: OneBotRuntimeAccount(
            accountId = accountId,
        )).copy(
            state = MessageSinkRouteState.UNAVAILABLE,
        )
        if (previous?.state != MessageSinkRouteState.UNAVAILABLE) {
            if (error == null) {
                logger.warn {
                    "OneBot 正向连接账号不可用：connectionId=$connectionId，name=${name.ifBlank { "-" }}，accountId=$accountId，url=$url，原因=$reason"
                }
            } else {
                logger.warn(error) {
                    "OneBot 正向连接账号不可用：connectionId=$connectionId，name=${name.ifBlank { "-" }}，accountId=$accountId，url=$url，原因=$reason"
                }
            }
        } else {
            if (error == null) {
                logger.debug {
                    "OneBot 正向连接账号仍不可用：connectionId=$connectionId，accountId=$accountId，原因=$reason"
                }
            } else {
                logger.debug(error) {
                    "OneBot 正向连接账号仍不可用：connectionId=$connectionId，accountId=$accountId，原因=$reason"
                }
            }
        }
    }

    private fun ForwardConnection.logAccountIdentifyFailure(reason: String, error: Throwable?, warn: Boolean) {
        val now = System.currentTimeMillis()
        val failureKey = reason.ifBlank { error?.javaClass?.name ?: "unknown" }
        val shouldWarn = warn && (lastIdentifyFailureKey != failureKey ||
            now - lastIdentifyFailureLogAt >= IDENTIFY_FAILURE_WARN_INTERVAL_MS)
        lastIdentifyFailureKey = failureKey
        lastIdentifyFailureLogAt = now
        if (shouldWarn) {
            if (error == null) {
                logger.warn {
                    "OneBot 正向连接账号识别失败：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，原因=$reason"
                }
            } else {
                logger.warn(error) {
                    "OneBot 正向连接账号识别失败：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，原因=$reason"
                }
            }
        } else {
            if (error == null) {
                logger.debug {
                    "OneBot 正向连接账号暂未识别：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，原因=$reason"
                }
            } else {
                logger.debug(error) {
                    "OneBot 正向连接账号暂未识别：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url，原因=$reason"
                }
            }
        }
    }

    private fun OneBotClient.runtimeAccountId(): String? {
        return bot?.selfId?.takeIf { it > 0 }?.toString()
    }

    private fun OneBotClient.isOpenForAction(): Boolean {
        return runCatching { ws?.isOpen == true }.getOrDefault(false)
    }

    private fun Any.setPrivateField(name: String, value: Any?) {
        val field = generateSequence(javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?: error("OneBot Client 字段不存在：$name")
        field.isAccessible = true
        field.set(this, value)
    }

    private fun closeClientQuickly(connection: ForwardConnection, client: OneBotClient?) {
        if (client == null) return
        runCatching {
            client.ws?.stopWithoutReconnect(1000, "shutdown")
        }.onFailure {
            logger.debug(it) {
                "OneBot 正向连接 WebSocket 快速关闭失败：connectionId=${connection.connectionId}，url=${connection.url}"
            }
        }
        runCatching {
            client.eventExecutor.shutdownNow()
        }.onFailure {
            logger.debug(it) {
                "OneBot 正向连接事件线程池关闭失败：connectionId=${connection.connectionId}，url=${connection.url}"
            }
        }
        runCatching {
            client.wsPool.shutdownNow()
        }.onFailure {
            logger.debug(it) {
                "OneBot 正向连接 WebSocket 线程池关闭失败：connectionId=${connection.connectionId}，url=${connection.url}"
            }
        }
    }

    private data class ForwardConnection(
        val connectionId: String,
        val url: String,
        val accessToken: String,
        val name: String,
        val rebuildLock: Any = Any(),
        @Volatile
        var client: OneBotClient? = null,
        @Volatile
        var account: OneBotRuntimeAccount? = null,
        @Volatile
        var unavailableSinceAt: Long? = null,
        @Volatile
        var reconnectAttempts: Int = 0,
        @Volatile
        var nextClientRebuildAt: Long = 0L,
        @Volatile
        var lastIdentifyFailureLogAt: Long = 0L,
        @Volatile
        var lastIdentifyFailureKey: String? = null,
        @Volatile
        var lastConnectionFailureReason: String? = null,
        @Volatile
        var reconnectSuspendedReason: String? = null,
        @Volatile
        var webSocketOpened: Boolean = false,
    )

    private class ObservedForwardWsClient(
        client: OneBotClient,
        uri: URI,
        accessToken: String,
        private val onOpenObserved: () -> Unit,
        private val onCloseObserved: (code: Int, reason: String, remote: Boolean) -> Boolean,
        private val onErrorObserved: (Exception) -> Boolean,
    ) : WSClient(client, uri) {

        init {
            val trimmedToken = accessToken.trim()
            if (trimmedToken.isNotEmpty()) {
                removeHeader("Authorization")
                addHeader("Authorization", "Bearer $trimmedToken")
            }
        }

        override fun onOpen(handshakedata: ServerHandshake) {
            onOpenObserved()
            super.onOpen(handshakedata)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            if (!onCloseObserved(code, reason, remote)) {
                super.onClose(code, reason, remote)
            }
        }

        override fun onError(ex: Exception) {
            if (!onErrorObserved(ex)) {
                super.onError(ex)
            }
        }
    }

    private companion object {
        private const val DEFAULT_SDK_RECONNECT_INTERVAL_SECONDS: Int = 5
        private const val IDENTIFY_FAILURE_WARN_INTERVAL_MS: Long = 5 * 60 * 1000L
        private const val MILLIS_PER_SECOND: Long = 1000L
        private val RECONNECT_BACKOFF_MILLIS: LongArray = longArrayOf(
            5 * MILLIS_PER_SECOND,
            10 * MILLIS_PER_SECOND,
            30 * MILLIS_PER_SECOND,
            60 * MILLIS_PER_SECOND,
            5 * 60 * MILLIS_PER_SECOND,
            10 * 60 * MILLIS_PER_SECOND,
            30 * 60 * MILLIS_PER_SECOND,
            60 * 60 * MILLIS_PER_SECOND,
        )
    }
}

internal fun oneBotForwardAuthenticationFailureReason(error: Throwable): String? {
    val text = error.diagnosticText()
    return oneBotForwardAuthenticationFailureReason(text)
}

internal fun oneBotForwardAuthenticationFailureReason(
    code: Int,
    reason: String,
    beforeOpen: Boolean = true,
): String? {
    val text = "code=$code reason=$reason"
    if (reason.isBlank()) {
        return if (beforeOpen && code in AUTH_FAILURE_CLOSE_CODES) {
            "WebSocket 连接在鉴权阶段被服务端关闭：code=$code"
        } else {
            null
        }
    }
    return oneBotForwardAuthenticationFailureReason(text)
}

private fun oneBotForwardAuthenticationFailureReason(text: String): String? {
    val normalized = text.lowercase()
    val matched = AUTH_FAILURE_KEYWORDS.any { it in normalized } ||
        HTTP_AUTH_STATUS_PATTERN.containsMatchIn(normalized)
    if (!matched) return null

    return text
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "服务端拒绝 WebSocket 鉴权" }
        .take(MAX_FAILURE_REASON_LENGTH)
}

private fun Throwable.diagnosticText(): String {
    return generateSequence(this) { it.cause }
        .joinToString(" | ") { throwable ->
            listOfNotNull(
                throwable.javaClass.simpleName,
                throwable.message?.takeIf { it.isNotBlank() },
                throwable.localizedMessage?.takeIf { it.isNotBlank() && it != throwable.message },
            ).joinToString(": ")
        }
}

private val AUTH_FAILURE_KEYWORDS: List<String> = listOf(
    "unauthorized",
    "forbidden",
    "authentication",
    "authorization",
    "access token",
    "invalid token",
    "token invalid",
    "token",
    "鉴权",
    "认证",
    "未授权",
    "无权限",
    "令牌",
)

private val HTTP_AUTH_STATUS_PATTERN: Regex = Regex("""\b(?:401|403)\b""")
private val AUTH_FAILURE_CLOSE_CODES: Set<Int> = setOf(1008)
private const val MAX_FAILURE_REASON_LENGTH: Int = 180
