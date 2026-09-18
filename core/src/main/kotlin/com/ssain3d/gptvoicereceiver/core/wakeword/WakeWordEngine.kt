package com.ssain3d.gptvoicereceiver.core.wakeword

/**
 * Why this is a frame processor rather than an event source (ARCHITECTURE.md §9.1
 * sketches a `Flow<WakeWordEvent>`):
 *
 * INV-1 says exactly one component owns microphone capture. Making the engine emit its
 * own events would give it a life of its own and invite a second capture path. Instead
 * `AudioCaptureService` — the sole owner of the AudioRecord — pushes frames in and
 * publishes the resulting events. The engine stays a pure, swappable detector
 * (ADR-002: the architecture does not depend on Porcupine).
 */
interface WakeWordEngine {

    /** For the debug UI, e.g. "Porcupine 3.0.2" or "disabled". */
    val name: String

    val status: WakeWordStatus

    /** Samples per [process] call. Callers must supply exactly this many. */
    val frameLength: Int

    /** Required input sample rate in Hz. */
    val sampleRate: Int

    /**
     * Try to bring the engine up. Must never throw: a missing AccessKey or missing
     * model file is a normal, reportable state, not a crash (Spike brief §7-3).
     */
    fun start(): WakeWordStatus

    /**
     * @param frame exactly [frameLength] 16-bit PCM mono samples at [sampleRate].
     * @return index of the detected keyword, or [NO_DETECTION].
     */
    fun process(frame: ShortArray): Int

    fun stop()

    fun release()

    companion object {
        const val NO_DETECTION: Int = -1
    }
}

sealed interface WakeWordStatus {

    /** Engine is loaded and [WakeWordEngine.process] will do real work. */
    data object Running : WakeWordStatus

    data object Stopped : WakeWordStatus

    /**
     * Cannot run, but this is expected and reportable — no AccessKey, no `.ppn` model,
     * no `.pv` params file. The app must keep working and show [reason] in the debug UI.
     */
    data class Unavailable(val reason: String) : WakeWordStatus

    /** Tried to initialise and the engine itself rejected it. */
    data class Failed(val reason: String) : WakeWordStatus
}

/**
 * ARCHITECTURE.md §9.1. Audio never leaves the device with this — only the index and
 * the time (SECURITY_PRIVACY.md §2).
 */
data class WakeWordEvent(
    val keywordIndex: Int,
    val detectedAtElapsedMs: Long,
    val detectedAtEpochMs: Long,
)
