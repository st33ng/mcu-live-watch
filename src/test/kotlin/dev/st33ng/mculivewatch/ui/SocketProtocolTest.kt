package dev.st33ng.mculivewatch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the request the plugin sends to the bridge.
 *
 * The bridge parses `name;size;address;hz;type`, so a wrong field count or a decimal address would
 * silently produce no samples at all.
 */
class SocketProtocolTest {

    @Test
    fun `put message carries name size hex address hz and type`() {
        val message = SocketClient.createSocketMessage(
            command = "put",
            varName = "htim15",
            varSize = 76,
            varAddress = 0x20000b0cL,
            varType = "TIM_TypeDef",
            updateRateHz = 1000,
        )

        assertEquals("put;htim15;76;0x20000b0c;1000;TIM_TypeDef", message)
        assertEquals(6, message.split(";").size)
    }

    @Test
    fun `address above 32 bit range is not truncated`() {
        // An unsigned 32-bit value that would go negative if stored as Int.
        val message = SocketClient.createSocketMessage(
            "put", "big", 4, 0xFFFF_FFF0L, "uint32_t", 100,
        )

        assertEquals("put;big;4;0xfffffff0;100;uint32_t", message)
    }

    @Test
    fun `frequency change message has three fields`() {
        val message = SocketClient.createSocketMessage("setVariableReadFrq", "counter", 500)

        assertEquals("setVariableReadFrq;counter;500", message)
    }

    @Test
    fun `plain command is passed through unchanged`() {
        assertEquals("start", SocketClient.createSocketMessage("start"))
        assertEquals("stop", SocketClient.createSocketMessage("stop"))
    }
}
