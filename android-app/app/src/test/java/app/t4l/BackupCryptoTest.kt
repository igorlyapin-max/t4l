package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val encrypted = BackupCrypto.encrypt("{\"formatVersion\":1}", "correct horse".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertEquals("{\"formatVersion\":1}", BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
    }

    @Test
    fun passwordIsClearedWhenDecryptionFails() {
        val encrypted = BackupCrypto.encrypt("{\"formatVersion\":2}", "correct".toCharArray())
        val wrong = "incorrect".toCharArray()
        try {
            BackupCrypto.decrypt(encrypted, wrong)
            fail("Expected invalid password failure")
        } catch (_: Exception) {
            assertTrue(wrong.all { it == '\u0000' })
        }
    }
}
