package de.dressler.twizy.core.sim

import de.dressler.twizy.core.canopen.CanChannel
import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.canopen.SdoAbortCodes
import de.dressler.twizy.core.elm.ElmTimeoutException
import de.dressler.twizy.core.params.DataType
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon

/**
 * In-memory SEVCON SDO server, initialized with the factory defaults from the catalog.
 *
 * Modelled behaviour: access levels via 0x5000, NMT state via 0x2800/0x5110, writes that need
 * pre-operational mode, read-only identity objects, expedited and segmented uploads.
 * The vendor error codes in 0x5310 are made up; the real SEVCON codes are unknown.
 */
class SimulatedSevcon(val type: ControllerType = ControllerType.TWIZY80) : CanChannel {

    private class Entry(var bytes: ByteArray, val size: Int, val writable: Boolean, val needsPreOp: Boolean)

    private val dictionary = HashMap<ObjectAddress, Entry>()
    private var accessLevel = 0L
    private var nmtState = 5L
    private var lastError = 0L
    private var segmentedData: ByteArray? = null
    private var segmentedOffset = 0
    private var expectedToggle = 0

    /** Number of requests to swallow without answering (simulates a lost frame). */
    var unansweredRequests = 0

    /** Writes to these objects fail with a general error. */
    val failingWrites = mutableSetOf<ObjectAddress>()

    /** If true, pre-operational requests are refused (e.g. vehicle is moving). */
    var refusePreOp = false

    var mapCommits = 0
        private set

    init {
        for (p in SevconCatalog.all) {
            val bytes = when {
                p.type == DataType.STRING -> defaultString(p.address).toByteArray(Charsets.ISO_8859_1)
                else -> p.type.encode(p.type.range.coerce(p.defaultFor(type) ?: 0))
            }
            val readOnly = p.address.index in 0x1000..0x1FFF
            dictionary[p.address] = Entry(bytes, p.type.size, !readOnly, p.requiresPreOp)
        }
        dictionary[ObjectAddress(0x1018, 0x02)]!!.bytes = DataType.U32.encode(type.productCode)
        // Not in the SDO list: the map commit trigger
        dictionary[Sevcon.MAP_COMMIT] = Entry(byteArrayOf(0), 1, writable = true, needsPreOp = true)
    }

    fun value(address: ObjectAddress): Long =
        DataType.U32.decode(dictionary[address]?.bytes ?: error("$address unknown"))

    val state: Long get() = nmtState
    val level: Long get() = accessLevel

    override suspend fun exchange(request: ByteArray): ByteArray {
        require(request.size == 8)
        if (unansweredRequests > 0) {
            unansweredRequests--
            throw ElmTimeoutException("simulated lost frame")
        }
        val cs = request.u8(0)
        val address = ObjectAddress(request.u8(1) or (request.u8(2) shl 8), request.u8(3))
        return when {
            cs and 0xE0 == 0x40 -> upload(address)
            cs and 0xE0 == 0x20 -> download(address, cs, request)
            cs and 0xE0 == 0x60 -> uploadSegment(cs)
            cs == 0x80 -> { segmentedData = null; ByteArray(8) }
            else -> abort(address, 0x0504_0001L)
        }
    }

    private fun upload(address: ObjectAddress): ByteArray {
        val value = when (address) {
            Sevcon.ACCESS_LEVEL -> DataType.U16.encode(accessLevel)
            Sevcon.NMT_STATE -> DataType.U8.encode(nmtState)
            Sevcon.LAST_ERROR -> DataType.U16.encode(lastError)
            else -> dictionary[address]?.bytes ?: return abort(address, SdoAbortCodes.OBJECT_DOES_NOT_EXIST)
        }
        if (value.size <= 4) {
            val response = header(0x43 or ((4 - value.size) shl 2), address)
            value.copyInto(response, 4)
            return response
        }
        segmentedData = value
        segmentedOffset = 0
        expectedToggle = 0
        val response = header(0x41, address)
        DataType.U32.encode(value.size.toLong()).copyInto(response, 4)
        return response
    }

    private fun uploadSegment(cs: Int): ByteArray {
        val data = segmentedData ?: return abort(ObjectAddress(0, 0), 0x0504_0001L)
        val toggle = cs and 0x10
        if (toggle != expectedToggle) return abort(ObjectAddress(0, 0), SdoAbortCodes.TOGGLE_BIT_NOT_ALTERNATED)
        val chunk = data.copyOfRange(segmentedOffset, minOf(segmentedOffset + 7, data.size))
        segmentedOffset += chunk.size
        val last = segmentedOffset >= data.size
        val response = ByteArray(8)
        response[0] = (toggle or ((7 - chunk.size) shl 1) or (if (last) 1 else 0)).toByte()
        chunk.copyInto(response, 1)
        expectedToggle = expectedToggle xor 0x10
        if (last) segmentedData = null
        return response
    }

    private fun download(address: ObjectAddress, cs: Int, request: ByteArray): ByteArray {
        if (cs and 0x02 == 0) return abort(address, 0x0504_0001L) // only expedited supported here
        val size = if (cs and 0x01 != 0) 4 - ((cs shr 2) and 0x03) else 4
        val data = request.copyOfRange(4, 4 + size)
        val raw = DataType.U32.decode(data)

        when (address) {
            Sevcon.USER_ID -> return ok(address)
            Sevcon.PASSWORD -> {
                accessLevel = when (raw) {
                    Sevcon.LOGIN_PASSWORD -> Sevcon.LEVEL_ENGINEERING
                    0L -> 0
                    else -> return generalError(address, 0x4681)
                }
                return ok(address)
            }
            Sevcon.NMT_REQUEST -> {
                if (accessLevel < Sevcon.LEVEL_ENGINEERING) return generalError(address, 0x4685)
                if (raw == 1L && refusePreOp) return generalError(address, 0x4686)
                nmtState = if (raw == 1L) 127 else 5
                return ok(address)
            }
        }

        val entry = dictionary[address] ?: return abort(address, SdoAbortCodes.OBJECT_DOES_NOT_EXIST)
        if (!entry.writable) return abort(address, SdoAbortCodes.READ_ONLY)
        if (cs and 0x01 != 0 && size != entry.size) return abort(address, SdoAbortCodes.LENGTH_MISMATCH)
        if (accessLevel < Sevcon.LEVEL_ENGINEERING) return generalError(address, 0x4685)
        if (entry.needsPreOp && nmtState != 127L) return generalError(address, 0x4688)
        if (address in failingWrites) return generalError(address, 0x46FF)
        if (!keepsMapOrder(address, raw)) return generalError(address, 0x4690)
        if (address == Sevcon.MAP_COMMIT) mapCommits++
        entry.bytes = data.copyOf(entry.size)
        return ok(address)
    }

    /**
     * "Param dyn range": a torque/speed map rpm must stay between its neighbours (as OVMS
     * observed on the real controller; the vendor code is made up).
     */
    private fun keepsMapOrder(address: ObjectAddress, rpm: Long): Boolean {
        if (address.index != 0x3813) return true
        val base = listOf(0x07, 0x1B, 0x24).firstOrNull { address.subIndex in it..it + 7 } ?: return true
        val offset = address.subIndex - base
        if (offset % 2 == 0) return true // level, not rpm
        val point = offset / 2
        fun rpmOf(p: Int) = value(ObjectAddress(0x3813, base + 2 * p + 1))
        val low = if (point == 0) 0 else rpmOf(point - 1)
        val high = if (point == 3) Long.MAX_VALUE else rpmOf(point + 1)
        return rpm in low..high
    }

    private fun ok(address: ObjectAddress) = header(0x60, address)

    private fun generalError(address: ObjectAddress, vendorCode: Long): ByteArray {
        lastError = vendorCode
        return abort(address, SdoAbortCodes.GENERAL_ERROR)
    }

    private fun abort(address: ObjectAddress, code: Long): ByteArray {
        val response = header(0x80, address)
        DataType.U32.encode(code).copyInto(response, 4)
        return response
    }

    private fun header(cs: Int, address: ObjectAddress) = byteArrayOf(
        cs.toByte(), address.index.toByte(), (address.index shr 8).toByte(), address.subIndex.toByte(), 0, 0, 0, 0,
    )

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun LongRange.coerce(v: Long) = v.coerceIn(first, last)

    private fun defaultString(address: ObjectAddress) = when (address.index) {
        0x1008 -> "Gen4 (Renault Twizy) [simulated]"
        0x1009 -> "0x00000003"
        0x100A -> "0712.0001"
        else -> ""
    }
}
