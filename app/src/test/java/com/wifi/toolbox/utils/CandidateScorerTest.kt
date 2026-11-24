package com.wifi.toolbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateScorerTest {

    @Test
    fun `ssid related candidates are ranked first`() {
        val ssid = "TP-LINK_HOME"
        val ordered = CandidateScorer.order(
            listOf("zzzzzzzz", "tplink123", "home2024"),
            ssid
        )
        assertEquals("home2024", ordered.first())
        assertTrue(ordered.indexOf("tplink123") < ordered.indexOf("zzzzzzzz"))
    }

    @Test
    fun `common weak password beats random password`() {
        val ordered = CandidateScorer.order(
            listOf("randompass", "12345678"),
            "MyWiFi"
        )
        assertEquals("12345678", ordered.first())
    }

    @Test
    fun `equal scores keep original order`() {
        val candidates = listOf("aaaaaaaa", "bbbbbbbb")
        assertEquals(candidates, CandidateScorer.order(candidates, "SomeNet"))
    }

    @Test
    fun `short passwords are penalized`() {
        val long = CandidateScorer.score("12345678", "net")
        val short = CandidateScorer.score("1234", "net")
        assertTrue(long > short)
    }

    @Test
    fun `ssid token increases score`() {
        val withToken = CandidateScorer.score("myoffice2024", "MyOffice")
        val withoutToken = CandidateScorer.score("randomtext99", "MyOffice")
        assertTrue(withToken > withoutToken)
    }

    @Test
    fun `previously successful password is tried first`() {
        val ordered = CandidateScorer.order(
            listOf("12345678", "SomeOldPass1"),
            "NewRouter",
            learned = listOf("someoldpass1")
        )
        assertEquals("SomeOldPass1", ordered.first())
    }

    @Test
    fun `learned password is matched case insensitively`() {
        val ordered = CandidateScorer.order(
            listOf("randompass9", "HomeNet#2024"),
            "AnotherNet",
            learned = listOf("homenet#2024")
        )
        assertEquals("HomeNet#2024", ordered.first())
    }
}
