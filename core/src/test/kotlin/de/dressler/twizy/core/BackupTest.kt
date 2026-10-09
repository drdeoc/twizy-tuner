package de.dressler.twizy.core

import de.dressler.twizy.core.backup.Backup
import de.dressler.twizy.core.backup.BackupService
import de.dressler.twizy.core.backup.BackupStore
import de.dressler.twizy.core.backup.RestorePlanner
import de.dressler.twizy.core.canopen.ObjectAddress
import de.dressler.twizy.core.params.SafetyLimits
import de.dressler.twizy.core.params.SevconCatalog
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter
import de.dressler.twizy.core.write.WriteRequest
import de.dressler.twizy.core.write.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val maxRpm = SevconCatalog[0x2920, 0x05]
    private val torque1 = SevconCatalog[0x4611, 0x01]

    private suspend fun backupOf(stack: SimStack): Backup {
        val info = ControllerInfo(stack.sevcon.identify(), "p", "h", "s", "elm")
        return BackupService(stack.sevcon).create(info, "2026-10-09T18:00:00Z")
    }

    private fun writer(stack: SimStack) = SafeWriter(stack.sevcon, PreOpGuard { true })

    @Test
    fun `backup contains every catalog object with decoded values`() = runTest {
        val backup = backupOf(SimStack().init())
        assertEquals(SevconCatalog.all.size, backup.entries.size)
        assertEquals(0, backup.readErrors)
        assertEquals(7_250L, backup.entry(maxRpm.address)!!.value)
        assertEquals(-200L, backup.entry(ObjectAddress(0x2870, 0x03))!!.value)
        assertEquals("0712.0001", backup.entry(ObjectAddress(0x100A, 0))!!.text)
    }

    @Test
    fun `unreadable objects are recorded instead of aborting`() = runTest {
        val stack = SimStack().init()
        val catalog = SevconCatalog.all.take(3) + SevconCatalog.all.first().copy(address = ObjectAddress(0x7777, 1), name = "missing")
        val backup = BackupService(stack.sevcon, catalog).create(ControllerInfo(ControllerType.TWIZY80, "", "", "", ""), "t")
        assertEquals(1, backup.readErrors)
        assertTrue(backup.entries.last().error!!.contains("does not exist"))
    }

    @Test
    fun `json round trip`() = runTest {
        val backup = backupOf(SimStack().init())
        assertEquals(backup, Backup.fromJson(backup.toJson()))
    }

    @Test
    fun `store saves, lists newest first and never overwrites`() {
        val store = BackupStore(tmp.root)
        val a = Backup(createdAt = "2026-10-09T10:00:00Z", controller = ControllerType.TWIZY80, productName = "", softwareVersion = "", hardwareVersion = "", entries = emptyList())
        val b = a.copy(createdAt = "2026-10-09T11:00:00Z")
        val f1 = store.save(a)
        val f2 = store.save(a)
        store.save(b)
        assertTrue(f1 != f2)
        assertEquals(listOf("2026-10-09T11:00:00Z", "2026-10-09T10:00:00Z", "2026-10-09T10:00:00Z"), store.list().map { it.backup.createdAt })
        assertTrue(store.hasBackupFor(ControllerType.TWIZY80))
        assertTrue(!store.hasBackupFor(ControllerType.TWIZY45))
        assertTrue(store.delete(f1))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `restore brings a tuned controller back to the backup`() = runTest {
        val stack = SimStack().init()
        val backup = backupOf(stack)
        val writer = writer(stack)
        writer.execute(writer.prepare(listOf(WriteRequest(maxRpm, 8_338), WriteRequest(torque1, 1_000))))
        assertEquals(8_338L, stack.sim.value(maxRpm.address))

        val selection = RestorePlanner.fromBackup(backup, ControllerType.TWIZY80)
        assertTrue(selection.skipped.isEmpty())
        val plan = writer.prepare(selection.requests)
        assertEquals(setOf(maxRpm, torque1), plan.changes.map { it.parameter }.toSet())

        val result = writer.execute(plan)
        assertTrue(result is WriteResult.Success)
        assertEquals(7_250L, stack.sim.value(maxRpm.address))
        assertEquals(880L, stack.sim.value(torque1.address))
        assertEquals(NmtState.OPERATIONAL.code, stack.sim.state)
    }

    @Test
    fun `restore refuses a backup from the other Twizy model`() = runTest {
        val backup = backupOf(SimStack(ControllerType.TWIZY45).init())
        assertThrows(IllegalArgumentException::class.java) { RestorePlanner.fromBackup(backup, ControllerType.TWIZY80) }
    }

    @Test
    fun `values outside the safe range are skipped, not restored`() = runTest {
        val backup = backupOf(SimStack().init())
        val boosted = backup.copy(entries = backup.entries.map {
            if (it.address == "6075:00") it.copy(value = 540_000) else it
        })
        val selection = RestorePlanner.fromBackup(boosted, ControllerType.TWIZY80)
        assertEquals(listOf(ObjectAddress(0x6075, 0)), selection.skipped.map { it.address })
        assertNull(selection.requests.firstOrNull { it.parameter.address == ObjectAddress(0x6075, 0) })
    }

    @Test
    fun `factory defaults cover every writable object for both models`() {
        for (type in ControllerType.entries) {
            val selection = RestorePlanner.factoryDefaults(type)
            assertEquals("$type: ${selection.skipped}", emptyList<Any>(), selection.skipped)
            assertEquals(SafetyLimits.writableAddresses.size, selection.requests.size)
        }
    }

    @Test
    fun `factory defaults on an untouched controller change nothing`() = runTest {
        for (type in ControllerType.entries) {
            val stack = SimStack(type).init()
            val plan = writer(stack).prepare(RestorePlanner.factoryDefaults(type).requests)
            assertTrue("$type: ${plan.changes}", plan.isEmpty)
        }
    }

    @Test
    fun `a failing restore leaves the controller as it was`() = runTest {
        val stack = SimStack().init()
        val backup = backupOf(stack)
        val writer = writer(stack)
        writer.execute(writer.prepare(listOf(WriteRequest(maxRpm, 8_338), WriteRequest(torque1, 1_000))))
        stack.sim.failingWrites += torque1.address

        val result = runBlocking { writer.execute(writer.prepare(RestorePlanner.fromBackup(backup, ControllerType.TWIZY80).requests)) }

        assertTrue(result is WriteResult.Failed)
        assertEquals(8_338L, stack.sim.value(maxRpm.address)) // rolled back to the tuned state
        assertEquals(1_000L, stack.sim.value(torque1.address))
    }
}
