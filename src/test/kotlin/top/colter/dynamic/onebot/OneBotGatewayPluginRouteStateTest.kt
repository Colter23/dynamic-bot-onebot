package top.colter.dynamic.onebot

import com.google.gson.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
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
    fun `command result should reject invalid target id without sending`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val gateway = FakeGateway(MessageSinkRouteState.READY)
        plugin.setPrivate("gateway", gateway)
        plugin.setPrivate("running", true)

        val result = plugin.sendCommandResult(
            request = CommandResultSendRequest(
                target = CommandTarget(
                    address = TargetAddress.of("qq", TargetKind.GROUP, "bad"),
                    senderId = "10001",
                ),
                chain = listOf(MessageBatch(listOf(MessageContent.Text("ok")))),
                inReplyTo = "trace-1",
            ),
            routeId = "onebot:qq:42",
        )

        val failed = result as MessageSendResult.Failed
        assertEquals("OneBot 目标 ID 必须是数字：bad", failed.reason)
        assertFalse(failed.retryable)
        assertEquals(0, gateway.sendGroupMessageCalls)
    }

    private class FakeGateway(
        private val state: MessageSinkRouteState,
    ) : OneBotGateway {
        var sendGroupMessageCalls: Int = 0

        override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        }

        override suspend fun availableAccounts(): List<OneBotRuntimeAccount> {
            return listOf(OneBotRuntimeAccount(accountId = "42", name = "测试 Bot", state = state))
        }

        override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? = null

        override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? {
            sendGroupMessageCalls += 1
            return null
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
