package com.ssain3d.gptvoicereceiver.audio

/**
 * Immutable snapshot of what the microphone path is doing right now.
 *
 * This is the primary instrument for S-1 / R-02 / GV-03. The question is not "is the
 * service alive" — a dead-silent service is also alive. The question is whether the
 * PCM still contains signal once the screen goes off, which is why
 * [allZeroFrames] and [quietFrames] are tracked separately:
 *
 *   allZeroFrames climbing      -> the OS is handing us a muted stream. This is the
 *                                  signature of the microphone being withheld, and it
 *                                  means GV-03 FAILED even though the service is up.
 *   quietFrames climbing, some
 *   non-zero samples present    -> the microphone is genuinely live in a quiet room.
 *                                  GV-03 can PASS.
 */
data class CaptureState(
    val running: Boolean = false,
    val audioRecordState: String = "n/a",
    val recordingState: String = "n/a",
    val audioSource: String = "VOICE_RECOGNITION",
    val sampleRateHz: Int = 0,
    val frameLength: Int = 0,
    val framesRead: Long = 0,
    val lastRms: Double = 0.0,
    val lastPeak: Int = 0,
    val allZeroFrames: Long = 0,
    val quietFrames: Long = 0,
    val consecutiveAllZeroFrames: Long = 0,
    val readErrors: Long = 0,
    val wakeEngineName: String = "-",
    val wakeStatus: String = "-",
    val wakeDetections: Int = 0,
    val lastWakeAtEpochMs: Long? = null,
    val screenOn: Boolean = true,
    val keyguardLocked: Boolean = false,
    val micReleasedForHandoff: Boolean = false,
    val pcmSinkAttached: Boolean = false,
    val sinkDroppedFrames: Long = 0,
    val startedAtEpochMs: Long? = null,
    val lastError: String? = null,
) {
    /** Fraction of frames that arrived completely zeroed. */
    val allZeroRatio: Double get() = if (framesRead == 0L) 0.0 else allZeroFrames.toDouble() / framesRead

    /**
     * The one-line S-1 verdict for the debug UI. Deliberately conservative: it says
     * SILENT only when we are genuinely receiving nothing.
     */
    val liveness: String
        get() = when {
            !running -> "STOPPED"
            micReleasedForHandoff -> "RELEASED (Mode H handoff)"
            framesRead < 10 -> "STARTING"
            consecutiveAllZeroFrames > 50 -> "SILENT — stream is all zeros"
            allZeroRatio > 0.5 -> "MOSTLY SILENT — ${(allZeroRatio * 100).toInt()}% zero frames"
            else -> "LIVE"
        }
}
