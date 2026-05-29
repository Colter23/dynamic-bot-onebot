package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import cn.evole.onebot.sdk.action.misc.ActionList
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
