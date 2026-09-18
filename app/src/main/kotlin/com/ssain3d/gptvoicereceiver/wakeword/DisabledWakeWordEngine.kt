package com.ssain3d.gptvoicereceiver.wakeword

import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordEngine
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordStatus

/**
 * Stand-in used when Porcupine cannot run — no AccessKey, no `.ppn`, no `.pv`.
 *
 * Spike brief §7-3: a missing key must never crash the app. More importantly, S-1's
 * critical hypothesis (R-02: does an FGS started by a VIS still receive *non-silent*
 * audio with the screen off?) is answered by the RMS/silence telemetry in
 * AudioCaptureService, which works with this engine in place. So the whole
 * screen-off feasibility test can be run before any Picovoice asset exists.
 *
 * What is NOT testable without Porcupine: wake-word detection rate and false-accept
 * rate (GV-02).
 */
class DisabledWakeWordEngine(private val reason: String) : WakeWordEngine {

    override val name: String = "disabled"

    override val status: WakeWordStatus = WakeWordStatus.Unavailable(reason)

    /** 32 ms at 16 kHz — the same cadence Porcupine uses, so frame timing is alike. */
    override val frameLength: Int = 512

    override val sampleRate: Int = 16_000

    override fun start(): WakeWordStatus = status

    override fun process(frame: ShortArray): Int = WakeWordEngine.NO_DETECTION

    override fun stop() = Unit

    override fun release() = Unit
}
