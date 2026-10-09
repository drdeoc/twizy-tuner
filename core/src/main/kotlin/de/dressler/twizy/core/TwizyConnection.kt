package de.dressler.twizy.core

import de.dressler.twizy.core.canopen.SdoClient
import de.dressler.twizy.core.elm.Elm327
import de.dressler.twizy.core.sevcon.ControllerType
import de.dressler.twizy.core.sevcon.Sevcon
import de.dressler.twizy.core.transport.Transport
import de.dressler.twizy.core.write.PreOpGuard
import de.dressler.twizy.core.write.SafeWriter

data class ControllerInfo(
    val type: ControllerType,
    val productName: String,
    val hardwareVersion: String,
    val softwareVersion: String,
    val adapterVersion: String,
)

/** An initialized adapter talking to an identified SEVCON controller. */
class TwizyConnection private constructor(
    private val transport: Transport,
    val sevcon: Sevcon,
    val info: ControllerInfo,
) : AutoCloseable {

    fun writer(guard: PreOpGuard) = SafeWriter(sevcon, guard)

    override fun close() = transport.close()

    companion object {
        const val SEVCON_NODE_ID = 1

        suspend fun open(transport: Transport): TwizyConnection {
            try {
                val elm = Elm327(transport)
                elm.initialize(txId = 0x600 + SEVCON_NODE_ID, rxId = 0x580 + SEVCON_NODE_ID)
                val sevcon = Sevcon(SdoClient(elm))
                val info = ControllerInfo(
                    type = sevcon.identify(),
                    productName = sevcon.readString(Sevcon.PRODUCT_NAME),
                    hardwareVersion = sevcon.readString(Sevcon.HARDWARE_VERSION),
                    softwareVersion = sevcon.readString(Sevcon.SOFTWARE_VERSION),
                    adapterVersion = elm.version.orEmpty(),
                )
                return TwizyConnection(transport, sevcon, info)
            } catch (e: Exception) {
                transport.close()
                throw e
            }
        }
    }
}
