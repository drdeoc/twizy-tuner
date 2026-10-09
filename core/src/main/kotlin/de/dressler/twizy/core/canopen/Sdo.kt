package de.dressler.twizy.core.canopen

/** Sends one 8-byte CAN frame to the SDO server and returns its 8-byte answer. */
fun interface CanChannel {
    suspend fun exchange(request: ByteArray): ByteArray
}

/** An object dictionary entry, written as "4611:01". */
data class ObjectAddress(val index: Int, val subIndex: Int) {
    init {
        require(index in 0..0xFFFF) { "Index out of range: $index" }
        require(subIndex in 0..0xFF) { "Sub-index out of range: $subIndex" }
    }

    override fun toString() = "%04X:%02X".format(index, subIndex)

    companion object {
        /** Parses the [toString] form, e.g. "4611:01". */
        fun parse(text: String): ObjectAddress {
            val parts = text.split(':')
            require(parts.size == 2) { "Not an object address: $text" }
            return ObjectAddress(parts[0].toInt(16), parts[1].toInt(16))
        }
    }
}

open class SdoException(message: String) : Exception(message)

/** The server answered with a CiA 301 abort transfer frame. */
class SdoAbortException(
    val address: ObjectAddress,
    val code: Long,
    /** SEVCON specific reason from object 0x5310, read after a general error. */
    val vendorCode: Long? = null,
) : SdoException(
    "SDO abort on $address: 0x%08X %s".format(code, SdoAbortCodes.describe(code)) +
        (vendorCode?.let { " (SEVCON 0x5310 = 0x%04X)".format(it) } ?: "")
) {
    fun withVendorCode(vendorCode: Long) = SdoAbortException(address, code, vendorCode)
}

/** The answer did not fit the protocol (wrong command specifier, wrong object, toggle mismatch). */
class SdoProtocolException(message: String) : SdoException(message)

/** SDO abort codes, CiA 301 v4.2 Table 22. */
object SdoAbortCodes {
    const val TOGGLE_BIT_NOT_ALTERNATED = 0x0503_0000L
    const val OUT_OF_MEMORY = 0x0504_0005L
    const val UNSUPPORTED_ACCESS = 0x0601_0000L
    const val WRITE_ONLY = 0x0601_0001L
    const val READ_ONLY = 0x0601_0002L
    const val OBJECT_DOES_NOT_EXIST = 0x0602_0000L
    const val LENGTH_MISMATCH = 0x0607_0010L
    const val SUB_INDEX_DOES_NOT_EXIST = 0x0609_0011L
    const val VALUE_TOO_HIGH = 0x0609_0031L
    const val VALUE_TOO_LOW = 0x0609_0032L
    const val GENERAL_ERROR = 0x0800_0000L
    const val DEVICE_STATE = 0x0800_0022L

    private val descriptions = mapOf(
        0x0503_0000L to "Toggle bit not alternated",
        0x0504_0000L to "SDO protocol timed out",
        0x0504_0001L to "Client/server command specifier not valid or unknown",
        0x0504_0002L to "Invalid block size",
        0x0504_0003L to "Invalid sequence number",
        0x0504_0004L to "CRC error",
        0x0504_0005L to "Out of memory",
        0x0601_0000L to "Unsupported access to an object",
        0x0601_0001L to "Attempt to read a write only object",
        0x0601_0002L to "Attempt to write a read only object",
        0x0602_0000L to "Object does not exist in the object dictionary",
        0x0604_0041L to "Object cannot be mapped to the PDO",
        0x0604_0042L to "PDO length exceeded",
        0x0604_0043L to "General parameter incompatibility",
        0x0604_0047L to "General internal incompatibility in the device",
        0x0606_0000L to "Access failed due to a hardware error",
        0x0607_0010L to "Data type does not match, length of service parameter does not match",
        0x0607_0012L to "Data type does not match, length of service parameter too high",
        0x0607_0013L to "Data type does not match, length of service parameter too low",
        0x0609_0011L to "Sub-index does not exist",
        0x0609_0030L to "Invalid value for parameter",
        0x0609_0031L to "Value of parameter written too high",
        0x0609_0032L to "Value of parameter written too low",
        0x0609_0036L to "Maximum value is less than minimum value",
        0x060A_0023L to "Resource not available: SDO connection",
        0x0800_0000L to "General error",
        0x0800_0020L to "Data cannot be transferred or stored to the application",
        0x0800_0021L to "Data cannot be transferred or stored because of local control",
        0x0800_0022L to "Data cannot be transferred or stored because of the present device state",
        0x0800_0023L to "Object dictionary dynamic generation failed",
        0x0800_0024L to "No data available",
    )

    fun describe(code: Long): String = descriptions[code] ?: "Unknown abort code"
}
