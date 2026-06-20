package top.colter.dynamic.onebot

import com.google.gson.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdviceRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryConfidence
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryMethod
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeRequest
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryProbeStatus
import top.colter.dynamic.core.plugin.MessageSinkRouteState

class OneBotGatewayPluginRouteStateTest {
    @Test
    fun `list routes should keep runtime account unavailable state`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        plugin.setPrivate("gateway", FakeGateway(MessageSinkRouteState.UNAVAILABLE))
        plugin.setPrivate("running", true)

        val route = plugin.listMessageSinkRoutes().single()

        assertEquals(MessageSinkRouteState.UNAVAILABLE, route.state)
        assertEquals("42", route.accountId)
    }

    @Test
    fun `message send should reject invalid target id without sending`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(MessageSinkRouteState.READY)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val target = TargetAddress.of("qq", TargetKind.GROUP, "bad")
        val result = plugin.sendMessage(
            request = MessageSendRequest(
                target = target,
                message = Message(
                    id = "message-1",
                    time = 1,
                    targets = listOf(target),
                    batches = listOf(MessageBatch(listOf(MessageContent.Text("ok")))),
                    replyToMessageId = "trace-1",
                ),
            ),
            routeId = "onebot:qq:42",
        )

        val failed = result as MessageSendResult.Failed
        assertEquals("OneBot 目标 ID 必须是数字：bad", failed.reason)
        assertFalse(failed.retryable)
        assertEquals(0, gateway.sendGroupMessageCalls)
    }

    @Test
    fun `message send should return independent receipts for split units`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(MessageSinkRouteState.READY)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)
        val target = TargetAddress.of("qq", TargetKind.GROUP, "10001")

        val result = plugin.sendMessage(
            request = MessageSendRequest(
                target = target,
                message = Message(
                    id = "message-split",
                    time = 1,
                    targets = listOf(target),
                    batches = listOf(
                        MessageBatch(listOf(MessageContent.Text("第一段"))),
                        MessageBatch(listOf(MessageContent.Text("第二段"))),
                    ),
                ),
            ),
            routeId = "onebot:qq:42",
        )

        val sent = assertIs<MessageSendResult.Sent>(result)
        assertEquals(listOf("group-1", "group-2"), sent.sinkMessageIds)
        assertEquals(listOf("group-1", "group-2"), sent.receipts.map { it.sinkMessageId })
        assertEquals("group-1", sent.sinkMessageId)
        assertEquals("onebot:qq:42", sent.receipts.single { it.sinkMessageId == "group-2" }.sinkRouteId)
    }

    @Test
    fun `napcat local probe should use download_file`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            implementationInfo = OneBotImplementationInfo(appName = "NapCatQQ", appVersion = "4.8.0"),
            downloadProbeResult = OneBotDownloadProbeResult(available = true, reason = "ok"),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val advice = plugin.adviseMediaDelivery(adviceRequest())
        val probe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "onebot:qq:42",
                method = MessageSinkMediaDeliveryMethod.LOCAL_FILE,
                uri = "file:///tmp/probe.png",
            ),
        )

        assertEquals(MessageSinkMediaDeliveryConfidence.UNKNOWN, advice.localFileConfidence)
        assertEquals(MessageSinkMediaDeliveryProbeStatus.AVAILABLE, probe.status)
        assertEquals(listOf("42:file:///tmp/probe.png"), gateway.downloadProbeCalls)
    }

    @Test
    fun `llonebot same host should allow likely local file without probe side effect`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            implementationInfo = OneBotImplementationInfo(appName = "LLOneBot", appVersion = "3.33.0"),
            connectionHints = OneBotConnectionHints(sameHostLikely = true),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val advice = plugin.adviseMediaDelivery(adviceRequest())
        val probe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "onebot:qq:42",
                method = MessageSinkMediaDeliveryMethod.LOCAL_FILE,
                uri = "file:///tmp/probe.png",
            ),
        )

        assertEquals(MessageSinkMediaDeliveryConfidence.LIKELY, advice.localFileConfidence)
        assertEquals(MessageSinkMediaDeliveryProbeStatus.UNKNOWN, probe.status)
        assertEquals(emptyList(), gateway.downloadProbeCalls)
    }

    @Test
    fun `llonebot remote host should not assume local file access`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            implementationInfo = OneBotImplementationInfo(appName = "LLOneBot"),
            connectionHints = OneBotConnectionHints(sameHostLikely = false),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val advice = plugin.adviseMediaDelivery(adviceRequest())

        assertEquals(MessageSinkMediaDeliveryConfidence.UNKNOWN, advice.localFileConfidence)
    }

    @Test
    fun `signed url probe should use download_file for any client`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(
            state = MessageSinkRouteState.READY,
            implementationInfo = OneBotImplementationInfo(appName = "unknown"),
            downloadProbeResult = OneBotDownloadProbeResult(available = false, reason = "connect timeout"),
        )
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val probe = plugin.probeMediaDelivery(
            MessageSinkMediaDeliveryProbeRequest(
                routeId = "onebot:qq:42",
                method = MessageSinkMediaDeliveryMethod.SIGNED_URL,
                uri = "http://127.0.0.1:2233/media/outbound-probe",
            ),
        )

        assertEquals(MessageSinkMediaDeliveryProbeStatus.UNAVAILABLE, probe.status)
        assertEquals("connect timeout", probe.reason)
        assertEquals(listOf("42:http://127.0.0.1:2233/media/outbound-probe"), gateway.downloadProbeCalls)
    }

    private fun adviceRequest(): MessageSinkMediaDeliveryAdviceRequest {
        return MessageSinkMediaDeliveryAdviceRequest(
            routeId = "onebot:qq:42",
            webAdminEnabled = true,
            webAdminHost = "127.0.0.1",
            webAdminPort = 2233,
        )
    }

    private class FakeGateway(
        private val state: MessageSinkRouteState,
        private val implementationInfo: OneBotImplementationInfo = OneBotImplementationInfo(),
        private val connectionHints: OneBotConnectionHints = OneBotConnectionHints(),
        private val downloadProbeResult: OneBotDownloadProbeResult = OneBotDownloadProbeResult(available = false),
    ) : OneBotGateway {
        var sendGroupMessageCalls: Int = 0
        val downloadProbeCalls: MutableList<String> = mutableListOf()

        override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        }

        override suspend fun availableAccounts(): List<OneBotRuntimeAccount> {
            return listOf(OneBotRuntimeAccount(accountId = "42", name = "测试 Bot", state = state))
        }

        override suspend fun implementationInfo(accountId: String): OneBotImplementationInfo = implementationInfo

        override suspend fun connectionHints(
            accountId: String,
            webAdminHost: String,
            webAdminPort: Int,
        ): OneBotConnectionHints = connectionHints

        override suspend fun probeDownload(accountId: String, uri: String): OneBotDownloadProbeResult {
            downloadProbeCalls += "$accountId:$uri"
            return downloadProbeResult
        }

        override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? = null

        override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? {
            sendGroupMessageCalls += 1
            return "group-$sendGroupMessageCalls"
        }

        override suspend fun sendPrivateForwardMessage(
            accountId: String,
            userId: Long,
            messages: List<Map<String, Any>>,
        ): String? = null

        override suspend fun sendGroupForwardMessage(
            accountId: String,
            groupId: Long,
            messages: List<Map<String, Any>>,
        ): String? = null

        override suspend fun recallMessage(accountId: String, messageId: String) {
        }

        override suspend fun listGroups(accountId: String): List<OneBotTargetCandidate> = emptyList()

        override suspend fun listFriends(accountId: String): List<OneBotTargetCandidate> = emptyList()

        override suspend fun close() {
        }
    }
}

private fun Any.setPrivate(name: String, value: Any?) {
    val field = javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
}
