package de.dressler.twizy.core.transport

/**
 * A raw byte pipe to an ELM327 adapter (Bluetooth SPP on Android, a simulator in tests).
 */
interface Transport : AutoCloseable {
    suspend fun write(data: ByteArray)

    /** Returns the next received byte, or null if nothing arrives within [timeoutMillis]. */
    suspend fun read(timeoutMillis: Long): Byte?

    /** Discards everything that is currently pending on the input side. */
    suspend fun drain() {
        while (read(DRAIN_TIMEOUT_MILLIS) != null) Unit
    }

    companion object {
        const val DRAIN_TIMEOUT_MILLIS = 20L
    }
}
