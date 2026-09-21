package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {
    @Test
    fun `debug accepts configured LAN URL and normalizes trailing slash`() {
        assertEquals(
            "http://192.168.202.35:5099/",
            ServerUrlPolicy.normalize(" http://192.168.202.35:5099 ", debug = true),
        )
    }

    @Test
    fun `debug rejects arbitrary cleartext host`() {
        assertNull(ServerUrlPolicy.normalize("http://example.com/", debug = true))
    }

    @Test
    fun `debug accepts RFC1918 addresses but rejects public literal IP`() {
        assertEquals("http://10.42.0.7:5099/", ServerUrlPolicy.normalize("http://10.42.0.7:5099", debug = true))
        assertEquals("http://172.31.2.9/", ServerUrlPolicy.normalize("http://172.31.2.9", debug = true))
        assertNull(ServerUrlPolicy.normalize("http://8.8.8.8/", debug = true))
    }

    @Test
    fun `release accepts HTTPS and rejects HTTP`() {
        assertEquals("https://api.example.com/", ServerUrlPolicy.normalize("https://api.example.com", debug = false))
        assertNull(ServerUrlPolicy.normalize("http://192.168.202.35:5099", debug = false))
    }

    @Test
    fun `URL rejects credentials query and fragment`() {
        assertNull(ServerUrlPolicy.normalize("https://user:secret@example.com/", debug = false))
        assertNull(ServerUrlPolicy.normalize("https://example.com/?token=secret", debug = false))
        assertNull(ServerUrlPolicy.normalize("https://example.com/#fragment", debug = false))
    }

    @Test
    fun `sync delays and default match product contract`() {
        assertEquals(listOf(0L, 5L, 15L, 30L, 60L, 300L), SyncDelay.entries.map(SyncDelay::seconds))
        assertEquals(SyncDelay.THIRTY_SECONDS, DEFAULT_SYNC_DELAY)
    }
}
