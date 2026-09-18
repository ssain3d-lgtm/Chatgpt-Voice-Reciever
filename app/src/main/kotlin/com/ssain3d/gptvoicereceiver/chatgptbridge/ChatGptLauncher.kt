package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.assistant.AssistantBridge
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * GV-14 (warm) and GV-15 (cold).
 *
 * ANDROID_CONSTRAINTS.md §2 is careful about a distinction this class preserves: the
 * `startAssistantActivity` API existing is `CONFIRMED`, but what ChatGPT SHOWS in
 * response is `DEVICE_TEST_REQUIRED`, and cold-start conversation continuity is
 * explicitly `NOT GUARANTEED`. So this class launches and records; it never claims an
 * outcome. The observer records what appeared.
 */
class ChatGptLauncher(private val context: Context) {

    /**
     * @return a human-readable note about which path was taken. The two paths are not
     *         equivalent and results from them must not be pooled.
     */
    fun launch(note: String): String {
        val viaAssistant = AssistantBridge.requestSession(
            Bundle().apply {
                putString(AssistantBridge.EXTRA_ACTION, AssistantBridge.ACTION_LAUNCH_CHATGPT)
                putString(
                    AssistantBridge.EXTRA_LAUNCH_INTENT_KIND,
                    AssistantBridge.LAUNCH_KIND_LAUNCHER,
                )
            }
        )
        if (viaAssistant) {
            Spike.log.log(
                SpikeId.S3, "Launcher", "Launch", EventResult.INFO,
                detail = "path=startAssistantActivity note=$note",
            )
            return "Launched via startAssistantActivity (the real assistant path)."
        }

        val intent = context.packageManager.getLaunchIntentForPackage(Spike.CHATGPT_PACKAGE)
        if (intent == null) {
            Spike.log.log(
                SpikeId.S3, "Launcher", "Launch", EventResult.FAIL,
                error = "no launch intent for ${Spike.CHATGPT_PACKAGE}",
            )
            return "ChatGPT does not appear to be installed."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val outcome = runCatching { context.startActivity(intent) }
        Spike.log.log(
            SpikeId.S3, "Launcher", "Launch",
            if (outcome.isSuccess) EventResult.OK else EventResult.FAIL,
            error = outcome.exceptionOrNull()?.toString(),
            detail = "path=startActivity(foreground fallback) note=$note",
        )
        return if (outcome.isSuccess) {
            "Launched with a plain startActivity — this is NOT the assistant path.\n" +
                "GV-14/GV-15 must be recorded against startAssistantActivity, so set " +
                "this app as the digital assistant and repeat."
        } else {
            "Launch failed: ${outcome.exceptionOrNull()}"
        }
    }
}
