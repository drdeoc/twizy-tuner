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
class SimulatedElm327Transport(
    private val bus: CanChannel,
    /** Source of broadcast frames for ATMA, or null if the bus is silent. */
    private val vehicle: SimulatedVehicle? = null,
) : Transport {

    private val input = StringBuilder()
    private val output = ArrayDeque<Byte>()
    private var echo = true
    private var headerSet = false
    private var filter: Int? = null
    private var monitoring = false
    var closed = false
        private set

    /** Every command line received, for assertions in tests. */
    val received = mutableListOf<String>()

    override suspend fun write(data: ByteArray) {
        if (monitoring) {
            // Any character stops ATMA.
            monitoring = false
            emit("\r>")
            return
        }
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
            cmd == "ATZ" -> { echo = true; headerSet = false; filter = null; reply("\rELM327 v1.5") }
            cmd == "ATE0" -> { echo = false; reply("OK") }
            cmd == "ATE1" -> { echo = true; reply("OK") }
            cmd == "ATSH601" -> { headerSet = true; reply("OK") }
            cmd.startsWith("ATCRA") && cmd.length == 8 -> { filter = cmd.substring(5).toInt(16); reply("OK") }
            cmd == "ATMA" -> monitor()
            cmd.startsWith("AT") -> reply("OK")
            cmd.length == 16 && cmd.all { it.isDigit() || it in 'A'..'F' } -> frame(cmd)
            else -> reply("?")
        }
    }

    private suspend fun frame(hex: String) {
        // The SDO answer only gets through if the filter lets 0x581 pass.
        if (!headerSet || filter != 0x581) return reply("NO DATA")
        val response = try {
            bus.exchange(hex.hexToBytes())
        } catch (e: ElmTimeoutException) {
            return reply("NO DATA")
        }
        reply(response.toHex())
    }

    private fun monitor() {
        monitoring = true
        val frame = filter?.let { vehicle?.frame(it) } ?: return // no frame: adapter stays silent
        emit(frame.toHex() + "\r")
    }

    private fun reply(text: String) = emit("$text\r\r>")

    private fun emit(text: String) {
        text.toByteArray(Charsets.US_ASCII).forEach { output.addLast(it) }
    }
}
