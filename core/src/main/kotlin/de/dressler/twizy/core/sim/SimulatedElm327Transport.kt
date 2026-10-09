package de.dressler.twizy.core.sim

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.elm.ElmTimeoutException
import de.dressler.twizy.core.elm.hexToBytes
import de.dressler.twizy.core.elm.toHex
import de.dressler.twizy.core.transport.Transport

/**
 * Byte-level ELM327 emulation in front of a [CanChannel] (usually a [SimulatedSevcon]).
 * Implements the subset of AT commands used by the app, with echo until ATE0.
 */
class SimulatedElm327Transport(private val bus: CanChannel) : Transport {

    private val input = StringBuilder()
    private val output = ArrayDeque<Byte>()
    private var echo = true
    private var headerSet = false
    private var filterSet = false
    var closed = false
        private set

    /** Every command line received, for assertions in tests. */
    val received = mutableListOf<String>()

    override suspend fun write(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\r') {
                handle(input.toString().trim())
                input.clear()
            } else {
                input.append(c)
            }
        }
    }

    override suspend fun read(timeoutMillis: Long): Byte? = output.removeFirstOrNull()

    override fun close() {
        closed = true
    }

    private suspend fun handle(line: String) {
        if (line.isEmpty()) return reply("")
        received += line
        if (echo) emit(line + "\r")
        val cmd = line.uppercase().replace(" ", "")
        when {
            cmd == "ATZ" -> { echo = true; headerSet = false; filterSet = false; reply("\rELM327 v1.5") }
            cmd == "ATE0" -> { echo = false; reply("OK") }
            cmd == "ATE1" -> { echo = true; reply("OK") }
            cmd == "ATSH601" -> { headerSet = true; reply("OK") }
            cmd == "ATCRA581" -> { filterSet = true; reply("OK") }
            cmd.startsWith("AT") -> reply("OK")
            cmd.length == 16 && cmd.all { it.isDigit() || it in 'A'..'F' } -> frame(cmd)
            else -> reply("?")
        }
    }

    private suspend fun frame(hex: String) {
        if (!headerSet || !filterSet) return reply("NO DATA")
        val response = try {
            bus.exchange(hex.hexToBytes())
        } catch (e: ElmTimeoutException) {
            return reply("NO DATA")
        }
        reply(response.toHex())
    }

    private fun reply(text: String) = emit("$text\r\r>")

    private fun emit(text: String) {
        text.toByteArray(Charsets.US_ASCII).forEach { output.addLast(it) }
    }
}
