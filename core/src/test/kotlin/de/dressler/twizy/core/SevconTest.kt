package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.canopen.SdoAbortCodes
import de.dressler.twizy.core.canopen.SdoClient
import de.dressler.twizy.core.canopen.SdoAbortException
import de.dressler.twizy.core.params.DataType
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.sevcon.SevconException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SevconTest {

    @Test
    fun `identifies Twizy 80 and Twizy 45`() = runTest {
        assertEquals(ControllerType.TWIZY80, SimStack(ControllerType.TWIZY80).init().sevcon.identify())
        assertEquals(ControllerType.TWIZY45, SimStack(ControllerType.TWIZY45).init().sevcon.identify())
    }

    @Test
    fun `login reaches access level 4 and logout returns to 0`() = runTest {
        val stack = SimStack().init()
        stack.sevcon.login()
        assertEquals(4L, stack.sim.level)
        stack.sevcon.logout()
        assertEquals(0L, stack.sim.level)
    }

    @Test
    fun `switches between operational and pre-operational`() = runTest {
        val stack = SimStack().init()
        stack.sevcon.login()
        stack.sevcon.enterPreOperational()
        assertEquals(NmtState.PRE_OPERATIONAL, stack.sevcon.nmtState())
        stack.sevcon.enterOperational()
        assertEquals(NmtState.OPERATIONAL, stack.sevcon.nmtState())
    }

    @Test
    fun `refused pre-operational request is reported`() = runTest {
        val stack = SimStack().init()
        stack.sevcon.login()
        stack.sim.refusePreOp = true
        assertThrows(SdoAbortException::class.java) { runBlocking { stack.sevcon.enterPreOperational() } }
    }

    @Test
    fun `general errors carry the SEVCON reason from 5310`() = runTest {
        val stack = SimStack().init()
        val p = SevconCatalog[0x2920, 0x05]
        val e = assertThrows(SdoAbortException::class.java) {
            runBlocking { stack.sevcon.write(p.address, DataType.U16, 8000) } // not logged in
        }
        assertEquals(SdoAbortCodes.GENERAL_ERROR, e.code)
        assertEquals(0x4685L, e.vendorCode)
    }

    @Test
    fun `reads version strings`() = runTest {
        val stack = SimStack().init()
        assertEquals("0712.0001", stack.sevcon.readString(Sevcon.SOFTWARE_VERSION))
    }

    @Test
    fun `unknown controller is rejected`() {
        val foreign = CanChannel { req -> bytes(0x43, req[1].toInt(), req[2].toInt(), req[3].toInt(), 0x01, 0x02, 0x03, 0x04) }
        assertThrows(SevconException::class.java) { runBlocking { Sevcon(SdoClient(foreign)).identify() } }
    }
}
