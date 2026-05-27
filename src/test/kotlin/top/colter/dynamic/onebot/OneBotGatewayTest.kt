package top.colter.dynamic.onebot

import cn.evole.onebot.sdk.action.misc.ActionData
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OneBotGatewayTest {

    @Test
    fun `accept no response as sent`() {
        val action: ActionData<*>? = null

        action.requireOk("send_private_msg", 123)
    }

    @Test
    fun `accept ok action response`() {
        val action = ActionData<Any>().apply {
            status = "ok"
        }

        action.requireOk("send_private_msg", 123)
    }

    @Test
    fun `reject failed action response`() {
        val action = ActionData<Any>().apply {
            status = "failed"
            retCode = 1400
        }

        assertFailsWith<IllegalStateException> {
            action.requireOk("send_private_msg", 123)
        }
    }
}
