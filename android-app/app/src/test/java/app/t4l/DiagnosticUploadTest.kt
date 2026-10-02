package app.t4l

import app.t4l.data.HttpStatusException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadTest {
    private fun event(name: String) = PendingDiagnosticEvent("2026-09-30T00:00:00Z", "basic", name, emptyMap())

    @Test fun invalidMiddleEventDoesNotDiscardItsNeighbors() = runTest {
        val queue = mutableListOf(event("sync_started"), event("unknown"), event("sync_succeeded"))
        val received = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val result = uploadDiagnosticEvents(queue.toList(), send = { batch ->
            if (batch.any { it.eventName == "unknown" }) throw HttpStatusException(400)
            received += batch.map { it.eventName }
        }, acknowledge = { acknowledged -> repeat(acknowledged.size) { queue.removeAt(0) } }, rejected = { rejected += it.eventName })

        assertTrue(result)
        assertEquals(listOf("sync_started", "sync_succeeded"), received)
        assertEquals(listOf("unknown"), rejected)
        assertTrue(queue.isEmpty())
    }

    @Test fun transientErrorPreservesUnsentSuffix() = runTest {
        val queue = mutableListOf(event("sync_started"), event("unknown"), event("sync_succeeded"))
        val received = mutableListOf<String>()
        val result = uploadDiagnosticEvents(queue.toList(), send = { batch ->
            if (batch.size > 1 || batch.single().eventName == "unknown") throw HttpStatusException(400)
            if (batch.single().eventName == "sync_succeeded") throw HttpStatusException(503)
            received += batch.single().eventName
        }, acknowledge = { acknowledged -> repeat(acknowledged.size) { queue.removeAt(0) } }, rejected = {})

        assertFalse(result)
        assertEquals(listOf("sync_started"), received)
        assertEquals(listOf("sync_succeeded"), queue.map { it.eventName })
    }
}
