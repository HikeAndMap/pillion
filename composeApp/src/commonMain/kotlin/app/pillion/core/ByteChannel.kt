package app.pillion.core

/**
 * A bidirectional raw byte link to the dash. The engine depends on this abstraction (DIP);
 * platforms provide the concrete implementation (Android RFCOMM, iOS EASession).
 */
/**
 * [ByteChannel.open] could not reach the dash (bike off, out of range, still booting) — as opposed to
 * a setup problem such as the dash not being paired. The engine keeps retrying this one.
 */
class DashUnreachableException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ByteChannel {
    fun open()
    fun write(bytes: ByteArray)
    /** Blocking read of up to [buffer].size bytes; returns count read (>0) or -1 when closed. */
    fun read(buffer: ByteArray): Int
    fun close()
}
