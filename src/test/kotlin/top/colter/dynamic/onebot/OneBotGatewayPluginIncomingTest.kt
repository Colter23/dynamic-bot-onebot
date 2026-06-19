package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.plugin.IncomingMessagePublisher

class OneBotGatewayPluginIncomingTest {
    @Test
    fun `incoming text message should publish asynchronously`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(scope) { request ->
            publishStarted.complete(Unit)
            releasePublish.await()
            published.complete(request)
        }

        try {
            plugin.setPrivate("running", true)
            val callbackReturned = async(Dispatchers.Default) {
                plugin.invokeIncoming(incomingCommand())
            }

            withTimeout(500) { callbackReturned.await() }
            withTimeout(500) { publishStarted.await() }
            assertFalse(published.isCompleted)

            releasePublish.complete(Unit)
            val request = withTimeout(500) { published.await() }
            assertEquals("message-1", request.traceId)
            assertEquals("message-1", request.replyToMessageId)
            assertEquals("/db status", request.message.text)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `incoming blank text message should still publish incoming event`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedIncoming = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishIncoming = { request -> publishedIncoming.complete(request) },
        )

        try {
            plugin.setPrivate("running", true)

            plugin.invokeIncoming(
                OneBotIncomingMessage(
                    chatType = OneBotChatType.GROUP,
                    chatId = "12345",
                    senderId = "67890",
                    text = "",
                    botAccountId = "42",
                    messageId = "321",
                )
            )

            val incoming = withTimeout(500) { publishedIncoming.await() }.message
            assertEquals("321", incoming.messageId)
            assertEquals("", incoming.text)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `incoming command should be ignored after stop`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<IncomingMessagePublishRequest>()
        plugin.prepareIncomingTest(scope) { request ->
            published.complete(request)
        }

        try {
            plugin.setPrivate("running", false)
            plugin.callPrivate("stopIncomingScope")

            plugin.invokeIncoming(incomingCommand())

            assertNull(withTimeoutOrNull(200) { published.await() })
        } finally {
            scope.cancel()
        }
    }

    private fun OneBotGatewayPlugin.prepareIncomingTest(
        scope: CoroutineScope,
        publishIncoming: suspend (IncomingMessagePublishRequest) -> Unit,
    ) {
        setPrivate("pluginId", ONEBOT_PLUGIN_ID)
        setPrivate("pluginScope", scope)
        setPrivate("incomingMessagePublisher", IncomingMessagePublisher { request -> publishIncoming(request) })
        callPrivate("startIncomingScope")
    }

    private fun incomingCommand(): OneBotIncomingMessage {
        return OneBotIncomingMessage(
            chatType = OneBotChatType.GROUP,
            chatId = "12345",
            senderId = "67890",
            text = "/db status",
            botAccountId = "42",
            messageId = "message-1",
            mentionedAccountIds = setOf("42"),
        )
    }

    private fun OneBotGatewayPlugin.invokeIncoming(incoming: OneBotIncomingMessage) {
        val method = javaClass.getDeclaredMethod("onIncomingMessage", OneBotIncomingMessage::class.java)
        method.isAccessible = true
        method.invoke(this, incoming)
    }

    private fun Any.callPrivate(name: String) {
        val method = javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(this)
    }

    private fun Any.setPrivate(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }
}
