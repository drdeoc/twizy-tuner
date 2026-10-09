package de.dressler.twizy.core.canopen

import de.dressler.twizy.core.elm.ElmErrorResponseException
import de.dressler.twizy.core.elm.ElmTimeoutException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SDO client (CiA 301 §7.2.4). Supports expedited and segmented upload, and expedited
 * download of up to 4 bytes, which covers every SEVCON parameter.
 *
 * Frame layout: byte 0 = command specifier, 1..2 = index (LE), 3 = sub-index, 4..7 = data (LE).
 */
class SdoClient(
    private val channel: CanChannel,
    private val attempts: Int = 3,
) {
    private val mutex = Mutex()

    suspend fun upload(address: ObjectAddress): ByteArray = mutex.withLock {
        retrying { uploadOnce(address) }
    }

    suspend fun download(address: ObjectAddress, data: ByteArray): Unit = mutex.withLock {
        require(data.size in 1..4) { "Only expedited downloads (1..4 bytes) are supported" }
        retrying { downloadOnce(address, data) }
    }

    private suspend fun uploadOnce(address: ObjectAddress): ByteArray {
        val response = request(address, frame(CCS_UPLOAD_INITIATE, address))
        if (response.cs() and 0xE0 != SCS_UPLOAD_INITIATE) {
            throw SdoProtocolException("Unexpected upload response for $address: ${response.hex()}")
        }
        val control = response.cs()
        if (control and EXPEDITED != 0) {
            val unused = if (control and SIZE_INDICATED != 0) (control shr 2) and 0x03 else 0
            return response.copyOfRange(4, 8 - unused)
        }

        // Segmented transfer
        val result = ArrayList<Byte>()
        var toggle = 0
        while (true) {
            val segment = channel.exchange(byteArrayOf((CCS_UPLOAD_SEGMENT or toggle).toByte(), 0, 0, 0, 0, 0, 0, 0))
            checkAbort(address, segment)
            if (segment.cs() and 0xF0 != (SCS_UPLOAD_SEGMENT or toggle)) {
                sendAbort(address, SdoAbortCodes.TOGGLE_BIT_NOT_ALTERNATED)
                throw SdoProtocolException("Segment toggle mismatch for $address: ${segment.hex()}")
            }
            val unused = (segment.cs() shr 1) and 0x07
            for (i in 1 until 8 - unused) result.add(segment[i])
            if (segment.cs() and LAST_SEGMENT != 0) return result.toByteArray()
            if (result.size > MAX_SEGMENTED_SIZE) {
                sendAbort(address, SdoAbortCodes.OUT_OF_MEMORY)
                throw SdoProtocolException("Segmented upload of $address exceeds $MAX_SEGMENTED_SIZE bytes")
            }
            toggle = toggle xor TOGGLE
        }
    }

    private suspend fun downloadOnce(address: ObjectAddress, data: ByteArray) {
        val unused = 4 - data.size
        val request = frame(CCS_DOWNLOAD_INITIATE or (unused shl 2) or EXPEDITED or SIZE_INDICATED, address)
        data.copyInto(request, 4)
        val response = request(address, request)
        if (response.cs() and 0xE0 != SCS_DOWNLOAD_INITIATE) {
            throw SdoProtocolException("Unexpected download response for $address: ${response.hex()}")
        }
    }

    /** Sends an initiate frame and checks that the answer is about the same object. */
    private suspend fun request(address: ObjectAddress, request: ByteArray): ByteArray {
        val response = channel.exchange(request)
        if (response.size != 8) throw SdoProtocolException("Response must have 8 bytes: ${response.hex()}")
        checkAbort(address, response)
        val answered = ObjectAddress(response.u8(1) or (response.u8(2) shl 8), response.u8(3))
        if (answered != address) {
            throw SdoProtocolException("Asked for $address but the answer is for $answered: ${response.hex()}")
        }
        return response
    }

    private fun checkAbort(address: ObjectAddress, response: ByteArray) {
        if (response.cs() == CS_ABORT) {
            val code = response.u8(4).toLong() or (response.u8(5).toLong() shl 8) or
                (response.u8(6).toLong() shl 16) or (response.u8(7).toLong() shl 24)
            throw SdoAbortException(address, code)
        }
    }

    private suspend fun sendAbort(address: ObjectAddress, code: Long) {
        val abort = frame(CS_ABORT, address)
        for (i in 0..3) abort[4 + i] = (code shr (8 * i)).toByte()
        runCatching { channel.exchange(abort) } // the server does not confirm an abort
    }

    /** Retries when the adapter or the controller did not answer; protocol errors are not retried. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(attempts) {
            try {
                return block()
            } catch (e: ElmTimeoutException) {
                last = e
            } catch (e: ElmErrorResponseException) {
                if (!e.isTransient) throw e
                last = e
            }
        }
        throw last!!
    }

    private fun frame(cs: Int, address: ObjectAddress) = byteArrayOf(
        cs.toByte(),
        address.index.toByte(), (address.index shr 8).toByte(),
        address.subIndex.toByte(),
        0, 0, 0, 0,
    )

    private fun ByteArray.cs() = u8(0)
    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        const val CCS_DOWNLOAD_INITIATE = 0x20
        const val SCS_DOWNLOAD_INITIATE = 0x60
        const val CCS_UPLOAD_INITIATE = 0x40
        const val SCS_UPLOAD_INITIATE = 0x40
        const val CCS_UPLOAD_SEGMENT = 0x60
        const val SCS_UPLOAD_SEGMENT = 0x00
        const val CS_ABORT = 0x80
        const val TOGGLE = 0x10
        const val EXPEDITED = 0x02
        const val SIZE_INDICATED = 0x01
        const val LAST_SEGMENT = 0x01
        const val MAX_SEGMENTED_SIZE = 256
    }
}
