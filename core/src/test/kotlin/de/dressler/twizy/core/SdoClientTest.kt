package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.canopen.SdoAbortCodes
import de.dressler.twizy.core.canopen.SdoAbortException
import de.dressler.twizy.core.canopen.SdoClient
import de.dressler.twizy.core.canopen.SdoProtocolException
import de.dressler.twizy.core.elm.ElmTimeoutException
import de.dressler.twizy.core.sim.SimulatedSevcon
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SdoClientTest {

    private class Recorder(private val answers: List<ByteArray>) : CanChannel {
        val requests = mutableListOf<ByteArray>()
        override suspend fun exchange(request: ByteArray): ByteArray {
            requests += request
            return answers[requests.size - 1]
        }
    }

    private val torque = ObjectAddress(0x4611, 0x01)

    @Test
    fun `expedited upload with size indication returns only the used bytes`() = runTest {
        val bus = Recorder(listOf(bytes(0x4B, 0x11, 0x46, 0x01, 0x70, 0x03, 0xAA, 0xBB)))
        val data = SdoClient(bus).upload(torque)
        assertArrayEquals(bytes(0x40, 0x11, 0x46, 0x01, 0, 0, 0, 0), bus.requests.single())
        assertArrayEquals(bytes(0x70, 0x03), data)
    }

    @Test
    fun `expedited download of two bytes uses size indicated command 2B`() = runTest {
        val bus = Recorder(listOf(bytes(0x60, 0x11, 0x46, 0x01, 0, 0, 0, 0)))
        SdoClient(bus).download(torque, bytes(0x70, 0x03))
        assertArrayEquals(bytes(0x2B, 0x11, 0x46, 0x01, 0x70, 0x03, 0, 0), bus.requests.single())
    }

    @Test
    fun `abort frame is decoded to the full 32 bit code`() = runTest {
        val bus = Recorder(listOf(bytes(0x80, 0x11, 0x46, 0x01, 0x00, 0x00, 0x02, 0x06)))
        val e = assertThrows(SdoAbortException::class.java) { runBlocking { SdoClient(bus).upload(torque) } }
        assertEquals(SdoAbortCodes.OBJECT_DOES_NOT_EXIST, e.code)
        assertEquals(torque, e.address)
    }

    @Test
    fun `answer for another object is rejected`() = runTest {
        val bus = Recorder(listOf(bytes(0x4B, 0x12, 0x46, 0x01, 0x70, 0x03, 0, 0)))
        assertThrows(SdoProtocolException::class.java) { runBlocking { SdoClient(bus).upload(torque) } }
    }

    @Test
    fun `segmented upload reassembles a string`() = runTest {
        val sim = SimulatedSevcon()
        val text = SdoClient(sim).upload(ObjectAddress(0x1008, 0)).toString(Charsets.ISO_8859_1)
        assertEquals("Gen4 (Renault Twizy) [simulated]", text)
    }

    @Test
    fun `lost frames are retried`() = runTest {
        val sim = SimulatedSevcon().apply { unansweredRequests = 2 }
        val data = SdoClient(sim, attempts = 3).upload(torque)
        assertArrayEquals(bytes(0x70, 0x03), data) // 880 = 0x0370
    }

    @Test
    fun `retries give up after the configured attempts`() = runTest {
        val sim = SimulatedSevcon().apply { unansweredRequests = 3 }
        assertThrows(ElmTimeoutException::class.java) { runBlocking { SdoClient(sim, attempts = 3).upload(torque) } }
    }
}
