package com.ssain3d.gptvoicereceiver.core.speech

import com.ssain3d.gptvoicereceiver.core.session.TurnId

/**
 * How audio reaches the recognizer (ARCHITECTURE.md §4.3). Which one a device supports
 * is `DEVICE_TEST_REQUIRED` — that is precisely what Spike S-2 measures (GV-09).
 */
enum class SpeechMode {
    /**
     * Mode P — we keep the microphone and hand the recognizer a pipe
     * (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 33). Pre-roll is possible, so no
     * first-syllable loss. The recognizer is allowed to ignore the extra and open the
     * mic itself, which is why this must be measured, not assumed.
     */
    MODE_P_PIPE,

    /**
     * Mode H — release our AudioRecord, cue the user, let the recognizer open the mic,
     * then reacquire. No pre-roll. The cue tone is the synchronisation signal that
     * tells the user it is safe to speak.
     */
    MODE_H_HANDOFF,
}

/**
 * Recognizer callbacks, timestamped.
 *
 * Spike scope (brief §12): S-2 records the callback timeline only. The Natural
 * Endpoint engine — Korean EndingClassifier, adaptive timeouts, grace window,
 * false-endpoint recovery — is NOT built here. It is built in v0.1 once S-2 passes.
 * Timestamps are captured now so that v0.1 can be tuned against real Galaxy traces
 * (ENDPOINT_ENGINE.md §9, dataset D6).
 */
sealed interface SpeechEvent {
    val turnId: TurnId

    /** Monotonic elapsed-time reading, for latency maths. Not wall clock. */
    val atElapsedMs: Long

    data class ReadyForSpeech(override val turnId: TurnId, override val atElapsedMs: Long) : SpeechEvent

    /** Recognizer reported the user started speaking. */
    data class SpeechStart(override val turnId: TurnId, override val atElapsedMs: Long) : SpeechEvent

    data class Partial(
        override val turnId: TurnId,
        override val atElapsedMs: Long,
        val text: String,
        val index: Int,
    ) : SpeechEvent

    /** `onSegmentResults` — only fires when segmented sessions actually work (GV-10). */
    data class Segment(
        override val turnId: TurnId,
        override val atElapsedMs: Long,
        val text: String,
        val index: Int,
    ) : SpeechEvent

    /**
     * `onResults`.
     *
     * INV-6: this is a SEGMENT result, not a send signal. The Spike shows it in the
     * debug UI and stops there — it is never wired to anything that sends.
     */
    data class Final(
        override val turnId: TurnId,
        override val atElapsedMs: Long,
        val text: String,
    ) : SpeechEvent

    data class SpeechEnd(override val turnId: TurnId, override val atElapsedMs: Long) : SpeechEvent

    data class RecognizerEnd(override val turnId: TurnId, override val atElapsedMs: Long) : SpeechEvent

    data class Error(
        override val turnId: TurnId,
        override val atElapsedMs: Long,
        val code: Int,
        val message: String,
    ) : SpeechEvent
}

/**
 * ARCHITECTURE.md §9.3, adapted for the Spike: a listener instead of a `Flow`, so that
 * `core` keeps zero dependencies. §9 states these signatures are sketches finalised at
 * implementation time.
 */
interface SpeechEngine {
    val mode: SpeechMode

    fun start(turnId: TurnId)
    fun stop(turnId: TurnId)
    fun release()

    fun interface Listener {
        fun onSpeechEvent(event: SpeechEvent)
    }
}
