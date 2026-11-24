package com.wifi.toolbox.offline

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies password candidates against one captured handshake.
 *
 * This is the offline counterpart of the online connection loop: instead of
 * asking the router "is this the password?", we derive the key ourselves and
 * compare it with the MIC/PMKID captured from the air. That is thousands of
 * times faster and does not touch the network at all.
 */
class WpaCracker(private val handshake: WpaHandshake) {

    private val ssidBytes: ByteArray = handshake.ssid.toByteArray(Charsets.UTF_8)
    private val apMac: ByteArray = Crypto.macToBytes(handshake.apMac)
    private val staMac: ByteArray = Crypto.macToBytes(handshake.staMac)
    private val ptkLength: Int = if (handshake.keyVersion == 1) 64 else 48

    /**
     * @return true when [password] produces the captured MIC or PMKID
     */
    fun verify(password: String): Boolean {
        val pmk = derivePmk(password) ?: return false
        return verifyPmk(pmk)
    }

    fun verifyPmk(pmk: ByteArray): Boolean {
        if (handshake.hasPmkid) {
            val captured = handshake.pmkid ?: return false
            val ap = handshake.pmkidApMac ?: apMac
            val sta = handshake.pmkidStaMac ?: staMac
            if (Crypto.constantTimeEquals(computePmkid(pmk, ap, sta), captured)) return true
        }
        if (handshake.hasFourWay) {
            if (handshake.eapol.isEmpty()) return false
            val ptk = derivePtk(pmk)
            val kck = ptk.copyOfRange(0, 16)
            val mic = Crypto.hmacSha1(kck, handshake.eapol)
            if (Crypto.constantTimeEquals(mic.copyOfRange(0, 16), handshake.mic)) return true
        }
        return false
    }

    fun derivePmk(password: String): ByteArray? {
        // 64 hex chars means the network stores a raw PSK instead of a passphrase.
        if (password.length == 64 && password.all { it.isHexDigit() }) {
            return ByteArray(32) { i ->
                password.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        if (password.length < 8 || password.length > 63) return null
        return Crypto.pbkdf2Sha1(password, ssidBytes)
    }

    fun derivePtk(pmk: ByteArray): ByteArray {
        val data = ByteArray(76)
        writeOrdered(apMac, staMac, data, 0)
        writeOrdered(handshake.anonce, handshake.snonce, data, 12)
        return Crypto.prf(pmk, "Pairwise key expansion", data, ptkLength)
    }

    fun computePmkid(pmk: ByteArray, ap: ByteArray, sta: ByteArray): ByteArray {
        val message = ByteArray(8 + 6 + 6)
        System.arraycopy("PMK Name".toByteArray(Charsets.US_ASCII), 0, message, 0, 8)
        System.arraycopy(ap, 0, message, 8, 6)
        System.arraycopy(sta, 0, message, 14, 6)
        return Crypto.hmacSha1(pmk, message).copyOfRange(0, 16)
    }

    private fun writeOrdered(a: ByteArray, b: ByteArray, out: ByteArray, offset: Int) {
        val first = if (compareUnsigned(a, b) <= 0) a else b
        val second = if (first === a) b else a
        System.arraycopy(first, 0, out, offset, first.size)
        System.arraycopy(second, 0, out, offset + first.size, second.size)
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/**
 * Runs a candidate stream through [WpaCracker] using every available core.
 */
object OfflineCracker {

    data class Result(
        val password: String?,
        val tried: Long,
        val elapsedMs: Long
    ) {
        val speed: Long
            get() = if (elapsedMs <= 0) 0 else tried * 1000 / elapsedMs
    }

    fun crack(
        handshake: WpaHandshake,
        candidates: Sequence<String>,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        cancelled: () -> Boolean = { false },
        onProgress: (tried: Long, speed: Long) -> Unit = { _, _ -> }
    ): Result {
        val start = System.currentTimeMillis()
        val cracker = WpaCracker(handshake)
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val tried = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(threads)
        val batchSize = 256
        val lastReport = AtomicLong(0)
        var pending = ArrayList<java.util.concurrent.Future<Boolean>>(threads * 2)

        try {
            val iterator = candidates.iterator()
            while (!stop.get() && !cancelled() && iterator.hasNext()) {
                val batch = ArrayList<String>(batchSize)
                while (batch.size < batchSize && iterator.hasNext()) {
                    batch.add(iterator.next())
                }
                if (batch.isEmpty()) break

                pending.add(
                    executor.submit(
                        Callable {
                            for (candidate in batch) {
                                if (stop.get()) return@Callable false
                                if (cracker.verify(candidate)) {
                                    found.compareAndSet(null, candidate)
                                    stop.set(true)
                                    return@Callable true
                                }
                            }
                            val total = tried.addAndGet(batch.size.toLong())
                            val now = System.currentTimeMillis()
                            val previous = lastReport.get()
                            if (now - previous >= 500 && lastReport.compareAndSet(previous, now)) {
                                onProgress(total, if (now > start) total * 1000 / (now - start) else 0)
                            }
                            false
                        }
                    )
                )

                if (pending.size >= threads * 2) {
                    pending.removeAt(0).get()
                }
            }
            pending.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        return Result(found.get(), tried.get(), System.currentTimeMillis() - start)
    }
}
