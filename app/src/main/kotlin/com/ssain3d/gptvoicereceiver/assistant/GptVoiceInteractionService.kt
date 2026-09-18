package com.ssain3d.gptvoicereceiver.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * Spike S-1. ADR-001: the whole product rests on this class.
 *
 * Why it matters: a plain foreground service started from the background loses
 * microphone access on Android 14+. The documented while-in-use exception list names
 * "the service starts by an app which provides the VoiceInteractionService"
 * (ANDROID_CONSTRAINTS.md §4) — so [AudioCaptureService] is started from [onReady]
 * here, and nowhere else, to stay inside that exception.
 *
 * Whether One UI actually honours it with the screen off is R-02 / GV-03: the single
 * most important unknown in S-1.
 */
class GptVoiceInteractionService : VoiceInteractionService() {

    override fun onCreate() {
        super.onCreate()
        AssistantBridge.attach(this)
        Spike.log.log(SpikeId.S1, "VIS", "onCreate", EventResult.OK)
    }

    override fun onReady() {
        super.onReady()
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        Spike.log.log(
            SpikeId.S1, "VIS", "onReady", EventResult.OK,
            detail = "recordAudioGranted=$hasMic",
        )

        if (hasMic) {
            // The one and only production start point for capture (INV-1 + ADR-001).
            AudioCaptureService.start(this, reason = "VIS.onReady")
        } else {
            Spike.log.log(
                SpikeId.S1, "VIS", "CaptureNotStarted", EventResult.FAIL,
                error = "RECORD_AUDIO not granted — grant it in the dashboard, then " +
                    "re-select the assistant or use 'Start capture'",
            )
        }
    }

    override fun onShutdown() {
        Spike.log.log(
            SpikeId.S1, "VIS", "onShutdown", EventResult.WARN,
            detail = "assistant role likely handed back to another app",
        )
        super.onShutdown()
    }

    override fun onDestroy() {
        AssistantBridge.detach(this)
        Spike.log.log(SpikeId.S1, "VIS", "onDestroy", EventResult.WARN)
        super.onDestroy()
    }

    /** Only the VoiceInteractionService may call showSession (ANDROID_CONSTRAINTS §2). */
    internal fun showSessionFromDebug(args: Bundle) {
        Spike.log.log(
            SpikeId.S1, "VIS", "showSession", EventResult.INFO,
            detail = "action=${args.getString(AssistantBridge.EXTRA_ACTION)}",
        )
        runCatching { showSession(args, 0) }
            .onFailure {
                Spike.log.log(
                    SpikeId.S1, "VIS", "showSession", EventResult.FAIL,
                    error = it.toString(),
                )
            }
    }
}
