package app.t4l

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private const val PREFIX = "T4L-ENC-1:"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()

    fun encrypt(plainText: String, password: CharArray): String {
        require(password.isNotEmpty()) { "Backup password is required." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        password.fill('\u0000')
        val payload = ByteBuffer.allocate(salt.size + nonce.size + encrypted.size)
            .put(salt).put(nonce).put(encrypted).array()
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(container: String, password: CharArray): String {
        require(container.startsWith(PREFIX)) { "Unsupported backup format." }
        require(password.isNotEmpty()) { "Backup password is required." }
        val payload = Base64.getDecoder().decode(container.removePrefix(PREFIX))
        require(payload.size > SALT_BYTES + NONCE_BYTES) { "Corrupted backup." }
        val buffer = ByteBuffer.wrap(payload)
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(PREFIX.toByteArray(Charsets.UTF_8))
        val result = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        password.fill('\u0000')
        return result
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
