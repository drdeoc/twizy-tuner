package de.dressler.twizy.core.params

import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.sevcon.ControllerType

enum class DataType(val size: Int, val range: LongRange) {
    U8(1, 0L..0xFFL),
    U16(2, 0L..0xFFFFL),
    S16(2, -0x8000L..0x7FFFL),
    U32(4, 0L..0xFFFF_FFFFL),
    S32(4, Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()),
    STRING(0, LongRange.EMPTY);

    val isNumeric get() = this != STRING

    /** Little-endian wire format. */
    fun encode(value: Long): ByteArray {
        check(isNumeric) { "$this cannot be encoded as a number" }
        require(value in range) { "$value does not fit into $this" }
        return ByteArray(size) { (value shr (8 * it)).toByte() }
    }

    /** Decodes a little-endian value. Shorter answers are zero-extended, longer ones truncated. */
    fun decode(bytes: ByteArray): Long {
        check(isNumeric) { "$this cannot be decoded as a number" }
        var raw = 0L
        for (i in 0 until minOf(size, bytes.size)) raw = raw or ((bytes[i].toLong() and 0xFF) shl (8 * i))
        return when (this) {
            S16 -> raw.toShort().toLong()
            S32 -> raw.toInt().toLong()
            else -> raw
        }
    }
}

data class Parameter(
    val address: ObjectAddress,
    val name: String,
    val type: DataType,
    val defaults: Map<ControllerType, Long>,
    /** Display unit; display value = raw value * [scale]. */
    val unit: String,
    val scale: Double,
    /** Writing requires the controller to be pre-operational (according to OVMS). */
    val requiresPreOp: Boolean,
    val tags: Set<String>,
    val remark: String,
) {
    fun defaultFor(type: ControllerType): Long? = defaults[type]

    fun format(raw: Long): String {
        if (scale == 1.0) return if (unit.isEmpty()) "$raw" else "$raw $unit"
        val value = "%.3f".format(raw * scale).trimEnd('0').trimEnd('.', ',')
        return "$value $unit (raw $raw)"
    }

    override fun toString() = "$address $name"
}

internal fun param(
    index: Int,
    subIndex: Int,
    name: String,
    type: DataType,
    default80: Long?,
    default45: Long?,
    unit: String,
    scale: Double,
    preOp: Boolean,
    tags: String,
    remark: String,
): Parameter {
    // The spreadsheet lists signed values as their unsigned 16 bit pattern (65336 = -200).
    fun normalize(v: Long) = if (type == DataType.S16 || type == DataType.S32) type.decode(type.copyBits(v)) else v
    return Parameter(
        address = ObjectAddress(index, subIndex),
        name = name,
        type = type,
        defaults = buildMap {
            default80?.let { put(ControllerType.TWIZY80, normalize(it)) }
            default45?.let { put(ControllerType.TWIZY45, normalize(it)) }
        },
        unit = unit,
        scale = scale,
        requiresPreOp = preOp,
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        remark = remark,
    )
}

private fun DataType.copyBits(v: Long) = ByteArray(size) { (v shr (8 * it)).toByte() }
