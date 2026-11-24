package com.wifi.toolbox.utils

/**
 * Candidate password ranking engine.
 *
 * Goal: try the most likely passwords first to cut online attempts.
 * Pure Kotlin with no Android dependencies, so it can be unit tested.
 *
 * Scoring heuristics (no network access):
 * - Common weak passwords / keyboard patterns / vendor defaults
 * - Length distribution (8-12 characters is the most common)
 * - All digits, years, repeated characters
 * - Words derived from the SSID (split and separator-stripped)
 * - Human habits such as letters mixed with digits
 */
object CandidateScorer {

    private val COMMON_PASSWORDS = setOf(
        "12345678", "123456789", "1234567890", "88888888", "87654321", "11111111",
        "00000000", "987654321", "11223344", "66668888", "0123456789", "147258369",
        "66666666", "123123123", "8888888888", "88889999", "12344321", "12341234",
        "11112222", "a1b2c3d4", "1111111111", "99999999", "1122334455", "12345679",
        "1234554321", "0987654321", "23456789", "a123456789", "22222222", "abcd1234",
        "qwertyuiop", "55555555", "a12345678", "88886666", "0000000000", "1234567899",
        "abc123456", "01234567", "123456789a", "a1234567", "asdfghjkl", "123456780",
        "password", "password1", "password123", "passw0rd", "admin", "admin123",
        "root", "guest", "wifi1234", "internet", "welcome", "welcome1", "qwerty",
        "qwerty123", "abc12345", "iloveyou", "letmein", "football", "monkey",
        "dragon", "sunshine", "princess", "master", "superman", "1qaz2wsx"
    )

    private val COMMON_WORDS = listOf(
        "password", "admin", "wifi", "internet", "welcome", "guest", "home", "test",
        "root", "user", "love", "mobile", "phone", "network", "router", "tplink",
        "tp-link", "dlink", "xiaomi", "huawei", "mercusys", "tenda", "asus",
        "netgear", "linksys", "default", "china", "netcom", "unicom", "telecom"
    )

    private val COMMON_SUFFIXES = listOf(
        "123", "1234", "12345", "123456", "12345678", "123456789", "000", "111",
        "666", "888", "999", "520", "1314", "2020", "2021", "2022", "2023",
        "2024", "2025", "2026"
    )

    private val KEYBOARD_PATTERNS = listOf(
        "qwerty", "qwertyuiop", "asdfgh", "asdfghjkl", "zxcvbn", "zxcvbnm",
        "qazwsx", "1qaz2wsx", "1q2w3e4r", "abcd", "abcdef"
    )

    private val SEPARATORS = Regex("[-_.\\s@]+")
    private val YEAR_PATTERN = Regex("(19|20)\\d{2}")
    private val NOISE_TOKENS = setOf("wifi", "net", "5g", "2g", "4g", "2.4g", "router", "guest")

    /** Maximum number of scored candidates; the rest keep their order */
    private const val MAX_SCORED = 100_000

    /**
     * Sorts by hit probability, keeping the original order for equal scores.
     *
     * @param learned Previously cracked passwords; password reuse is common,
     *                so matches are pushed to the front
     */
    fun order(
        candidates: List<String>,
        ssid: String,
        learned: List<String> = emptyList(),
        preferLongPasswords: Boolean = false,
        preferNumeric: Boolean = false
    ): List<String> {
        if (candidates.size < 2) return candidates
        val tokens = extractTokens(ssid)
        val learnedSet = learned
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()

        // Huge dictionaries are only scored up front to avoid jank/OOM on the phone;
        // they are usually already ordered by commonness, so the tail keeps its order.
        val scoredSize = minOf(candidates.size, MAX_SCORED)
        val buckets = HashMap<Int, MutableList<String>>()
        for (i in 0 until scoredSize) {
            val value = candidates[i]
            val key = score(value, tokens, learnedSet, preferLongPasswords, preferNumeric)
            buckets.getOrPut(key) { ArrayList() }.add(value)
        }

        val result = ArrayList<String>(candidates.size)
        buckets.keys.sortedDescending().forEach { key ->
            result.addAll(buckets.getValue(key))
        }
        if (candidates.size > scoredSize) {
            result.addAll(candidates.subList(scoredSize, candidates.size))
        }
        return result
    }

    fun score(password: String, ssid: String): Int = score(password, extractTokens(ssid))

    private fun score(password: String, tokens: List<String>): Int =
        score(password, tokens, emptySet())

    private fun score(
        password: String,
        tokens: List<String>,
        learned: Set<String>,
        preferLongPasswords: Boolean = false,
        preferNumeric: Boolean = false
    ): Int {
        if (password.isEmpty()) return Int.MIN_VALUE

        var score = 0
        val lower = password.lowercase()
        val length = password.length

        score += when {
            length in 8..12 -> 40
            length in 13..16 -> 15
            length > 16 -> -10
            length < 8 -> -80
            else -> 0
        }

        if (password.all { it.isDigit() }) score += 25
        if (password.all { it == password[0] }) score += 15
        if (COMMON_PASSWORDS.contains(lower)) score += 70
        if (KEYBOARD_PATTERNS.any { lower.contains(it) }) score += 25
        if (COMMON_SUFFIXES.any { lower.endsWith(it) }) score += 12
        if (YEAR_PATTERN.containsMatchIn(password)) score += 18
        if (COMMON_WORDS.any { lower.contains(it) }) score += 10

        val matchedToken = tokens.firstOrNull { lower.contains(it) }
        if (matchedToken != null) {
            score += 35
            if (lower.startsWith(matchedToken)) score += 15
            if (lower == matchedToken) score += 10
        }

        if (password.any { it.isLetter() } && password.any { it.isDigit() }) score += 10

        // Security-profile hints: WPA3 deployments tend to use longer passwords,
        // old WPA/TKIP routers tend to keep numeric defaults.
        if (preferLongPasswords) {
            if (length >= 12) score += 8
            if (length in 8..9) score -= 4
        }
        if (preferNumeric && password.all { it.isDigit() }) score += 10

        // Learned passwords go straight to the front; variants that contain one also score higher
        if (learned.isNotEmpty()) {
            if (learned.contains(lower)) {
                score += 120
            } else if (learned.any { it.length >= 6 && lower.contains(it) }) {
                score += 20
            }
        }

        if (length >= 12 && password.substring(0, length / 2) == password.substring(length / 2)) {
            score += 8
        }

        return score
    }

    private fun extractTokens(ssid: String): List<String> {
        val normalized = ssid.trim().removeSurrounding("\"").lowercase()
        if (normalized.isEmpty()) return emptyList()

        val tokens = LinkedHashSet<String>()
        tokens.add(normalized)

        val compact = normalized.replace(SEPARATORS, "")
        if (compact.length >= 3) tokens.add(compact)

        normalized.split(SEPARATORS).forEach { part ->
            val token = part.trim()
            if (token.length >= 3) tokens.add(token)
        }

        return tokens
            .filter { it.length >= 3 && it !in NOISE_TOKENS }
            .take(8)
    }
}
