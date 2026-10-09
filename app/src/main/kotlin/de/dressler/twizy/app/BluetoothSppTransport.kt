package de.dressler.twizy.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import de.dressler.twizy.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** ELM327 adapter over Bluetooth Classic serial port profile (RFCOMM). */
class BluetoothSppTransport private constructor(private val socket: BluetoothSocket) : Transport {

    private val incoming = Channel<Byte>(Channel.UNLIMITED)

    init {
        thread(isDaemon = true, name = "spp-reader") {
            val buffer = ByteArray(256)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    for (i in 0 until n) incoming.trySend(buffer[i])
                }
            } catch (_: IOException) {
                // socket closed
            } finally {
                incoming.close()
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        socket.outputStream.write(data)
        socket.outputStream.flush()
    }

    override suspend fun read(timeoutMillis: Long): Byte? {
        val result = withTimeoutOrNull(timeoutMillis) { incoming.receiveCatching() } ?: return null
        return result.getOrNull() ?: throw IOException("Bluetooth connection lost")
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun connect(device: BluetoothDevice): BluetoothSppTransport = withContext(Dispatchers.IO) {
            // Many cheap ELM327 clones only accept insecure RFCOMM connections.
            val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            BluetoothSppTransport(socket)
        }
    }
}
