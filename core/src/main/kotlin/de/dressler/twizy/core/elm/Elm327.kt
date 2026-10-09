package de.dressler.twizy.core.elm

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.transport.Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class ElmException(message: String) : Exception(message)

/** The adapter (or the bus behind it) did not answer in time. Safe to retry. */
class ElmTimeoutException(message: String) : ElmException(message)

/** The adapter answered with an error such as NO DATA or CAN ERROR. */
class ElmErrorResponseException(val command: String, val response: String) :
    ElmException("ELM327 answered '$response' to '$command'") {
    /** NO DATA means the controller did not reply; that is worth a retry. */
    val isTransient: Boolean get() = response.contains("NO DATA")
}

/**
 * ELM327 driver for raw 11-bit CAN frames (CAN auto-formatting off).
 *
 * Every command is terminated by '\r' and every answer ends with the '>' prompt, so reads
 * wait for the prompt instead of sleeping for a fixed time.
 */
class Elm327(
    private val transport: Transport,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : CanChannel {

    var version: String? = null
        private set

    /** Serializes SDO exchanges and broadcast monitoring, which both change the receive filter. */
    private val mutex = Mutex()

    /** CAN ID the adapter currently lets through (ATCRA), or null if unknown. */
    private var rxFilter: Int? = null
    private var sdoRxId: Int = 0

    /** Sends [command] and returns the raw answer without the trailing prompt. */
    suspend fun command(command: String, timeout: Long = timeoutMillis): String {
        transport.drain()
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val answer = StringBuilder()
        while (true) {
            val byte = transport.read(timeout)
                ?: throw ElmTimeoutException("No prompt after '$command' (got '${answer.trim()}')")
            val c = (byte.toInt() and 0xFF).toChar()
            if (c == PROMPT) break
            answer.append(c)
            if (answer.length > MAX_ANSWER_LENGTH) throw ElmException("Answer to '$command' too long")
        }
        return answer.toString()
    }

    /**
     * Resets the adapter and configures it for SDO traffic with one CANopen node.
     * @param txId CAN ID requests are sent on (0x600 + node)
     * @param rxId CAN ID responses arrive on (0x580 + node); everything else is filtered out
     */
    suspend fun initialize(txId: Int, rxId: Int) {
        // Abort anything the adapter might still be doing, then reset it.
        transport.write("\r".toByteArray(Charsets.US_ASCII))
        transport.drain()
        val reset = lines(command("ATZ", RESET_TIMEOUT_MILLIS))
        version = reset.firstOrNull { it.contains("ELM", ignoreCase = true) }
            ?: throw ElmException("Not an ELM327 adapter: $reset")

        expectOk("ATE0")              // echo off (the answer to this one is still echoed)
        expectOk("ATL0")              // no linefeeds
        expectOk("ATS0")              // no spaces between bytes
        expectOk("ATH0")              // no CAN IDs in answers
        expectOk("ATSP6")             // ISO 15765-4 CAN, 11 bit, 500 kbaud
        expectOk("ATCAF0")            // raw frames, no ISO-TP formatting
        expectOk("ATSH%03X".format(txId))
        sdoRxId = rxId
        setFilter(rxId)
    }

    override suspend fun exchange(request: ByteArray): ByteArray = mutex.withLock {
        setFilter(sdoRxId)
        val cmd = request.toHex()
        val answer = lines(command(cmd))
        answer.firstOrNull { it.isErrorLine() }?.let { throw ElmErrorResponseException(cmd, it) }
        val frame = answer.firstOrNull { it.length == 16 && it.all(Char::isHexDigit) }
            ?: throw ElmException("No CAN frame in answer to '$cmd': $answer")
        frame.hexToBytes()
    }

    /**
     * Waits for one broadcast frame with CAN ID [id] ("free frame", as CanZE does it):
     * restricts the receive filter to [id], monitors with ATMA until the first frame
     * arrives, then stops monitoring. Returns null if no frame arrives within [timeout].
     */
    suspend fun monitor(id: Int, timeout: Long): ByteArray? = mutex.withLock {
        setFilter(id)
        transport.drain()
        transport.write("ATMA\r".toByteArray(Charsets.US_ASCII))
        val line = StringBuilder()
        var frame: ByteArray? = null
        var promptSeen = false
        while (frame == null) {
            val byte = transport.read(timeout) ?: break
            when (val c = (byte.toInt() and 0xFF).toChar()) {
                '\r', '\n' -> {
                    val text = line.trim()
                    if (text.isNotEmpty() && text.length % 2 == 0 && text.length <= 16 && text.all(Char::isHexDigit)) {
                        frame = text.toString().hexToBytes()
                    }
                    line.clear()
                }
                PROMPT -> { promptSeen = true; break } // monitoring ended on its own (e.g. BUFFER FULL)
                else -> line.append(c)
            }
        }
        if (!promptSeen) {
            // Any character stops monitoring; the adapter then finishes with a prompt.
            transport.write("\r".toByteArray(Charsets.US_ASCII))
            while (true) {
                val byte = transport.read(timeoutMillis) ?: throw ElmTimeoutException("Adapter did not stop monitoring")
                if ((byte.toInt() and 0xFF).toChar() == PROMPT) break
            }
        }
        frame
    }

    private suspend fun setFilter(id: Int) {
        if (rxFilter == id) return
        rxFilter = null
        expectOk("ATCRA%03X".format(id))
        rxFilter = id
    }

    private suspend fun expectOk(cmd: String) {
        val answer = lines(command(cmd))
        if (answer.none { it == "OK" }) throw ElmException("'$cmd' was not accepted: $answer")
    }

    private fun lines(raw: String): List<String> =
        raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun String.isErrorLine(): Boolean =
        this == "?" || ERROR_ANSWERS.any { contains(it) }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1_000L
        const val RESET_TIMEOUT_MILLIS = 3_000L
        private const val PROMPT = '>'
        private const val MAX_ANSWER_LENGTH = 512
        private val ERROR_ANSWERS = listOf(
            "NO DATA", "CAN ERROR", "BUS ERROR", "BUS BUSY", "BUFFER FULL",
            "DATA ERROR", "FB ERROR", "STOPPED", "UNABLE TO CONNECT", "ERR",
        )
    }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length: $this" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
