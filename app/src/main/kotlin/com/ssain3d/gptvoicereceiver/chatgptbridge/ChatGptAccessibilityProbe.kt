package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.bridge.ChatBridgeResult
import com.ssain3d.gptvoicereceiver.core.bridge.SendGuard
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.session.TurnId

/**
 * Spike S-3, Plan A. Measures the four things CHATGPT_BRIDGE.md §10.2 defines as
 * "Plan A success":
 *
 *   A1 composer found as editable + visible
 *   A2 ACTION_SET_TEXT lands and reads back
 *   A3 Send candidate found and becomes enabled
 *   A4 ONE ACTION_CLICK clears the composer
 *
 * The two entry points are deliberately separate, and only the second sends anything:
 * [injectTestText] never clicks Send, so the composer path can be explored without any
 * risk of posting to a real conversation.
 *
 * INV-5 is enforced by [SendGuard]: the turn is claimed BEFORE the click, so no code
 * path — not an exception, not a retry, not a double tap — can produce a second click
 * for the same turn. Missing send is better than duplicate send.
 */
class ChatGptAccessibilityProbe(private val context: Context) {

    data class Result(
        val headline: String,
        val ok: Boolean,
        val detail: String,
    )

    private val handler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------- inspection

    fun inspect(): Result {
        val service = ChatGptAccessibilityService.get()
            ?: return Result("Accessibility service not connected", false, ENABLE_HINT)

        val root = service.activeChatGptRoot()
            ?: return Result(
                "ChatGPT is not in the foreground",
                false,
                "Open ${Spike.CHATGPT_PACKAGE} on a conversation screen, then come back " +
                    "and press Inspect. Last window seen: ${service.lastWindowClass ?: "none"}",
            )

        val t0 = SystemClock.elapsedRealtime()
        val report = NodeInspector.inspect(root, screenRect())
        val elapsed = SystemClock.elapsedRealtime() - t0

        Spike.log.log(
            SpikeId.S3, "NodeInspector", "Scan",
            if (report.composerIndex != null) EventResult.OK else EventResult.FAIL,
            latencyMs = elapsed,
            detail = "nodes=${report.totalNodesVisited} candidates=${report.candidates.size} " +
                "composer=${report.composerIndex ?: "NONE"} send=${report.sendIndex ?: "NONE"}",
        )

        val headline = when {
            report.composerIndex == null -> "A1 FAIL — no editable composer node"
            report.sendIndex == null -> "A1 OK, A3 FAIL — composer found, no Send candidate"
            else -> "A1 OK, A3 candidate found — record contentDescription in GV-11"
        }
        return Result(headline, report.composerIndex != null, report.render())
    }

    // ------------------------------------------------------- A2: set text, no send

    /**
     * GV-12. Injects and verifies. Explicitly does NOT press Send (brief §9-4).
     */
    fun injectTestText(text: String): Result {
        val scan = scanOrNull() ?: return Result("ChatGPT not readable", false, ENABLE_HINT)
        val composer = scan.composerNode()
            ?: return Result("A2 FAIL — no composer node to inject into", false, scan.report.render())

        val t0 = SystemClock.elapsedRealtime()
        val setOk = performSetText(composer, text)
        val readBack = readComposerText(composer)
        val elapsed = SystemClock.elapsedRealtime() - t0
        val matches = readBack == text

        Spike.log.log(
            SpikeId.S3, "Bridge", "ActionSetText",
            if (matches) EventResult.OK else EventResult.FAIL,
            latencyMs = elapsed,
            detail = "performAction=$setOk readBackMatches=$matches " +
                "expectedLen=${text.length} actualLen=${readBack?.length ?: -1}",
        )

        val detail = buildString {
            appendLine("performAction(ACTION_SET_TEXT) returned: $setOk")
            appendLine("composer text after injection: ${readBack?.let { "\"$it\"" } ?: "<null>"}")
            appendLine("matches injected text: $matches")
            appendLine("elapsed: $elapsed ms")
            appendLine()
            appendLine("No Send was pressed. Nothing has been posted to the conversation.")
            if (!matches) {
                appendLine()
                appendLine("GV-12 fail path: next step per CHATGPT_BRIDGE.md §3 is")
                appendLine("ACTION_FOCUS -> clipboard -> ACTION_PASTE. Not automated in the Spike.")
            }
        }
        return Result(
            if (matches) "A2 PASS — ACTION_SET_TEXT verified" else "A2 FAIL — text did not read back",
            matches,
            detail,
        )
    }

    // ------------------------------------------------- A4: set text, then ONE click

    /**
     * GV-13. This is the only method in the Spike that can post to a real conversation,
     * which is why the text is an unmistakable diagnostic string chosen by the caller
     * and why the whole thing runs behind its own button.
     */
    fun injectAndSend(text: String, onComplete: (Result) -> Unit) {
        val turnId = Spike.turnIds.next()

        val scan = scanOrNull()
        if (scan == null) {
            onComplete(Result("ChatGPT not readable", false, ENABLE_HINT))
            return
        }
        val composer = scan.composerNode()
        if (composer == null) {
            onComplete(Result("A2 FAIL — no composer node", false, scan.report.render()))
            return
        }

        // --- inject and verify before even looking for Send
        if (!performSetText(composer, text) || readComposerText(composer) != text) {
            Spike.log.log(
                SpikeId.S3, "Bridge", "InjectionFailed", EventResult.FAIL, turnId = turnId,
                detail = "aborting before Send; nothing was clicked",
            )
            onComplete(
                Result(
                    "A2 FAIL — injection did not verify, Send NOT attempted",
                    false,
                    "The composer did not read back the injected text, so no click was made.\n" +
                        "Result: ${ChatBridgeResult.InjectionFailed(turnId, "set text not verified")}",
                )
            )
            return
        }

        // --- re-scan: the Send button usually only becomes enabled once text exists
        val afterInject = scanOrNull()
        val sendNode = afterInject?.sendNode()
        if (sendNode == null) {
            Spike.log.log(
                SpikeId.S3, "Bridge", "SendNodeNotFound", EventResult.FAIL, turnId = turnId,
            )
            onComplete(
                Result(
                    "A3 FAIL — text is in the composer but no Send node was found",
                    false,
                    "Result: ${ChatBridgeResult.InjectedButNotSent(turnId, "send node not found")}\n" +
                        "The text is still sitting in the composer. Nothing was sent.\n\n" +
                        (afterInject?.report?.render() ?: ""),
                )
            )
            return
        }
        if (!sendNode.isEnabled) {
            Spike.log.log(
                SpikeId.S3, "Bridge", "SendNodeDisabled", EventResult.FAIL, turnId = turnId,
            )
            onComplete(
                Result(
                    "A3 FAIL — Send candidate is disabled even with text present",
                    false,
                    "Result: ${ChatBridgeResult.InjectedButNotSent(turnId, "send node disabled")}\n" +
                        "Either the candidate is not really Send, or the app has not " +
                        "registered the injected text.\n\n" + afterInject.report.render(),
                )
            )
            return
        }

        // --- claim BEFORE clicking. CHATGPT_BRIDGE.md §7 / INV-5.
        when (val claim = Spike.sendGuard.claim(turnId)) {
            SendGuard.Claim.GRANTED -> Unit
            else -> {
                Spike.log.log(
                    SpikeId.S3, "Bridge", "DuplicateSendBlocked", EventResult.WARN, turnId = turnId,
                    detail = "claim=$claim",
                )
                onComplete(
                    Result(
                        "Blocked — this turn was already sent",
                        false,
                        "Result: ${ChatBridgeResult.SendFailed(turnId, "duplicate call")}\n" +
                            "SendGuard refused ($claim). This is the guard working as intended.",
                    )
                )
                return
            }
        }

        val clickedAt = SystemClock.elapsedRealtime()
        val clicked = runCatching {
            sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)

        Spike.log.log(
            SpikeId.S3, "Bridge", "SendClick",
            if (clicked) EventResult.OK else EventResult.FAIL,
            turnId = turnId,
            detail = "performAction(ACTION_CLICK)=$clicked — exactly one click, no retry path exists",
        )

        // --- verify at the configured probe points (GV-13 measures WHEN it clears)
        verifyComposerCleared(turnId, clickedAt, clicked, onComplete)
    }

    private fun verifyComposerCleared(
        turnId: TurnId,
        clickedAt: Long,
        clicked: Boolean,
        onComplete: (Result) -> Unit,
    ) {
        val probes = Spike.config.sendVerifyProbesMs
        val observations = mutableListOf<String>()
        var everCleared = false

        fun probeAt(i: Int) {
            if (i >= probes.size) {
                val cleared = everCleared
                val result = if (cleared) {
                    ChatBridgeResult.Sent(turnId, composerVerified = true)
                } else {
                    ChatBridgeResult.SendFailed(turnId, "composer not cleared")
                }
                Spike.log.log(
                    SpikeId.S3, "Bridge", "SendVerdict",
                    if (cleared) EventResult.OK else EventResult.FAIL,
                    turnId = turnId,
                    latencyMs = SystemClock.elapsedRealtime() - clickedAt,
                    detail = result.toString(),
                )
                onComplete(
                    Result(
                        headline = if (cleared) {
                            "A4 PASS — one click, composer cleared"
                        } else {
                            "A4 FAIL — composer did not clear. NOT retrying."
                        },
                        ok = cleared,
                        detail = buildString {
                            appendLine("ACTION_CLICK returned: $clicked")
                            observations.forEach { appendLine(it) }
                            appendLine()
                            appendLine("Result: $result")
                            if (!cleared) {
                                appendLine()
                                appendLine("IMPORTANT: the message may or may not have been sent.")
                                appendLine("The Spike will not click again (INV-5). Check ChatGPT")
                                appendLine("yourself and record the truth in GV-13/GV-16.")
                            }
                        },
                    )
                )
                return
            }

            val delay = probes[i] - (SystemClock.elapsedRealtime() - clickedAt)
            handler.postDelayed({
                val text = scanOrNull()?.composerNode()?.let { readComposerText(it) }
                val cleared = text.isNullOrEmpty()
                if (cleared) everCleared = true
                observations += "  t+${probes[i]}ms: cleared=$cleared len=${text?.length ?: 0}"
                Spike.log.log(
                    SpikeId.S3, "Bridge", "ComposerProbe",
                    if (cleared) EventResult.OK else EventResult.INFO,
                    turnId = turnId, latencyMs = probes[i].toLong(),
                    detail = "cleared=$cleared len=${text?.length ?: 0}",
                )
                probeAt(i + 1)
            }, delay.coerceAtLeast(0))
        }

        probeAt(0)
    }

    // ---------------------------------------------------------------------- helpers

    private fun scanOrNull(): NodeInspector.Scan? {
        val root = ChatGptAccessibilityService.get()?.activeChatGptRoot() ?: return null
        return NodeInspector.scan(root, screenRect())
    }

    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    /** Refreshes first: a node handle captured before the action reports stale text. */
    private fun readComposerText(node: AccessibilityNodeInfo): String? {
        runCatching { node.refresh() }
        return node.text?.toString()
    }

    private fun screenRect(): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm?.currentWindowMetrics?.bounds
        return bounds ?: Rect(0, 0, 1080, 2400)
    }

    private companion object {
        const val ENABLE_HINT =
            "Enable 'AURA Spike — ChatGPT bridge' in Settings > Accessibility.\n" +
                "On a side-loaded APK, Android 13+ may first require\n" +
                "App info > (⋮) > Allow restricted settings (GV-20)."
    }
}
