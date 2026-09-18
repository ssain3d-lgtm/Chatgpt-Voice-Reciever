package com.ssain3d.gptvoicereceiver.debug

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.chatgptbridge.ChatGptAccessibilityProbe
import com.ssain3d.gptvoicereceiver.chatgptbridge.ChatGptAccessibilityService
import com.ssain3d.gptvoicereceiver.chatgptbridge.ChatGptLauncher
import com.ssain3d.gptvoicereceiver.chatgptbridge.ShareIntentProbe

/**
 * S-3 console. The decisive screen for the product (ADR-015).
 *
 * Two safety properties are structural, not conventions:
 *   - "Inject Test Text" can never send. It has no code path to a click.
 *   - "Inject + Send" is the only sender, sits behind a confirmation, and goes
 *     through SendGuard, which burns the turn BEFORE the click (INV-5).
 */
class S3Activity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var output: TextView
    private lateinit var textInput: EditText
    private lateinit var sendInput: EditText

    private val probe by lazy { ChatGptAccessibilityProbe(this) }
    private val share by lazy { ShareIntentProbe(this) }
    private val launcher by lazy { ChatGptLauncher(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        root.removeAllViews()
        with(root) {
            heading("S-3  Official ChatGPT app bridge")
            note("Hypothesis under test (R-01): the real ChatGPT Android app exposes an editable composer and a findable, clickable Send. Nothing about this is documented by OpenAI, so every line below is a measurement.")

            statusRow(
                "Accessibility enabled",
                ChatGptAccessibilityService.isEnabledInSettings(this@S3Activity).tri(),
            )
            statusRow("Service connected", ChatGptAccessibilityService.isConnected().tri())
            note("Scoped to ${Spike.CHATGPT_PACKAGE} by packageNames, and the traversal re-checks the foreground package before reading anything. No other app is ever read.")

            heading("1 · Node inspector (GV-11)")
            note("Open ChatGPT on a conversation, come back, press Inspect. Only editable/clickable nodes are collected, so the answer text is never dumped.")
            button("Inspect ChatGPT accessibility tree") { show(probe.inspect()) }

            heading("2 · ACTION_SET_TEXT (GV-12) — never sends")
            textInput = input("test text", DEFAULT_TEST_TEXT)
            button("Inject Test Text") { show(probe.injectTestText(textInput.text.toString())) }
            note("Injects, reads the composer back, and stops. Send is not pressed.")

            heading("3 · Inject + Send (GV-13) — THIS SENDS")
            sendInput = input("diagnostic text", DEFAULT_SEND_TEXT)
            button("Inject + Send Test") { confirmSend() }
            note("One ACTION_CLICK, never a retry. If the composer does not clear, the result is SendFailed and the Spike stops — the message may or may not have gone out, and you must check ChatGPT yourself. Missing send is better than duplicate send.")
            note("SendGuard: ${Spike.sendGuard.grantedCount()} granted, ${Spike.sendGuard.rejectedCount()} rejected, highWater=${Spike.sendGuard.highWaterMark()}")

            heading("4 · ChatGPT launch (GV-14 warm / GV-15 cold)")
            note("Warm: open a specific ChatGPT conversation, press Home, then Warm launch.\nCold: adb shell am force-stop com.openai.chatgpt, then Cold launch.\nRecord what actually appears: same conversation / conversation list / new chat / other. Do not assume.")
            button("Warm launch") { show("Warm launch", true, launcher.launch("warm")) }
            button("Cold launch") { show("Cold launch", true, launcher.launch("cold")) }

            heading("5 · Plan B share intent (GV-21)")
            button("Query share target") {
                show("Share target", true, share.queryTarget().render())
            }
            button("Share test text to ChatGPT") {
                show("Share", true, share.share(sendInput.text.toString()))
            }
            note("Record: does a target exist, does ChatGPT open, is the text prefilled, is it a new or the same conversation, does it auto-send, is a user tap required? That last answer decides the Go/No-Go (CHATGPT_BRIDGE.md §10).")

            divider()
            output = body("", mono = true)
        }
    }

    private fun confirmSend() {
        AlertDialog.Builder(this)
            .setTitle("Send to ChatGPT?")
            .setMessage(
                "This will type the text into the ChatGPT composer and press Send once.\n\n" +
                    "It will post to whatever conversation is currently open.\n\n" +
                    "\"${sendInput.text}\""
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send once") { _, _ ->
                show("Send", true, "running…")
                probe.injectAndSend(sendInput.text.toString()) { result -> show(result) }
            }
            .show()
    }

    private fun show(result: ChatGptAccessibilityProbe.Result) =
        show(result.headline, result.ok, result.detail)

    private fun show(headline: String, ok: Boolean, detail: String) {
        if (!::output.isInitialized) return
        output.text = buildString {
            appendLine(if (ok) "▶ $headline" else "✗ $headline")
            appendLine()
            append(detail)
        }
    }

    private companion object {
        const val DEFAULT_TEST_TEXT = "AURA accessibility bridge test"
        const val DEFAULT_SEND_TEXT = "AURA bridge diagnostic test. Reply with OK."
    }
}
