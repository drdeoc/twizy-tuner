package de.dressler.twizy.core

import de.dressler.twizy.core.params.DataType
import de.dressler.twizy.core.params.SafetyLimits
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun `signed values from the spreadsheet are normalized`() {
        assertEquals(-200L, SevconCatalog[0x2870, 0x03].defaultFor(ControllerType.TWIZY80))
    }

    @Test
    fun `power curve torque is in sixteenth Nm`() {
        val p = SevconCatalog[0x4611, 0x01]
        assertEquals(55.0, p.defaultFor(ControllerType.TWIZY80)!! * p.scale, 1e-9)
    }

    @Test
    fun `addresses are unique`() {
        assertEquals(SevconCatalog.all.size, SevconCatalog.all.map { it.address }.toSet().size)
    }

    @Test
    fun `every writable object is in the catalog and numeric`() {
        for (address in SafetyLimits.writableAddresses) {
            val p = SevconCatalog.find(address)
            assertTrue("$address missing from catalog", p != null)
            assertTrue("$address not numeric", p!!.type.isNumeric)
        }
    }

    @Test
    fun `factory defaults are inside the safe ranges`() {
        for (address in SafetyLimits.writableAddresses) {
            val p = SevconCatalog[address]
            for (type in ControllerType.entries) {
                val default = p.defaultFor(type) ?: continue
                val range = SafetyLimits.rangeFor(address, type)!!
                assertTrue("$p default $default for $type outside $range", default in range)
            }
        }
    }

    @Test
    fun `data types round trip little endian`() {
        assertEquals(-200L, DataType.S16.decode(DataType.S16.encode(-200)))
        assertEquals(0x0712302DL, DataType.U32.decode(bytes(0x2D, 0x30, 0x12, 0x07)))
        assertEquals(listOf(0x70, 0x03), DataType.U16.encode(880).map { it.toInt() and 0xFF })
    }
}
