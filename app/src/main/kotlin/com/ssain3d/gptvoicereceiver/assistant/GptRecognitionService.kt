package com.ssain3d.gptvoicereceiver.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * Registration stub — it exists so the app shows up in the digital-assistant picker.
 *
 * ANDROID_CONSTRAINTS.md §1: the VoiceInteractionService metadata's
 * `recognitionService` attribute must point at a real RecognitionService component or
 * the assistant is not listed at all. This is that component.
 *
 * It intentionally performs no recognition. AURA consumes recognition via
 * `SpeechRecognizer` from the system provider (ADR-004); it does not provide it. Any
 * caller that binds here is told so immediately rather than being left hanging.
 *
 * If GV-01 shows the app missing from the picker, this class and its manifest entry
 * are the first things to check.
 */
class GptRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        Spike.log.log(
            SpikeId.S1, "RecognitionService", "onStartListening",
            EventResult.WARN,
            detail = "stub — returning ERROR_CLIENT; AURA does not provide recognition",
        )
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        Spike.log.log(SpikeId.S1, "RecognitionService", "onCancel")
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        Spike.log.log(SpikeId.S1, "RecognitionService", "onStopListening")
    }
}
