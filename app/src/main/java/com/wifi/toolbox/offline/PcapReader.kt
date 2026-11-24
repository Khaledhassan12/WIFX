package com.wifi.toolbox.offline

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for classic libpcap (.pcap) and pcapng (.pcapng) capture files.
 *
 * Only what the offline WPA auditor needs is implemented: link type, packet
 * timestamp and the raw frame bytes. Both byte orders are supported.
 *
 * Packets are streamed through a callback so a 200 MB capture never has to be
 * loaded into memory at once; the callback returns false to stop early.
 */
object PcapReader {

    const val LINKTYPE_NULL = 0
    const val LINKTYPE_ETHERNET = 1
    const val LINKTYPE_IEEE802_11 = 105
    const val LINKTYPE_LINUX_SLL = 113
    const val LINKTYPE_IEEE802_11_RADIOTAP = 127
    const val LINKTYPE_IEEE802_11_AVS = 163
    const val LINKTYPE_PPI = 192
    const val LINKTYPE_LINUX_SLL2 = 276

    data class Packet(
        val timestampMicros: Long,
        val linkType: Int,
        val data: ByteArray
    )

    class PcapException(message: String) : Exception(message)

    /** @return number of packets delivered */
    fun read(file: File, consumer: (Packet) -> Boolean): Int =
        file.inputStream().use { read(it, consumer) }

    /** @return number of packets delivered */
    fun read(input: InputStream, consumer: (Packet) -> Boolean): Int {
        val stream = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val header = ByteArray(4)
        if (stream.readFully(header, 0, 4) < 4) throw PcapException("File too short")
        val magicLE = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        val magicBE = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int

        return when {
            // pcapng section header block starts with 0x0A0D0D0A
            magicBE == 0x0A0D0D0A -> readPcapNg(stream, consumer)
            magicLE == PCAP_MAGIC || magicLE == PCAP_MAGIC_NANO ->
                readPcap(stream, header, ByteOrder.LITTLE_ENDIAN, consumer)
            magicBE == PCAP_MAGIC || magicBE == PCAP_MAGIC_NANO ->
                readPcap(stream, header, ByteOrder.BIG_ENDIAN, consumer)
            else -> throw PcapException("Not a pcap/pcapng file")
        }
    }

    fun readAll(file: File): List<Packet> {
        val list = ArrayList<Packet>()
        read(file) { list.add(it); true }
        return list
    }

    // ---------------------------------------------------------------- pcap ---

    private fun readPcap(
        stream: BufferedInputStream,
        firstBytes: ByteArray,
        order: ByteOrder,
        consumer: (Packet) -> Boolean
    ): Int {
        val header = ByteArray(24)
        System.arraycopy(firstBytes, 0, header, 0, 4)
        if (stream.readFully(header, 4, 20) < 20) return 0
        val buf = ByteBuffer.wrap(header).order(order)
        buf.int // magic
        buf.short // major
        buf.short // minor
        buf.int // thiszone
        buf.int // sigfigs
        buf.int // snaplen
        val linkType = buf.int and 0x0fffffff

        var delivered = 0
        val record = ByteArray(16)
        while (true) {
            if (stream.readFully(record, 0, 16) < 16) break
            val r = ByteBuffer.wrap(record).order(order)
            val tsSec = r.int.toLong() and 0xffffffffL
            val tsFrac = r.int.toLong() and 0xffffffffL
            val inclLen = r.int
            if (inclLen <= 0 || inclLen > MAX_PACKET) break
            val data = ByteArray(inclLen)
            if (stream.readFully(data, 0, inclLen) < inclLen) break
            delivered++
            if (!consumer(Packet(tsSec * 1_000_000L + tsFrac, linkType, data))) break
        }
        return delivered
    }

    // -------------------------------------------------------------- pcapng ---

    private fun readPcapNg(stream: BufferedInputStream, consumer: (Packet) -> Boolean): Int {
        val interfaceLinkTypes = ArrayList<Int>()
        val blockHeader = ByteArray(8)
        var delivered = 0

        while (true) {
            if (stream.readFully(blockHeader, 0, 8) < 8) break
            // Detect byte order from the block type bytes.
            val typeBE = ByteBuffer.wrap(blockHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val order =
                if (typeBE == 0x0A0D0D0A) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val type = ByteBuffer.wrap(blockHeader, 0, 4).order(order).int
            val totalLength = ByteBuffer.wrap(blockHeader, 4, 4).order(order).int
            if (totalLength < 12 || totalLength > MAX_BLOCK) break

            val body = ByteArray(totalLength - 12)
            if (body.isNotEmpty() && stream.readFully(body, 0, body.size) < body.size) break
            val trailer = ByteArray(4)
            if (stream.readFully(trailer, 0, 4) < 4) break

            when (type) {
                0x00000001 -> { // Interface Description Block
                    if (body.size >= 8) {
                        interfaceLinkTypes.add(
                            ByteBuffer.wrap(body).order(order).getShort(0).toInt() and 0xffff
                        )
                    }
                }

                0x00000006 -> { // Enhanced Packet Block
                    if (body.size >= 20) {
                        val b = ByteBuffer.wrap(body).order(order)
                        val iface = b.getInt(0)
                        val tsHigh = b.getInt(4).toLong() and 0xffffffffL
                        val tsLow = b.getInt(8).toLong() and 0xffffffffL
                        val capLen = b.getInt(12)
                        if (capLen <= 0 || capLen > body.size - 20) continue
                        val data = ByteArray(capLen)
                        System.arraycopy(body, 20, data, 0, capLen)
                        val ts = (tsHigh shl 32) or tsLow
                        delivered++
                        if (!consumer(
                                Packet(
                                    ts / 1000L,
                                    interfaceLinkTypes.getOrElse(iface) { LINKTYPE_ETHERNET },
                                    data
                                )
                            )
                        ) break
                    }
                }

                0x00000003 -> { // Simple Packet Block
                    if (body.size >= 4) {
                        val b = ByteBuffer.wrap(body).order(order)
                        val origLen = b.getInt(0)
                        val capLen = minOf(origLen, body.size - 4)
                        if (capLen <= 0) continue
                        val data = ByteArray(capLen)
                        System.arraycopy(body, 4, data, 0, capLen)
                        delivered++
                        if (!consumer(
                                Packet(
                                    0L,
                                    interfaceLinkTypes.firstOrNull() ?: LINKTYPE_ETHERNET,
                                    data
                                )
                            )
                        ) break
                    }
                }
            }
        }
        return delivered
    }

    private fun BufferedInputStream.readFully(buffer: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = read(buffer, offset + read, length - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private const val PCAP_MAGIC = 0xA1B2C3D4.toInt()
    private const val PCAP_MAGIC_NANO = 0xA1B23C4D.toInt()
    private const val MAX_PACKET = 16 * 1024 * 1024
    private const val MAX_BLOCK = 32 * 1024 * 1024
}
