package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.SdoClient
import de.dressler.twizy.core.elm.Elm327
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.sim.SimulatedElm327Transport
import de.dressler.twizy.core.sim.SimulatedSevcon
import de.dressler.twizy.core.sim.SimulatedVehicle

/** The full stack (ELM327 driver, SDO client, SEVCON service) on top of the simulator. */
class SimStack(type: ControllerType = ControllerType.TWIZY80) {
    val sim = SimulatedSevcon(type)
    val vehicle = SimulatedVehicle()
    val transport = SimulatedElm327Transport(sim, vehicle)
    val elm = Elm327(transport)
    val sevcon = Sevcon(SdoClient(elm))

    suspend fun init(): SimStack {
        elm.initialize(0x601, 0x581)
        return this
    }
}

fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
