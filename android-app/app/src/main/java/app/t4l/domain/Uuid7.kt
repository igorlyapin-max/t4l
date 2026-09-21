package app.t4l.domain

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

object Uuid7 {
    private val random = SecureRandom()

    fun new(nowEpochMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = (nowEpochMs ushr 40).toByte()
        bytes[1] = (nowEpochMs ushr 32).toByte()
        bytes[2] = (nowEpochMs ushr 24).toByte()
        bytes[3] = (nowEpochMs ushr 16).toByte()
        bytes[4] = (nowEpochMs ushr 8).toByte()
        bytes[5] = nowEpochMs.toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
