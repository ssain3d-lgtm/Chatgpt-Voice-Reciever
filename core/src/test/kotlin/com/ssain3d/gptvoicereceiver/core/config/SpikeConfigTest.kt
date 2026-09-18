package com.ssain3d.gptvoicereceiver.core.config

import com.ssain3d.gptvoicereceiver.core.speech.SpeechMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpikeConfigTest {

    @Test
    fun `empty input yields documented defaults`() {
        val r = SpikeConfig.parse(emptyMap())
        assertTrue(r.warnings.isEmpty())
        assertEquals(300, r.config.preRollMs)
        assertEquals(2000, r.config.ringBufferMs)
        assertEquals(SpeechMode.MODE_P_PIPE, r.config.speechMode)
        assertFalse(r.config.transcriptLogging, "SECURITY_PRIVACY §5: default OFF")
    }

    @Test
    fun `pre-roll converts to samples at the capture rate`() {
        val c = SpikeConfig(preRollMs = 300, sampleRateHz = 16_000)
        assertEquals(4800, c.preRollSamples)
        assertEquals(32_000, c.ringBufferSamples)
    }

    @Test
    fun `valid values are taken`() {
        val r = SpikeConfig.parse(
            mapOf(
                "preRollMs" to "500",
                "ringBufferMs" to "3000",
                "speechMode" to "H",
                "composerPollMs" to "150",
                "composerTimeoutMs" to "6000",
                "sendVerifyProbesMs" to "250, 800, 1500",
                "transcriptLogging" to "on",
            )
        )
        assertTrue(r.warnings.isEmpty(), "unexpected warnings: ${r.warnings}")
        assertEquals(500, r.config.preRollMs)
        assertEquals(3000, r.config.ringBufferMs)
        assertEquals(SpeechMode.MODE_H_HANDOFF, r.config.speechMode)
        assertEquals(150, r.config.composerPollMs)
        assertEquals(6000, r.config.composerTimeoutMs)
        assertEquals(listOf(250, 800, 1500), r.config.sendVerifyProbesMs)
        assertTrue(r.config.transcriptLogging)
    }

    @Test
    fun `garbage falls back to the default and warns instead of throwing`() {
        val r = SpikeConfig.parse(mapOf("preRollMs" to "banana"))
        assertEquals(300, r.config.preRollMs)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("preRollMs"))
    }

    @Test
    fun `out-of-range values fall back and warn`() {
        val r = SpikeConfig.parse(mapOf("preRollMs" to "999999"))
        assertEquals(300, r.config.preRollMs)
        assertTrue(r.warnings.single().contains("outside"))
    }

    @Test
    fun `ring buffer is raised so it can always hold the pre-roll`() {
        val r = SpikeConfig.parse(mapOf("preRollMs" to "1500", "ringBufferMs" to "400"))
        assertEquals(1500, r.config.preRollMs)
        assertEquals(1500, r.config.ringBufferMs)
        assertTrue(r.warnings.any { it.contains("ringBufferMs") })
    }

    @Test
    fun `speech mode accepts the documented aliases`() {
        listOf("P", "MODE_P", "mode_p_pipe", "pipe").forEach {
            assertEquals(SpeechMode.MODE_P_PIPE, SpikeConfig.parse(mapOf("speechMode" to it)).config.speechMode, it)
        }
        listOf("H", "MODE_H", "mode_h_handoff", "handoff").forEach {
            assertEquals(SpeechMode.MODE_H_HANDOFF, SpikeConfig.parse(mapOf("speechMode" to it)).config.speechMode, it)
        }
    }

    @Test
    fun `unknown speech mode warns and keeps Mode P`() {
        val r = SpikeConfig.parse(mapOf("speechMode" to "whisper"))
        assertEquals(SpeechMode.MODE_P_PIPE, r.config.speechMode)
        assertTrue(r.warnings.single().contains("speechMode"))
    }

    @Test
    fun `send verify probes are cleaned and sorted`() {
        val r = SpikeConfig.parse(mapOf("sendVerifyProbesMs" to "2000, , oops, 300, 99999, 1000"))
        assertEquals(listOf(300, 1000, 2000), r.config.sendVerifyProbesMs)
    }

    @Test
    fun `unusable probe list falls back to the default`() {
        val r = SpikeConfig.parse(mapOf("sendVerifyProbesMs" to "oops, nope"))
        assertEquals(SpikeConfig.DEFAULT_SEND_VERIFY_PROBES_MS, r.config.sendVerifyProbesMs)
        assertTrue(r.warnings.single().contains("sendVerifyProbesMs"))
    }

    @Test
    fun `transcript logging only turns on for an explicit affirmative`() {
        listOf("true", "1", "on", "yes").forEach {
            assertTrue(SpikeConfig.parse(mapOf("transcriptLogging" to it)).config.transcriptLogging, it)
        }
        listOf("false", "0", "off", "no", "").forEach {
            assertFalse(SpikeConfig.parse(mapOf("transcriptLogging" to it)).config.transcriptLogging, it)
        }
        val weird = SpikeConfig.parse(mapOf("transcriptLogging" to "maybe"))
        assertFalse(weird.config.transcriptLogging, "unknown values must not enable transcript logging")
        assertTrue(weird.warnings.isNotEmpty())
    }

    @Test
    fun `handoff release timeout is a parsed setting, not a constant`() {
        assertEquals(1500, SpikeConfig.parse(emptyMap()).config.handoffReleaseTimeoutMs)

        val ok = SpikeConfig.parse(mapOf("handoffReleaseTimeoutMs" to "2500"))
        assertTrue(ok.warnings.isEmpty())
        assertEquals(2500, ok.config.handoffReleaseTimeoutMs)

        val bad = SpikeConfig.parse(mapOf("handoffReleaseTimeoutMs" to "0"))
        assertEquals(1500, bad.config.handoffReleaseTimeoutMs)
        assertTrue(bad.warnings.single().contains("handoffReleaseTimeoutMs"))
    }

    @Test
    fun `constructing an inconsistent config directly is rejected`() {
        val failed = try {
            SpikeConfig(preRollMs = 1000, ringBufferMs = 500); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }
}
