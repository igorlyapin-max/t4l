package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val encrypted = BackupCrypto.encrypt("{\"formatVersion\":1}", "correct horse".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertEquals("{\"formatVersion\":1}", BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
    }
}
