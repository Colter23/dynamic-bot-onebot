package top.colter.dynamic.onebot

import cn.evole.onebot.client.OneBotClient
import cn.evole.onebot.client.core.Bot
import cn.evole.onebot.client.core.BotConfig
import com.google.gson.JsonArray
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

    override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        if (connections.isNotEmpty()) return

        incomingMessageHandler = onIncomingMessage
        config.enabledConnections().forEachIndexed { index, connection ->
            val runtimeConnection = ForwardConnection(
                connectionId = "forward-$index",
                url = connection.url,
                accessToken = connection.accessToken,
                name = connection.name,
                mediaDeliveryProfileId = connection.mediaDeliveryProfileId
                    .ifBlank { config.mediaDeliveryProfileId.trim() },
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
        val active = connections.toList()
        connections.clear()
        active.forEach { connection ->
            withContext(Dispatchers.IO) {
                runCatching {
                    connection.client?.close()
                }.onFailure {
                    logger.warn(it) { "OneBot 正向连接关闭失败：connectionId=${connection.connectionId}，name=${connection.name.ifBlank { "-" }}，url=${connection.url}" }
                }
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
        val current = account
        val currentClient = client
        if (currentClient == null || !currentClient.isOpenForAction()) {
            recordUnavailable()
            markUnavailable("OneBot 正向连接未连接", warnWhenAccountUnknown = false)
            rebuildClientIfDue()
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
                mediaDeliveryProfileId = mediaDeliveryProfileId,
            )
        }.onSuccess {
            val previous = account
            account = it
            unavailableSinceAt = null
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
        val handler = incomingMessageHandler ?: return
        if (!config.reconnect) return

        synchronized(rebuildLock) {
            val now = nowMillis()
            val unavailableAt = unavailableSinceAt ?: return
            if (now - unavailableAt < reconnectIntervalMillis()) return
            if (now - lastClientRebuildAt < reconnectIntervalMillis()) return

            lastClientRebuildAt = now
            logger.warn {
                "OneBot 正向连接不可用，准备重新发起连接：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url"
            }
            val previousClient = client
            runCatching { previousClient?.close() }
                .onFailure {
                    logger.debug(it) {
                        "OneBot 正向连接旧客户端关闭失败，继续重建：connectionId=$connectionId，url=$url"
                    }
                }
            client = runCatching {
                openClient(handler)
            }.onSuccess {
                logger.info {
                    "OneBot 正向连接已重新发起：connectionId=$connectionId，name=${name.ifBlank { "-" }}，url=$url"
                }
            }.onFailure {
                markUnavailable(it.message ?: "OneBot 正向连接客户端重建失败", it)
            }.getOrNull()
        }
    }

    private fun ForwardConnection.openClient(
        onIncomingMessage: (OneBotIncomingMessage) -> Unit,
    ): OneBotClient {
        return OneBotClient.create(
            BotConfig(url, accessToken).apply {
                isReconnect = false
                reconnectInterval = config.reconnectIntervalSeconds
                reconnectMaxTimes = 0
            },
            OneBotIncomingListener(
                onIncomingMessage = onIncomingMessage,
                botAccountIdProvider = {
                    knownAccountId()
                },
            ),
        ).open()
    }

    private fun ForwardConnection.recordUnavailable() {
        if (unavailableSinceAt == null) {
            unavailableSinceAt = nowMillis()
        }
    }

    private fun reconnectIntervalMillis(): Long {
        return config.reconnectIntervalSeconds.coerceAtLeast(1) * MILLIS_PER_SECOND
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
            mediaDeliveryProfileId = mediaDeliveryProfileId,
        )).copy(
            state = MessageSinkRouteState.UNAVAILABLE,
            mediaDeliveryProfileId = mediaDeliveryProfileId,
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

    private data class ForwardConnection(
        val connectionId: String,
        val url: String,
        val accessToken: String,
        val name: String,
        val mediaDeliveryProfileId: String,
        val rebuildLock: Any = Any(),
        @Volatile
        var client: OneBotClient? = null,
        @Volatile
        var account: OneBotRuntimeAccount? = null,
        @Volatile
        var unavailableSinceAt: Long? = null,
        @Volatile
        var lastClientRebuildAt: Long = 0L,
        @Volatile
        var lastIdentifyFailureLogAt: Long = 0L,
        @Volatile
        var lastIdentifyFailureKey: String? = null,
    )

    private companion object {
        private const val IDENTIFY_FAILURE_WARN_INTERVAL_MS: Long = 5 * 60 * 1000L
        private const val MILLIS_PER_SECOND: Long = 1000L
    }
}
