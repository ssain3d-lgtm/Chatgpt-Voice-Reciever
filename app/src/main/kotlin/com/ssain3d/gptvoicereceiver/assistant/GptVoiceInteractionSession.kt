package com.ssain3d.gptvoicereceiver.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.TextView
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.debug.body
import com.ssain3d.gptvoicereceiver.debug.column
import com.ssain3d.gptvoicereceiver.debug.heading

/**
 * The assistant session window (ADR-008: this, and no SYSTEM_ALERT_WINDOW overlay).
 *
 * In the Spike it does two jobs:
 *   1. Proves the session UI can be shown at all, including over the lock screen
 *      (GV-04) — a wake detection flashes it.
 *   2. Provides the ONLY legitimate `startAssistantActivity` path, which is how
 *      GV-14 (warm) and GV-15 (cold) ChatGPT launches must be measured.
 *
 * It does NOT run a turn. No STT, no endpointing, no sending is wired to it.
 */
class GptVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null

    override fun onCreateContentView(): View {
        val root = context.column(padding = 20) {
            heading("✦ GPT — Spike")
            statusView = body("세션이 표시되었습니다 (session shown)")
            body("Technical Spike build — no question is sent from here.").apply {
                textSize = 11f
                alpha = 0.7f
            }
        }
        Spike.log.log(SpikeId.S1, "Session", "onCreateContentView", EventResult.OK)
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val action = args?.getString(AssistantBridge.EXTRA_ACTION)
        AssistantBridge.lastSessionEvent = "onShow action=$action"
        Spike.log.log(
            SpikeId.S1, "Session", "onShow", EventResult.OK,
            detail = "showFlags=$showFlags action=$action",
        )

        when (action) {
            AssistantBridge.ACTION_LAUNCH_CHATGPT -> launchChatGpt(args)
            else -> {
                statusView?.text = "듣고 있습니다… (spike: display only)"
                handler.postDelayed({ runCatching { hide() } }, SESSION_AUTO_HIDE_MS)
            }
        }
    }

    override fun onHide() {
        Spike.log.log(SpikeId.S1, "Session", "onHide")
        super.onHide()
    }

    /**
     * GV-14 / GV-15. ANDROID_CONSTRAINTS.md §2: the API exists (`CONFIRMED`); what
     * ChatGPT actually shows in response is `DEVICE_TEST_REQUIRED`. We only record
     * that the call was made and whether it threw — the resulting screen is read
     * separately by the S-3 node inspector, so the two are never conflated.
     */
    private fun launchChatGpt(args: Bundle) {
        val kind = args.getString(AssistantBridge.EXTRA_LAUNCH_INTENT_KIND)
            ?: AssistantBridge.LAUNCH_KIND_LAUNCHER

        val intent: Intent? = when (kind) {
            AssistantBridge.LAUNCH_KIND_SHARE -> {
                val text = args.getString(AssistantBridge.EXTRA_SHARE_TEXT).orEmpty()
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(Spike.CHATGPT_PACKAGE)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            }

            else -> context.packageManager.getLaunchIntentForPackage(Spike.CHATGPT_PACKAGE)
        }

        if (intent == null) {
            Spike.log.log(
                SpikeId.S3, "Session", "StartAssistantActivity", EventResult.FAIL,
                error = "no launch intent for ${Spike.CHATGPT_PACKAGE} — is it installed?",
            )
            statusView?.text = "ChatGPT를 찾을 수 없습니다"
            return
        }

        statusView?.text = "처리 중… (launching ChatGPT)"
        val t0 = SystemClock.elapsedRealtime()
        val outcome = runCatching { startAssistantActivity(intent) }

        Spike.log.log(
            SpikeId.S3, "Session", "StartAssistantActivity",
            if (outcome.isSuccess) EventResult.OK else EventResult.FAIL,
            latencyMs = SystemClock.elapsedRealtime() - t0,
            error = outcome.exceptionOrNull()?.toString(),
            detail = "path=startAssistantActivity kind=$kind",
        )

        // Get out of ChatGPT's way so the launched screen can actually be observed.
        handler.postDelayed({ runCatching { hide() } }, LAUNCH_AUTO_HIDE_MS)
    }

    private companion object {
        const val SESSION_AUTO_HIDE_MS = 2500L
        const val LAUNCH_AUTO_HIDE_MS = 600L
    }
}
