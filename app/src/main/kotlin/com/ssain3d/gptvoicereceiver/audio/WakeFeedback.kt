package com.ssain3d.gptvoicereceiver.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.assistant.AssistantBridge
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * Wake acknowledgement (brief §7-4): a short haptic, a short tone, and the session UI.
 *
 * The tone is not decoration. In Mode H it is the synchronisation cue — the moment the
 * microphone has actually changed hands and it is safe to start speaking
 * (ARCHITECTURE.md §4.3). Keeping it in one place means S-1 and S-2 produce the same
 * cue, so first-syllable-loss measurements are comparable between them.
 */
object WakeFeedback {

    private var tone: ToneGenerator? = null

    fun signal(context: Context, showSession: Boolean = true) {
        vibrate(context)
        beep()
        if (showSession) {
            val shown = AssistantBridge.requestSession(
                Bundle().apply {
                    putString(AssistantBridge.EXTRA_ACTION, AssistantBridge.ACTION_SHOW_ONLY)
                }
            )
            if (!shown) {
                Spike.log.log(
                    SpikeId.S1, "WakeFeedback", "SessionNotShown", EventResult.WARN,
                    detail = "app is not the current digital assistant, so there is no " +
                        "session to show — set it in the dashboard (GV-01)",
                )
            }
        }
    }

    /** Short tone. Safe to call with the screen off. */
    fun beep() {
        runCatching {
            val generator = tone ?: ToneGenerator(
                AudioManager.STREAM_NOTIFICATION, TONE_VOLUME,
            ).also { tone = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
        }.onFailure {
            Spike.log.log(
                SpikeId.S1, "WakeFeedback", "BeepFailed", EventResult.WARN, error = it.toString(),
            )
        }
    }

    fun vibrate(context: Context) {
        runCatching {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return
            manager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }.onFailure {
            Spike.log.log(
                SpikeId.S1, "WakeFeedback", "VibrateFailed", EventResult.WARN, error = it.toString(),
            )
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }

    private const val TONE_VOLUME = 70
    private const val TONE_MS = 120
    private const val VIBRATE_MS = 60L
}
