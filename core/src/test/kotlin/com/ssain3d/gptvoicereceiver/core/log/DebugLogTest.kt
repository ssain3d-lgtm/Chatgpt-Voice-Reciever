package com.ssain3d.gptvoicereceiver.core.log

import com.ssain3d.gptvoicereceiver.core.session.TurnId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogTest {

    private val fixedTime = TimeFormatter { "T$it" }

    private fun log(capacity: Int = 4): DebugLog {
        var t = 0L
        return DebugLog(capacity = capacity, now = { ++t })
    }

    @Test
    fun `keeps insertion order`() {
        val log = log()
        log.log(SpikeId.S1, "A", "one")
        log.log(SpikeId.S1, "A", "two")
        assertEquals(listOf("one", "two"), log.snapshot().map { it.event })
    }

    @Test
    fun `drops oldest once full and counts the drops`() {
        val log = log(capacity = 3)
        repeat(5) { log.log(SpikeId.S2, "Rec", "e$it") }

        assertEquals(3, log.size())
        assertEquals(listOf("e2", "e3", "e4"), log.snapshot().map { it.event })
        assertEquals(2L, log.dropped())
    }

    @Test
    fun `clear empties the buffer and the drop counter`() {
        val log = log(capacity = 2)
        repeat(5) { log.log(SpikeId.S2, "Rec", "e$it") }
        log.clear()
        assertEquals(0, log.size())
        assertEquals(0L, log.dropped())
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `capacity must be positive`() {
        val failed = try {
            DebugLog(capacity = 0); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(failed, "a zero-capacity ring buffer would silently discard everything")
    }

    @Test
    fun `is safe under concurrent appends`() {
        val log = DebugLog(capacity = 500)
        val threads = 8
        val each = 1000
        val workers = List(threads) { w ->
            Thread { repeat(each) { log.log(SpikeId.APP, "T$w", "e$it") } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(500, log.size())
        assertEquals((threads * each - 500).toLong(), log.dropped())
    }

    // ---- SECURITY_PRIVACY.md §5 ----

    @Test
    fun `transcripts are masked by default`() {
        val log = log()
        assertFalse(log.transcriptLoggingEnabled, "default must be OFF")
        val masked = log.transcript("헤이 지피티 FastH3 확인해줘")
        assertFalse(masked.contains("FastH3"), "raw transcript must not appear")
        assertContains(masked, "len=")
    }

    @Test
    fun `transcripts are recorded verbatim only after explicit opt-in`() {
        val log = log()
        log.transcriptLoggingEnabled = true
        assertEquals("FastH3 확인해줘", log.transcript("FastH3 확인해줘"))
    }

    @Test
    fun `null transcript does not blow up`() {
        assertEquals("<null>", log().transcript(null))
    }

    // ---- formatting / export ----

    @Test
    fun `format contains every required field`() {
        val e = DebugEvent(
            atEpochMs = 123,
            spike = SpikeId.S3,
            component = "Bridge",
            event = "SendClick",
            result = EventResult.FAIL,
            turnId = TurnId(9),
            latencyMs = 42,
            error = "composer not cleared",
            detail = "node=EditText",
        )
        val line = e.format(fixedTime)

        assertContains(line, "T123")
        assertContains(line, "FAIL")
        assertContains(line, "S3")
        assertContains(line, "Bridge/SendClick")
        assertContains(line, "turn#9")
        assertContains(line, "+42ms")
        assertContains(line, "composer not cleared")
        assertContains(line, "node=EditText")
    }

    @Test
    fun `export states the transcript logging mode`() {
        val log = log()
        log.log(SpikeId.S1, "Audio", "started")
        assertContains(log.export(fixedTime), "transcriptLogging=OFF")
        log.transcriptLoggingEnabled = true
        assertContains(log.export(fixedTime), "transcriptLogging=ON")
    }
}
