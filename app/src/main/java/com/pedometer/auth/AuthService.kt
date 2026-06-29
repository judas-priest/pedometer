package com.pedometer.auth

import android.util.Log
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AuthService(authKeyHex: String) {

    private val secretKey: ByteArray = parseAuthKey(authKeyHex)
    val phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private var decryptionKey = ByteArray(16)
    private var encryptionKey = ByteArray(16)
    private var decryptionNonce = ByteArray(4)
    private var encryptionNonce = ByteArray(4)

    var isInitialized = false
        private set

    var useV2Crypto = false

    fun processWatchNonce(watchNonce: ByteArray, watchHmac: ByteArray): Boolean {
        val derived = computeAuthKeys(secretKey, phoneNonce, watchNonce)
        System.arraycopy(derived, 0, decryptionKey, 0, 16)
        System.arraycopy(derived, 16, encryptionKey, 0, 16)
        System.arraycopy(derived, 32, decryptionNonce, 0, 4)
        System.arraycopy(derived, 36, encryptionNonce, 0, 4)

        val expectedHmac = hmacSha256(decryptionKey, watchNonce + phoneNonce)
        if (!expectedHmac.contentEquals(watchHmac)) return false

        isInitialized = true
        Log.i("AuthService", "Keys derived: encKey=${encryptionKey.joinToString("") { "%02x".format(it) }} decKey=${decryptionKey.joinToString("") { "%02x".format(it) }}")
        return true
    }

    fun getEncryptedNonces(watchNonce: ByteArray): ByteArray {
        return hmacSha256(encryptionKey, phoneNonce + watchNonce)
    }

    fun encrypt(data: ByteArray, counter: Int): ByteArray {
        if (useV2Crypto) return encryptCtr(encryptionKey, encryptionKey, data)
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(encryptionNonce).putInt(0).putInt(counter).array()
        return encryptCcm(encryptionKey, nonce, data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (useV2Crypto) return decryptCtr(decryptionKey, decryptionKey, data)
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(decryptionNonce).putInt(0).putInt(0).array()
        return decryptCcm(decryptionKey, nonce, data)
    }

    companion object {
        fun parseAuthKey(hex: String): ByteArray {
            val clean = hex.trim().removePrefix("0x")
            return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun computeAuthKeys(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
            val salt = "miwear-auth".toByteArray()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
            val hmacKey = mac.doFinal(secretKey)
            mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))

            val output = ByteArray(64)
            var tmp = ByteArray(0)
            var b: Byte = 1
            var i = 0
            while (i < output.size) {
                mac.update(tmp)
                mac.update(salt)
                mac.update(b)
                tmp = mac.doFinal()
                for (j in tmp.indices) {
                    if (i >= output.size) break
                    output[i++] = tmp[j]
                }
                b++
            }
            return output
        }

        fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(input)
        }

        fun encryptCcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
            val engine = AESEngine()
            val cipher = CCMBlockCipher(engine)
            cipher.init(true, AEADParameters(KeyParameter(key), 32, nonce, null))
            val out = ByteArray(cipher.getOutputSize(plaintext.size))
            val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            cipher.doFinal(out, len)
            return out
        }

        fun decryptCcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
            val engine = AESEngine()
            val cipher = CCMBlockCipher(engine)
            cipher.init(false, AEADParameters(KeyParameter(key), 32, nonce, null))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            cipher.doFinal(out, len)
            return out
        }

        fun encryptCtr(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(plaintext)
        }

        fun decryptCtr(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
