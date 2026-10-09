package de.dressler.twizy.core.write

import de.dressler.twizy.core.params.Parameter
import de.dressler.twizy.core.params.SafetyLimits
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.NmtState
import de.dressler.twizy.core.sevcon.Sevcon

/** Asks the driver whether the car is safe for pre-operational mode (motor off). */
fun interface PreOpGuard {
    /** Must only return true if the vehicle is stationary, in N and not in GO. */
    suspend fun confirmVehicleSafe(): Boolean
}

data class WriteRequest(val parameter: Parameter, val value: Long)

data class PlannedChange(val parameter: Parameter, val oldValue: Long, val newValue: Long)

/** A validated set of changes, including the current values that serve as backup. */
class WritePlan internal constructor(
    val controller: ControllerType,
    val changes: List<PlannedChange>,
) {
    val requiresPreOp: Boolean get() = changes.any { it.parameter.requiresPreOp }
    val touchesMaps: Boolean get() = changes.any { it.parameter.address.index in MAP_INDICES }
    val isEmpty: Boolean get() = changes.isEmpty()

    private companion object {
        val MAP_INDICES = setOf(0x4610, 0x4611)
    }
}

class UnsafeWriteException(val problems: List<String>) :
    Exception("Refusing to write: " + problems.joinToString("; "))

sealed interface WriteResult {
    data class Success(val changes: List<PlannedChange>) : WriteResult

    data class Failed(
        val cause: Throwable,
        /** Changes that were written and then restored to their old value. */
        val rolledBack: List<PlannedChange>,
        /** Changes whose old value could not be restored. The controller needs attention. */
        val rollbackFailures: List<Pair<PlannedChange, Throwable>>,
        /** False if the controller could not be returned to operational mode. */
        val operationalRestored: Boolean,
    ) : WriteResult
}

/**
 * The only way to change controller parameters.
 *
 * 1. [prepare]: identify the controller, check every value against [SafetyLimits]
 *    and read the current values (preview and backup).
 * 2. [execute]: log in, enter pre-operational mode if needed (after asking the [PreOpGuard]),
 *    write and read back every value, commit maps, return to operational mode.
 *    If anything fails, the values written so far are restored.
 */
class SafeWriter(
    private val sevcon: Sevcon,
    private val guard: PreOpGuard,
) {

    suspend fun prepare(requests: List<WriteRequest>): WritePlan {
        val controller = sevcon.identify()
        val problems = validate(controller, requests)
        if (problems.isNotEmpty()) throw UnsafeWriteException(problems)
        val changes = requests.map { PlannedChange(it.parameter, sevcon.read(it.parameter), it.value) }
            .filter { it.oldValue != it.newValue }
        return WritePlan(controller, changes)
    }

    suspend fun execute(plan: WritePlan): WriteResult {
        if (plan.isEmpty) return WriteResult.Success(emptyList())
        try {
            val controller = sevcon.identify()
            if (controller != plan.controller) error("Plan was made for ${plan.controller}, but connected to $controller")
            sevcon.login()
            for (change in plan.changes) {
                val current = sevcon.read(change.parameter)
                if (current != change.oldValue) {
                    error("${change.parameter} changed from ${change.oldValue} to $current since the plan was made")
                }
            }
        } catch (e: Exception) {
            return WriteResult.Failed(e, emptyList(), emptyList(), operationalRestored = true)
        }

        var enteredPreOp = false
        if (plan.requiresPreOp) {
            try {
                if (sevcon.nmtState() != NmtState.PRE_OPERATIONAL) {
                    if (!guard.confirmVehicleSafe()) error("Driver did not confirm that the vehicle is safe")
                    enteredPreOp = true
                    sevcon.enterPreOperational()
                }
            } catch (e: Exception) {
                return WriteResult.Failed(e, emptyList(), emptyList(), restoreOperational(enteredPreOp))
            }
        }

        val written = mutableListOf<PlannedChange>()
        val result = try {
            for (change in plan.changes) {
                written += change
                writeAndVerify(change.parameter, change.newValue)
            }
            if (plan.touchesMaps) sevcon.commitMaps()
            WriteResult.Success(plan.changes)
        } catch (e: Exception) {
            val failures = rollBack(written, plan.touchesMaps)
            WriteResult.Failed(e, written - failures.map { it.first }.toSet(), failures, operationalRestored = true)
        }

        val operational = restoreOperational(enteredPreOp)
        return when {
            result is WriteResult.Failed -> result.copy(operationalRestored = operational)
            !operational -> WriteResult.Failed(
                IllegalStateException("Values written, but the controller did not return to operational mode"),
                emptyList(), emptyList(), operationalRestored = false,
            )
            else -> result
        }
    }

    private suspend fun writeAndVerify(parameter: Parameter, value: Long) {
        sevcon.write(parameter.address, parameter.type, value)
        val readBack = sevcon.read(parameter)
        if (readBack != value) error("$parameter: wrote $value but read back $readBack")
    }

    private suspend fun rollBack(written: List<PlannedChange>, commitMaps: Boolean): List<Pair<PlannedChange, Throwable>> {
        val failures = mutableListOf<Pair<PlannedChange, Throwable>>()
        for (change in written.asReversed()) {
            runCatching {
                // The failed write may not have changed anything, and may fail again.
                if (sevcon.read(change.parameter) != change.oldValue) writeAndVerify(change.parameter, change.oldValue)
            }.onFailure { failures += change to it }
        }
        if (commitMaps) runCatching { sevcon.commitMaps() }
        return failures
    }

    private suspend fun restoreOperational(enteredPreOp: Boolean): Boolean =
        !enteredPreOp || runCatching { sevcon.enterOperational() }.isSuccess

    companion object {
        fun validate(controller: ControllerType, requests: List<WriteRequest>): List<String> {
            val problems = mutableListOf<String>()
            val duplicates = requests.groupBy { it.parameter.address }.filterValues { it.size > 1 }.keys
            duplicates.forEach { problems += "$it is written more than once" }
            for ((parameter, value) in requests) {
                val range = SafetyLimits.rangeFor(parameter.address, controller)
                when {
                    !parameter.type.isNumeric -> problems += "$parameter is not numeric"
                    range == null -> problems += "$parameter is read-only"
                    value !in range -> problems += "$parameter: $value is outside the safe range ${range.first}..${range.last}"
                    value !in parameter.type.range -> problems += "$parameter: $value does not fit into ${parameter.type}"
                }
            }
            return problems
        }
    }
}
