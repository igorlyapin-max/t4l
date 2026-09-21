package app.t4l.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

object DeterministicIds {
    fun budgetAllocation(workspaceId: String, planId: String, categoryId: String): String {
        val name = "budget-allocation:${workspaceId.lowercase()}:${planId.lowercase()}:${categoryId.lowercase()}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
