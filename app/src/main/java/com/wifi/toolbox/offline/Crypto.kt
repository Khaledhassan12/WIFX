package com.wifi.toolbox.offline

import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal, dependency-free crypto helpers used by the offline WPA auditor.
 *
 * Everything here is plain JVM/Android (javax.crypto) so it can be unit tested
 * on a desktop JVM and still run on the phone.
 */
object Crypto {

    private const val HMAC_SHA1 = "HmacSHA1"

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA1)
        mac.init(SecretKeySpec(key, HMAC_SHA1))
        return mac.doFinal(data)
    }

    fun hmacSha1(key: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA1)
        mac.init(SecretKeySpec(key, HMAC_SHA1))
        mac.update(data, offset, length)
        return mac.doFinal()
    }

    private val pbkdf2Factory = ThreadLocal.withInitial {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    }

    /**
     * PBKDF2-HMAC-SHA1 (RFC 2898), the KDF used by WPA/WPA2-PSK.
     *
     * The passphrase is converted to bytes as UTF-8 by the provider, which is
     * exactly what the 802.11i spec requires (passphrases are 8..63 ASCII
     * characters in practice).
     */
    fun pbkdf2Sha1(
        password: String,
        salt: ByteArray,
        iterations: Int = 4096,
        keyLengthBytes: Int = 32
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBytes * 8)
        return pbkdf2Factory.get()!!.generateSecret(spec).encoded
    }

    /**
     * IEEE 802.11i PRF (pseudo-random function) used to derive the PTK.
     * Implemented on top of HMAC-SHA1 directly so it works with any key length.
     */
    fun prf(key: ByteArray, label: String, data: ByteArray, lengthBytes: Int): ByteArray {
        val labelBytes = label.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(lengthBytes)
        var position = 0
        var counter = 0
        while (position < lengthBytes) {
            counter++
            val message = ByteArray(labelBytes.size + 1 + data.size + 1)
            System.arraycopy(labelBytes, 0, message, 0, labelBytes.size)
            message[labelBytes.size] = 0
            System.arraycopy(data, 0, message, labelBytes.size + 1, data.size)
            message[message.size - 1] = counter.toByte()
            val digest = hmacSha1(key, message)
            val copy = minOf(digest.size, lengthBytes - position)
            System.arraycopy(digest, 0, out, position, copy)
            position += copy
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun toMac(bytes: ByteArray, offset: Int = 0): String {
        val sb = StringBuilder(17)
        for (i in 0 until 6) {
            if (i > 0) sb.append(':')
            val v = bytes[offset + i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun macToBytes(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "")
        require(clean.length == 12) { "Invalid MAC: $mac" }
        return ByteArray(6) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Compares two MAC byte arrays regardless of order. */
    fun macEquals(a: ByteArray, b: ByteArray): Boolean =
        a.size >= 6 && b.size >= 6 && (0 until 6).all { a[it] == b[it] }
}
