package com.wifi.toolbox.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    @Test
    fun `pbkdf2 matches RFC 6070 vectors`() {
        assertEquals(
            "0c60c80f961f0e71f3a9b524af6012062fe037a6",
            Crypto.toHex(Crypto.pbkdf2Sha1("password", "salt".toByteArray(), 1, 20))
        )
        assertEquals(
            "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957",
            Crypto.toHex(Crypto.pbkdf2Sha1("password", "salt".toByteArray(), 2, 20))
        )
        assertEquals(
            "4b007901b765489abead49d926f721d065a429c1",
            Crypto.toHex(Crypto.pbkdf2Sha1("password", "salt".toByteArray(), 4096, 20))
        )
    }

    @Test
    fun `hmac sha1 matches RFC 2202`() {
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            Crypto.toHex(Crypto.hmacSha1(ByteArray(20) { 0x0b }, "Hi There".toByteArray()))
        )
    }

    @Test
    fun `prf is deterministic and length correct`() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(76) { (it * 3).toByte() }
        val a = Crypto.prf(key, "Pairwise key expansion", data, 48)
        val b = Crypto.prf(key, "Pairwise key expansion", data, 48)
        assertEquals(48, a.size)
        assertEquals(Crypto.toHex(a), Crypto.toHex(b))
        assertFalse(Crypto.toHex(a) == Crypto.toHex(Crypto.prf(key, "Pairwise key expansion", data, 64)))
    }

    @Test
    fun `mac parsing round trip`() {
        val mac = "02:11:22:33:44:55"
        assertEquals(mac, Crypto.toMac(Crypto.macToBytes(mac)))
    }
}
