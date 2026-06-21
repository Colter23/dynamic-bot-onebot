package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import cn.evole.onebot.sdk.action.misc.ActionList
import cn.evole.onebot.sdk.action.misc.ActionRaw
import cn.evole.onebot.sdk.entity.MsgId
import cn.evole.onebot.sdk.response.contact.LoginInfoResp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.ServerSocket
import java.util.LinkedList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotGatewayTest {

    @Test
    fun `forward websocket close should return quickly when connection never succeeds`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val gateway = ForwardWsOneBotGateway(
            OneBotConfig(
                connections = listOf(
                    OneBotForwardConnectionConfig(url = "ws://127.0.0.1:$port", enabled = true),
                ),
            ),
        )

        gateway.connect { }
        delay(200)

        withTimeout(1_000) {
            gateway.close()
        }
    }

    @Test
    fun `forward auth failure classifier should detect websocket handshake rejection`() {
        val error = IllegalStateException(
            "Invalid status code received: 401 Status line: HTTP/1.1 401 Unauthorized",
        )

        val reason = oneBotForwardAuthenticationFailureReason(error)

        assertNotNull(reason)
        assertTrue(reason.contains("401"))
    }

    @Test
    fun `forward auth failure classifier should detect unauthorized close reason`() {
        val reason = oneBotForwardAuthenticationFailureReason(1008, "unauthorized")

        assertNotNull(reason)
        assertTrue(reason.contains("unauthorized"))
    }

    @Test
    fun `forward auth failure classifier should detect blank policy close before open`() {
        val reason = oneBotForwardAuthenticationFailureReason(1008, "", beforeOpen = true)

        assertNotNull(reason)
        assertTrue(reason.contains("1008"))
    }

    @Test
    fun `forward auth failure classifier should ignore blank policy close after open`() {
        val reason = oneBotForwardAuthenticationFailureReason(1008, "", beforeOpen = false)

        assertNull(reason)
    }

    @Test
    fun `forward auth failure classifier should ignore normal network failure`() {
        val reason = oneBotForwardAuthenticationFailureReason(
            java.net.ConnectException("Connection refused: connect"),
        )

        assertNull(reason)
    }

    @Test
    fun `missing send response should be uncertain`() {
        val action: ActionData<*>? = null

        val result = action.requireSendAccepted("send_private_msg", 123)

        assertIs<OneBotSendOutcome.Uncertain>(result)
    }

    @Test
    fun `accept ok action response`() {
        val action = ActionData<Any>().apply {
            status = "ok"
        }

        val result = action.requireSendAccepted("send_private_msg", 123)

        assertIs<OneBotSendOutcome.Accepted>(result)
    }

    @Test
    fun `send accepted should return message id when present`() {
        val action = ActionData<MsgId>().apply {
            status = "ok"
            data = MsgId(321)
        }

        val result = action.requireSendAccepted("send_private_msg", 123)

        assertEquals("321", assertIs<OneBotSendOutcome.Accepted>(result).sinkMessageId)
    }

    @Test
    fun `send accepted should return message id from raw action map data`() {
        val action = ActionData<Map<String, Any>>().apply {
            status = "ok"
            data = mapOf("message_id" to 321.0)
        }

        val result = action.requireSendAccepted("send_group_forward_msg", 123)

        assertEquals("321", assertIs<OneBotSendOutcome.Accepted>(result).sinkMessageId)
    }

    @Test
    fun `send accepted should return message id from raw action json data`() {
        val action = ActionData<JsonObject>().apply {
            status = "ok"
            data = JsonObject().apply { addProperty("message_id", 321) }
        }

        val result = action.requireSendAccepted("send_group_forward_msg", 123)

        assertEquals("321", assertIs<OneBotSendOutcome.Accepted>(result).sinkMessageId)
    }

    @Test
    fun `forward action params should keep messages as array object`() {
        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                add("data", JsonObject().apply { addProperty("text", "hello") })
            })
        }
        val messages = listOf(
            mapOf(
                "type" to "node",
                "data" to mapOf(
                    "name" to "Bot",
                    "uin" to "42",
                    "content" to content,
                ),
            ),
        )

        val params = forwardActionParams("group_id", 10001, messages)

        assertEquals(10001L, params["group_id"])
        assertEquals(messages, params["messages"])
        assertTrue(params["messages"] is List<*>)
    }

    @Test
    fun `explicit no response send status should be uncertain`() {
        val action = ActionData<Any>().apply {
            status = "no_response"
        }

        val result = action.requireSendAccepted("send_private_msg", 123)

        assertIs<OneBotSendOutcome.Uncertain>(result)
    }

    @Test
    fun `reject failed action response`() {
        val action = ActionData<Any>().apply {
            status = "failed"
            retCode = 1400
        }

        val error = assertFailsWith<IllegalStateException> {
            action.requireSendAccepted("send_private_msg", 123)
        }
        assertTrue(error.message.orEmpty().contains("OneBot 发送失败"))
    }

    @Test
    fun `require query ok should return response data`() {
        val action = ActionList<String>().apply {
            status = "ok"
            data = LinkedList(listOf("group"))
        }

        val data = action.requireQueryOk("get_group_list")

        assertEquals(listOf("group"), data)
    }

    @Test
    fun `require data ok should return response data`() {
        val action = ActionData<LoginInfoResp>().apply {
            status = "ok"
            data = LoginInfoResp(42L, "主机器人")
        }

        val data = action.requireDataOk("get_login_info")

        assertEquals(42L, data.userId)
        assertEquals("主机器人", data.nickname)
    }

    @Test
    fun `action accepted should reject failed recall response`() {
        val action = ActionRaw().apply {
            status = "failed"
            retCode = 1400
        }

        val error = assertFailsWith<IllegalStateException> {
            action.requireActionAccepted("delete_msg")
        }
        assertTrue(error.message.orEmpty().contains("OneBot"))
    }

    @Test
    fun `require query ok should reject missing no response and failed states`() {
        val missing: ActionList<String>? = null
        val noResponse = ActionList<String>().apply {
            status = "no_response"
        }
        val failed = ActionList<String>().apply {
            status = "failed"
            retCode = 1400
        }

        listOf(missing, noResponse, failed).forEach { action ->
            val error = assertFailsWith<IllegalStateException> {
                action.requireQueryOk("get_group_list")
            }
            assertTrue(error.message.orEmpty().startsWith("OneBot 查询失败"))
        }
    }
}
