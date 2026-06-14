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
import top.colter.dynamic.core.command.CommandPublishRequest
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.plugin.IncomingMessagePublisher

class OneBotGatewayPluginIncomingTest {
    @Test
    fun `incoming command should publish asynchronously`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val published = CompletableDeferred<CommandPublishRequest>()
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
            assertEquals("/db status", withTimeout(500) { published.await() }.rawText)
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `incoming non command message should publish incoming event only`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publishedCommand = CompletableDeferred<CommandPublishRequest>()
        val publishedIncoming = CompletableDeferred<IncomingMessage>()
        plugin.prepareIncomingTest(
            scope = scope,
            publishCommand = { request -> publishedCommand.complete(request) },
            publishIncoming = { message -> publishedIncoming.complete(message) },
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

            val incoming = withTimeout(500) { publishedIncoming.await() }
            assertEquals("321", incoming.messageId)
            assertEquals("", incoming.text)
            assertNull(withTimeoutOrNull(200) { publishedCommand.await() })
        } finally {
            plugin.callPrivate("stopIncomingScope")
            scope.cancel()
        }
    }

    @Test
    fun `incoming command should be ignored after stop`() = runBlocking {
        val plugin = OneBotGatewayPlugin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = CompletableDeferred<CommandPublishRequest>()
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
        publishIncoming: suspend (IncomingMessage) -> Unit = {},
        publishCommand: suspend (CommandPublishRequest) -> Unit,
    ) {
        setPrivate("pluginId", ONEBOT_PLUGIN_ID)
        setPrivate("pluginScope", scope)
        setPrivate("commandPublisher", CommandPublisher { request -> publishCommand(request) })
        setPrivate("incomingMessagePublisher", IncomingMessagePublisher { message -> publishIncoming(message) })
        callPrivate("startIncomingScope")
    }

    private fun incomingCommand(): OneBotIncomingMessage {
        return OneBotIncomingMessage(
            chatType = OneBotChatType.GROUP,
            chatId = "12345",
            senderId = "67890",
            text = "/db status",
            botAccountId = "42",
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
