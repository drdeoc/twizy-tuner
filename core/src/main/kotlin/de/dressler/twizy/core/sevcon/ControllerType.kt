package de.dressler.twizy.core.sevcon

import kotlinx.serialization.Serializable

/** Identified by object 0x1018:02 (identity object, product code). */
@Serializable
enum class ControllerType(val productCode: Long, val label: String) {
    TWIZY80(0x0712_302DL, "Twizy 80 (SEVCON Gen4, 48 V, 450 A)"),
    TWIZY45(0x0712_301BL, "Twizy 45 (SEVCON Gen4, 48 V, 275 A)");

    companion object {
        fun fromProductCode(code: Long): ControllerType? = entries.firstOrNull { it.productCode == code }
    }
}
