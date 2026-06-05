package top.colter.dynamic.onebot

import com.google.gson.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
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

    private class FakeGateway(
        private val state: MessageSinkRouteState,
    ) : OneBotGateway {
        override fun connect(onIncomingMessage: (OneBotIncomingMessage) -> Unit) {
        }

        override suspend fun availableAccounts(): List<OneBotRuntimeAccount> {
            return listOf(OneBotRuntimeAccount(accountId = "42", name = "测试 Bot", state = state))
        }

        override suspend fun sendPrivateMessage(accountId: String, userId: Long, message: JsonArray): String? = null

        override suspend fun sendGroupMessage(accountId: String, groupId: Long, message: JsonArray): String? = null

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
