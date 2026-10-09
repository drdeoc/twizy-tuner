package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.elm.Elm327
import de.dressler.twizy.core.elm.ElmErrorResponseException
import de.dressler.twizy.core.elm.ElmTimeoutException
import de.dressler.twizy.core.sim.SimulatedElm327Transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Elm327Test {

    @Test
    fun `initialize configures raw CAN frames for node 1`() = runTest {
        val stack = SimStack().init()
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATCAF0", "ATSH601", "ATCRA581"),
            stack.transport.received,
        )
        assertEquals("ELM327 v1.5", stack.elm.version)
    }

    @Test
    fun `frames are sent as hex and the answer is parsed`() = runTest {
        val stack = SimStack().init()
        val answer = stack.elm.exchange(bytes(0x40, 0x11, 0x46, 0x01, 0, 0, 0, 0))
        assertArrayEquals(bytes(0x4B, 0x11, 0x46, 0x01, 0x70, 0x03, 0, 0), answer)
        assertEquals("40114601" + "00000000", stack.transport.received.last())
    }

    @Test
    fun `NO DATA becomes a transient error`() = runTest {
        val silent = CanChannel { throw ElmTimeoutException("silent") }
        val elm = Elm327(SimulatedElm327Transport(silent)).also { it.initialize(0x601, 0x581) }
        val e = assertThrows(ElmErrorResponseException::class.java) {
            runBlocking { elm.exchange(ByteArray(8)) }
        }
        assertEquals(true, e.isTransient)
    }
}
