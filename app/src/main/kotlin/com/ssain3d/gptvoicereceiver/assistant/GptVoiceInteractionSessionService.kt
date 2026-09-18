package com.ssain3d.gptvoicereceiver.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * ADR-014: deliberately in the same process as the VoiceInteractionService. Splitting
 * it with `android:process=":session"` is neither required nor recommended by the
 * official docs, and would fragment the shared DebugLog across processes — which is
 * the one thing the Spike needs intact.
 */
class GptVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Spike.log.log(SpikeId.S1, "SessionService", "onNewSession")
        return GptVoiceInteractionSession(this)
    }
}
