package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import cn.evole.onebot.sdk.action.misc.ActionList
import cn.evole.onebot.sdk.action.misc.ActionRaw
import cn.evole.onebot.sdk.entity.MsgId
import cn.evole.onebot.sdk.response.contact.LoginInfoResp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.LinkedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OneBotGatewayTest {

    @Test
    fun `accept no response as sent`() {
        val action: ActionData<*>? = null

        action.requireSendAccepted("send_private_msg", 123)
    }

    @Test
    fun `accept ok action response`() {
        val action = ActionData<Any>().apply {
            status = "ok"
        }

        action.requireSendAccepted("send_private_msg", 123)
    }

    @Test
    fun `send accepted should return message id when present`() {
        val action = ActionData<MsgId>().apply {
            status = "ok"
            data = MsgId(321)
        }

        val messageId = action.requireSendAccepted("send_private_msg", 123)

        assertEquals("321", messageId)
    }

    @Test
    fun `send accepted should return message id from raw action map data`() {
        val action = ActionData<Map<String, Any>>().apply {
            status = "ok"
            data = mapOf("message_id" to 321.0)
        }

        val messageId = action.requireSendAccepted("send_group_forward_msg", 123)

        assertEquals("321", messageId)
    }

    @Test
    fun `send accepted should return message id from raw action json data`() {
        val action = ActionData<JsonObject>().apply {
            status = "ok"
            data = JsonObject().apply { addProperty("message_id", 321) }
        }

        val messageId = action.requireSendAccepted("send_group_forward_msg", 123)

        assertEquals("321", messageId)
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
    fun `accept explicit no response status as sent`() {
        val action = ActionData<Any>().apply {
            status = "no_response"
        }

        action.requireSendAccepted("send_private_msg", 123)
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
