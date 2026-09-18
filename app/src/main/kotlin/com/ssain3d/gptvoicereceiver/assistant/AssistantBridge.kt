package com.ssain3d.gptvoicereceiver.assistant

import android.os.Bundle

/**
 * Lets the debug screens reach the live VoiceInteractionService.
 *
 * Only `VoiceInteractionSession` may call `startAssistantActivity`, and only the
 * VoiceInteractionService may call `showSession`. The debug UI therefore cannot launch
 * ChatGPT "as the assistant" by itself — it has to go through the real assistant path,
 * which is exactly the path GV-14/GV-15 are meant to measure.
 *
 * Safe as a static field because the Spike is single-process (ADR-014).
 */
object AssistantBridge {

    const val EXTRA_ACTION = "aura.action"
    const val ACTION_LAUNCH_CHATGPT = "launch_chatgpt"
    const val ACTION_SHOW_ONLY = "show_only"
    const val EXTRA_LAUNCH_INTENT_KIND = "aura.launch_kind"

    /** "launcher" or "share" — which intent the session should hand to ChatGPT. */
    const val LAUNCH_KIND_LAUNCHER = "launcher"
    const val LAUNCH_KIND_SHARE = "share"
    const val EXTRA_SHARE_TEXT = "aura.share_text"

    @Volatile
    private var service: GptVoiceInteractionService? = null

    @Volatile
    var lastSessionEvent: String = "(none)"
        internal set

    fun attach(s: GptVoiceInteractionService) {
        service = s
    }

    fun detach(s: GptVoiceInteractionService) {
        if (service === s) service = null
    }

    /** True when the system has bound us as the current assistant. */
    val isAssistantBound: Boolean get() = service != null

    /**
     * @return false when we are not the current digital assistant, so the caller can
     *         fall back to a plain foreground `startActivity` and RECORD THAT IT DID —
     *         the two paths are not equivalent and must not be conflated in results.
     */
    fun requestSession(args: Bundle): Boolean {
        val s = service ?: return false
        s.showSessionFromDebug(args)
        return true
    }
}
