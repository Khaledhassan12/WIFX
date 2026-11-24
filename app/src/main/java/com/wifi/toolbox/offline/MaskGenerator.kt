package com.wifi.toolbox.offline

/**
 * Hashcat-style mask generator.
 *
 * Supported placeholders:
 *   ?l  lowercase        a-z
 *   ?u  uppercase        A-Z
 *   ?d  digits           0-9
 *   ?s  symbols          ASCII punctuation
 *   ?a  all printable    ?l?u?d?s
 *   ?b  binary           0x00-0xff
 *   ?1..?4 custom charsets supplied via [customCharsets]
 *   ??  a literal '?'
 *
 * Example: `?u?l?l?l?l?l?d?d` -> "Abcdef12" style passwords.
 *
 * Generation is lazy: a mask with 10^8 combinations never materialises in
 * memory, which is what makes offline auditing practical on a phone.
 */
object MaskGenerator {

    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"
    const val SYMBOLS = " !\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    data class Mask(
        val source: String,
        val positions: List<CharSet>,
        val customCharsets: List<String> = emptyList()
    )

    data class CharSet(val chars: CharArray, val isLiteral: Boolean = false)

    class MaskException(message: String) : Exception(message)

    fun parse(mask: String, customCharsets: List<String> = emptyList()): Mask {
        if (mask.isEmpty()) throw MaskException("Empty mask")
        val positions = ArrayList<CharSet>(mask.length)
        var i = 0
        while (i < mask.length) {
            val c = mask[i]
            if (c != '?') {
                positions.add(CharSet(charArrayOf(c), isLiteral = true))
                i++
                continue
            }
            if (i + 1 >= mask.length) throw MaskException("Mask ends with '?'")
            when (val spec = mask[i + 1]) {
                'l' -> positions.add(CharSet(LOWER.toCharArray()))
                'u' -> positions.add(CharSet(UPPER.toCharArray()))
                'd' -> positions.add(CharSet(DIGITS.toCharArray()))
                's' -> positions.add(CharSet(SYMBOLS.toCharArray()))
                'a' -> positions.add(CharSet((LOWER + UPPER + DIGITS + SYMBOLS).toCharArray()))
                'b' -> positions.add(CharSet(CharArray(256) { it.toChar() }))
                '?' -> positions.add(CharSet(charArrayOf('?'), isLiteral = true))
                in '1'..'4' -> {
                    val index = spec - '1'
                    val chars = customCharsets.getOrNull(index)
                        ?: throw MaskException("Missing custom charset ?${spec}")
                    if (chars.isEmpty()) throw MaskException("Custom charset ?${spec} is empty")
                    positions.add(CharSet(chars.toCharArray()))
                }
                else -> throw MaskException("Unknown placeholder ?$spec")
            }
            i += 2
        }
        return Mask(mask, positions, customCharsets)
    }

    /**
     * Total number of combinations. Returns [Long.MAX_VALUE] when the mask is
     * larger than what a Long can represent (or overflows).
     */
    fun count(mask: Mask): Long {
        var total = 1L
        for (p in mask.positions) {
            val size = p.chars.size.toLong()
            if (size == 0L) return 0L
            if (total > Long.MAX_VALUE / size) return Long.MAX_VALUE
            total *= size
        }
        return total
    }

    fun count(mask: String, customCharsets: List<String> = emptyList()): Long =
        count(parse(mask, customCharsets))

    /**
     * Lazy sequence over every combination. Iteration order matches hashcat:
     * the right-most position changes fastest.
     */
    fun sequence(mask: Mask): Sequence<String> = sequence {
        if (mask.positions.isEmpty()) return@sequence
        val indices = IntArray(mask.positions.size)
        val builder = CharArray(mask.positions.size)
        val total = count(mask)
        var produced = 0L
        while (produced < total) {
            for (p in mask.positions.indices) {
                builder[p] = mask.positions[p].chars[indices[p]]
            }
            yield(String(builder))
            produced++
            var p = mask.positions.size - 1
            while (p >= 0) {
                indices[p]++
                if (indices[p] < mask.positions[p].chars.size) break
                indices[p] = 0
                p--
            }
            if (p < 0) break
        }
    }

    fun sequence(mask: String, customCharsets: List<String> = emptyList()): Sequence<String> =
        sequence(parse(mask, customCharsets))

    /** Human readable estimate, e.g. "12.4M". */
    fun formatCount(count: Long): String = when {
        count >= 1_000_000_000L -> "%.1fB".format(count / 1_000_000_000.0)
        count >= 1_000_000L -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000L -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }

    /**
     * Built-in masks that cover the passwords routers actually ship with.
     * Ordered from "most likely on a home router" to "last resort".
     */
    fun presets(ssid: String): List<MaskPreset> {
        val list = mutableListOf<MaskPreset>()

        list += MaskPreset("?d?d?d?d?d?d?d?d", "8 digits")
        list += MaskPreset("?d?d?d?d?d?d?d?d?d?d", "10 digits")
        list += MaskPreset("?d?d?d?d?d?d?d?d?d?d?d", "11 digits")

        val token = ssidToken(ssid)
        if (token != null) {
            list += MaskPreset(token + "?d?d?d?d", "SSID + 4 digits")
            list += MaskPreset(token + "?d?d?d?d?d?d", "SSID + 6 digits")
            list += MaskPreset(token + "@?d?d?d?d", "SSID@digits")
            list += MaskPreset("?u?l?l?l?l?l?d?d", "Word + 2 digits")
            list += MaskPreset("?u?l?l?l?l?l?l?l?d?d", "Word + 2 digits (long)")
        }

        list += MaskPreset("?d?d?d?d?d?d?d?d?d?d?d?d", "12 digits")
        list += MaskPreset("?u?l?l?l?l?l?l?l?d?d?d", "Word + 3 digits")
        list += MaskPreset("?u?u?u?u?d?d?d?d", "4 letters + 4 digits")
        list += MaskPreset("?d?d?d?d?d?d?d?d?a", "8 digits + symbol")
        return list
    }

    /** First alphanumeric token of the SSID, lowercased, for SSID-based masks. */
    private fun ssidToken(ssid: String): String? =
        ssid.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .firstOrNull { it.length in 3..10 }
            ?.takeIf { it.any { c -> c.isLetter() } }

    data class MaskPreset(val mask: String, val label: String) {
        val count: Long get() = MaskGenerator.count(mask)
    }
}
