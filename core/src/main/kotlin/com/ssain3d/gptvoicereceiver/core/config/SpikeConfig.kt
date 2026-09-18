package com.ssain3d.gptvoicereceiver.core.config

import com.ssain3d.gptvoicereceiver.core.speech.SpeechMode

/**
 * Tunable parameters for the Technical Spike.
 *
 * INV-8: timing parameters are configuration, not code constants. Every value here is
 * a `DEFAULT_INITIAL_VALUE` / `DEVICE_TUNABLE` / `NOT A PRODUCT CONSTANT` — an
 * experiment starting point to be replaced by real Galaxy measurements, never a number
 * to be frozen into logic (ADR-011).
 *
 * This is NOT `EndpointThresholds`. The Natural Endpoint engine is out of Spike scope;
 * its parameters arrive in v0.1.
 */
data class SpikeConfig(
    /**
     * Audio handed to the recognizer from *before* the wake instant, so the first
     * syllable is not lost. Only meaningful in Mode P. ARCHITECTURE.md §4.2.
     */
    val preRollMs: Int = DEFAULT_PRE_ROLL_MS,

    /** Ring buffer capacity. Must be able to hold at least the pre-roll. */
    val ringBufferMs: Int = DEFAULT_RING_BUFFER_MS,

    /** Capture format. Matches Porcupine's required input (ADR-003). */
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,

    /** Which wake -> STT transition to attempt first. */
    val speechMode: SpeechMode = SpeechMode.MODE_P_PIPE,

    /** How long to wait for the recognizer to say anything before calling Mode P dead. */
    val modePProbeTimeoutMs: Int = DEFAULT_MODE_P_PROBE_TIMEOUT_MS,

    /**
     * Mode H: how long to wait for the capture service to confirm it has actually
     * released the microphone before handing it to the recognizer. Two simultaneous
     * captures is what makes one of them silent, so this is a real handshake, not a
     * cosmetic delay.
     */
    val handoffReleaseTimeoutMs: Int = DEFAULT_HANDOFF_RELEASE_TIMEOUT_MS,

    /** Accessibility node search: polling interval and overall budget. CHATGPT_BRIDGE.md §3. */
    val composerPollMs: Int = DEFAULT_COMPOSER_POLL_MS,
    val composerTimeoutMs: Int = DEFAULT_COMPOSER_TIMEOUT_MS,

    /** When to re-read the composer after a single Send click, to time the clear (GV-13). */
    val sendVerifyProbesMs: List<Int> = DEFAULT_SEND_VERIFY_PROBES_MS,

    /** SECURITY_PRIVACY.md §5: OFF by default, even in a debug build. */
    val transcriptLogging: Boolean = false,
) {
    init {
        require(preRollMs >= 0) { "preRollMs must be >= 0" }
        require(ringBufferMs >= preRollMs) {
            "ringBufferMs ($ringBufferMs) must hold preRollMs ($preRollMs)"
        }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(composerPollMs > 0) { "composerPollMs must be positive" }
        require(composerTimeoutMs >= composerPollMs) {
            "composerTimeoutMs must be >= composerPollMs"
        }
    }

    val preRollSamples: Int get() = preRollMs * sampleRateHz / 1000
    val ringBufferSamples: Int get() = ringBufferMs * sampleRateHz / 1000

    companion object {
        const val DEFAULT_PRE_ROLL_MS = 300
        const val DEFAULT_RING_BUFFER_MS = 2000
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_MODE_P_PROBE_TIMEOUT_MS = 4000
        const val DEFAULT_HANDOFF_RELEASE_TIMEOUT_MS = 1500
        const val DEFAULT_COMPOSER_POLL_MS = 200
        const val DEFAULT_COMPOSER_TIMEOUT_MS = 4000
        val DEFAULT_SEND_VERIFY_PROBES_MS = listOf(300, 1000, 2000)

        /** Guard rails, not product decisions — they only stop nonsense input. */
        private val PRE_ROLL_RANGE = 0..2000
        private val RING_BUFFER_RANGE = 200..10_000
        private val POLL_RANGE = 20..2000
        private val TIMEOUT_RANGE = 200..30_000

        /**
         * Parses a flat `key -> value` map (the debug settings screen, or a properties
         * file).
         *
         * Never throws and never crashes the Spike on bad input: an unusable value is
         * reported as a warning and the default is kept. A diagnostics run must not be
         * lost to a typo.
         */
        fun parse(source: Map<String, String>): ParseResult {
            val warnings = mutableListOf<String>()

            fun int(key: String, default: Int, range: IntRange): Int {
                val raw = source[key]?.trim() ?: return default
                val parsed = raw.toIntOrNull()
                if (parsed == null) {
                    warnings += "$key: '$raw' is not an integer, using $default"
                    return default
                }
                if (parsed !in range) {
                    warnings += "$key: $parsed outside $range, using $default"
                    return default
                }
                return parsed
            }

            val preRoll = int("preRollMs", DEFAULT_PRE_ROLL_MS, PRE_ROLL_RANGE)
            var ring = int("ringBufferMs", DEFAULT_RING_BUFFER_MS, RING_BUFFER_RANGE)
            if (ring < preRoll) {
                warnings += "ringBufferMs: $ring cannot hold preRollMs $preRoll, raising to $preRoll"
                ring = preRoll
            }

            val modeRaw = source["speechMode"]?.trim()
            val mode = when (modeRaw?.uppercase()) {
                null -> SpeechMode.MODE_P_PIPE
                "P", "MODE_P", "MODE_P_PIPE", "PIPE" -> SpeechMode.MODE_P_PIPE
                "H", "MODE_H", "MODE_H_HANDOFF", "HANDOFF" -> SpeechMode.MODE_H_HANDOFF
                else -> {
                    warnings += "speechMode: '$modeRaw' unknown, using MODE_P_PIPE"
                    SpeechMode.MODE_P_PIPE
                }
            }

            val probesRaw = source["sendVerifyProbesMs"]?.trim()
            val probes = if (probesRaw.isNullOrEmpty()) {
                DEFAULT_SEND_VERIFY_PROBES_MS
            } else {
                val parsed = probesRaw.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it in TIMEOUT_RANGE }
                    .sorted()
                if (parsed.isEmpty()) {
                    warnings += "sendVerifyProbesMs: '$probesRaw' had no usable values, " +
                        "using $DEFAULT_SEND_VERIFY_PROBES_MS"
                    DEFAULT_SEND_VERIFY_PROBES_MS
                } else {
                    parsed
                }
            }

            val transcriptRaw = source["transcriptLogging"]?.trim()
            val transcript = when (transcriptRaw?.lowercase()) {
                null, "" -> false
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> {
                    warnings += "transcriptLogging: '$transcriptRaw' unknown, using OFF"
                    false
                }
            }

            val poll = int("composerPollMs", DEFAULT_COMPOSER_POLL_MS, POLL_RANGE)
            var timeout = int("composerTimeoutMs", DEFAULT_COMPOSER_TIMEOUT_MS, TIMEOUT_RANGE)
            if (timeout < poll) {
                warnings += "composerTimeoutMs: $timeout below composerPollMs $poll, raising to $poll"
                timeout = poll
            }

            return ParseResult(
                config = SpikeConfig(
                    preRollMs = preRoll,
                    ringBufferMs = ring,
                    speechMode = mode,
                    modePProbeTimeoutMs = int(
                        "modePProbeTimeoutMs",
                        DEFAULT_MODE_P_PROBE_TIMEOUT_MS,
                        TIMEOUT_RANGE,
                    ),
                    handoffReleaseTimeoutMs = int(
                        "handoffReleaseTimeoutMs",
                        DEFAULT_HANDOFF_RELEASE_TIMEOUT_MS,
                        TIMEOUT_RANGE,
                    ),
                    composerPollMs = poll,
                    composerTimeoutMs = timeout,
                    sendVerifyProbesMs = probes,
                    transcriptLogging = transcript,
                ),
                warnings = warnings.toList(),
            )
        }
    }

    data class ParseResult(val config: SpikeConfig, val warnings: List<String>)
}
