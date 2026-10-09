package de.dressler.twizy.core.sevcon

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.canopen.SdoAbortCodes
import de.dressler.twizy.core.canopen.SdoAbortException
import de.dressler.twizy.core.canopen.SdoClient
import de.dressler.twizy.core.params.DataType
import de.dressler.twizy.core.params.Parameter
import kotlinx.coroutines.delay

class SevconException(message: String) : Exception(message)

/** CANopen NMT state as reported by SEVCON object 0x5110:00. */
enum class NmtState(val code: Long) {
    OPERATIONAL(5), PRE_OPERATIONAL(127), UNKNOWN(-1);

    companion object {
        fun of(code: Long) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * The SEVCON Gen4 motor controller of the Twizy (CANopen node 1).
 *
 * Login, identification and NMT state handling follow the OVMS implementation
 * (vehicle_twizy_login / vehicle_twizy_configmode).
 */
class Sevcon(private val sdo: SdoClient) {

    suspend fun readBytes(address: ObjectAddress): ByteArray = withVendorError { sdo.upload(address) }

    suspend fun read(parameter: Parameter): Long = parameter.type.decode(readBytes(parameter.address))

    suspend fun readString(address: ObjectAddress): String =
        readBytes(address).toString(Charsets.ISO_8859_1).trimEnd('\u0000', ' ')

    /** Raw write without any safety checks. Use [de.dressler.twizy.core.write.SafeWriter] instead. */
    internal suspend fun write(address: ObjectAddress, type: DataType, value: Long) =
        withVendorError { sdo.download(address, type.encode(value)) }

    suspend fun identify(): ControllerType {
        val code = DataType.U32.decode(readBytes(IDENTITY_PRODUCT_CODE))
        return ControllerType.fromProductCode(code)
            ?: throw SevconException("Unknown controller 0x%08X, neither Twizy 80 nor Twizy 45".format(code))
    }

    suspend fun accessLevel(): Long = DataType.U16.decode(readBytes(ACCESS_LEVEL))

    /** Logs in with access level 4, which is needed for writing parameters. */
    suspend fun login() {
        if (accessLevel() == LEVEL_ENGINEERING) return
        write(USER_ID, DataType.U16, 0)
        write(PASSWORD, DataType.U16, LOGIN_PASSWORD)
        val level = accessLevel()
        if (level != LEVEL_ENGINEERING) throw SevconException("Login failed, access level is $level instead of 4")
    }

    suspend fun logout() {
        if (accessLevel() == 0L) return
        write(USER_ID, DataType.U16, 0)
        write(PASSWORD, DataType.U16, 0)
        val level = accessLevel()
        if (level != 0L) throw SevconException("Logout failed, access level is still $level")
    }

    suspend fun nmtState(): NmtState = NmtState.of(DataType.U8.decode(readBytes(NMT_STATE)))

    /**
     * Requests pre-operational mode. The motor is disabled in this state, so the caller
     * must make sure the vehicle is stationary, in neutral and not in GO.
     */
    suspend fun enterPreOperational() {
        if (nmtState() == NmtState.PRE_OPERATIONAL) return
        write(NMT_REQUEST, DataType.U8, 1)
        if (!awaitState(NmtState.PRE_OPERATIONAL)) {
            write(NMT_REQUEST, DataType.U8, 0) // withdraw the request like OVMS does
            throw SevconException("Controller did not enter pre-operational mode")
        }
    }

    suspend fun enterOperational(attempts: Int = 5) {
        repeat(attempts) {
            runCatching {
                write(NMT_REQUEST, DataType.U8, 0)
                if (awaitState(NmtState.OPERATIONAL)) return
            }
            delay(RETRY_DELAY_MILLIS)
        }
        throw SevconException("Controller did not return to operational mode")
    }

    /** Makes changed torque/flux maps (0x4610, 0x4611) effective. */
    internal suspend fun commitMaps() = write(MAP_COMMIT, DataType.U8, 1)

    private suspend fun awaitState(expected: NmtState): Boolean {
        repeat(STATE_POLLS) {
            delay(STATE_POLL_DELAY_MILLIS)
            if (nmtState() == expected) return true
        }
        return false
    }

    /** Adds the SEVCON specific reason (0x5310:00) to CANopen general errors, as OVMS does. */
    private suspend fun <T> withVendorError(block: suspend () -> T): T = try {
        block()
    } catch (e: SdoAbortException) {
        if (e.code != SdoAbortCodes.GENERAL_ERROR || e.address == LAST_ERROR) throw e
        val vendor = runCatching { DataType.U16.decode(sdo.upload(LAST_ERROR)) }.getOrNull()
        throw if (vendor != null) e.withVendorCode(vendor) else e
    }

    companion object {
        val IDENTITY_PRODUCT_CODE = ObjectAddress(0x1018, 0x02)
        val PRODUCT_NAME = ObjectAddress(0x1008, 0x00)
        val HARDWARE_VERSION = ObjectAddress(0x1009, 0x00)
        val SOFTWARE_VERSION = ObjectAddress(0x100A, 0x00)
        val ACCESS_LEVEL = ObjectAddress(0x5000, 0x01)
        val PASSWORD = ObjectAddress(0x5000, 0x02)
        val USER_ID = ObjectAddress(0x5000, 0x03)
        val NMT_REQUEST = ObjectAddress(0x2800, 0x00)
        val NMT_STATE = ObjectAddress(0x5110, 0x00)
        val LAST_ERROR = ObjectAddress(0x5310, 0x00)
        val MAP_COMMIT = ObjectAddress(0x4641, 0x01)

        const val LEVEL_ENGINEERING = 4L
        const val LOGIN_PASSWORD = 0x4BDFL

        private const val STATE_POLLS = 10
        private const val STATE_POLL_DELAY_MILLIS = 50L
        private const val RETRY_DELAY_MILLIS = 100L
    }
}
