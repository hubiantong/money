package top.tongtong.money.data.prefs

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object LockManager {
    // 注意：hashPin/verify 是约 100-300ms 的 CPU 密集操作，不要在主线程调用。
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256

    private val secureRandom = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun hashPin(pin: String, saltBase64: String): String {
        val hash = hashPinBytes(pin, saltBase64)
            ?: throw IllegalArgumentException("解锁 PIN 的盐值无效")
        return Base64.getEncoder().encodeToString(hash)
    }

    fun verify(pin: String, saltBase64: String, expectedHash: String): Boolean {
        if (saltBase64.isBlank() || expectedHash.isBlank()) return false
        val expected = runCatching { Base64.getDecoder().decode(expectedHash) }.getOrNull() ?: return false
        val actual = hashPinBytes(pin, saltBase64) ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    private fun hashPinBytes(pin: String, saltBase64: String): ByteArray? {
        val salt = runCatching { Base64.getDecoder().decode(saltBase64) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val pinChars = pin.toCharArray()
        return try {
            val spec = PBEKeySpec(pinChars, salt, ITERATIONS, KEY_LENGTH)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } catch (_: GeneralSecurityException) {
                null
            } finally {
                spec.clearPassword()
            }
        } catch (_: IllegalArgumentException) {
            // 盐值或密钥长度非法（例如空盐解码结果）时视为哈希失败
            null
        } finally {
            pinChars.fill('\u0000')
        }
    }
}
