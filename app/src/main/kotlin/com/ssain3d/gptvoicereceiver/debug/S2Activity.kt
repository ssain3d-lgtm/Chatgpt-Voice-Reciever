package com.ssain3d.gptvoicereceiver.debug

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.speech.SpeechMode
import com.ssain3d.gptvoicereceiver.speech.RecognizerInfo
import com.ssain3d.gptvoicereceiver.speech.SpikeSpeechProbe

/**
 * S-2 console (brief §8-4).
 *
 * The recognizer selector at the top is not a convenience. AURA must declare a
 * RecognitionService to appear in the assistant picker at all, and ours is a stub
 * that answers ERROR_CLIENT by design. If selecting AURA as the assistant also makes
 * that stub the system default recognizer, an unpinned S-2 run would measure our own
 * failure and report it as "the Galaxy recognizer ignores EXTRA_AUDIO_SOURCE" — a
 * confident, wrong answer to GV-09. So the provider is always shown, the stub case is
 * called out in red, and the run can be pinned to an explicit external component.
 */
class S2Activity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var providerView: TextView
    private lateinit var supportView: TextView
    private lateinit var stateView: TextView
    private lateinit var recognizerButton: Button
    private lateinit var preRollInput: EditText

    private val probe by lazy { SpikeSpeechProbe(this) }

    /** null = system default. */
    private var selected: ComponentName? = null
    private var choices: List<ComponentName?> = listOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        build()
        probe.observe { render(it) }
    }

    private fun build() {
        root.removeAllViews()
        val info = RecognizerInfo.read(this)

        choices = listOf<ComponentName?>(null) + info.externalServices
        // If the default is our own stub, do not make the tester discover that the
        // hard way — preselect a usable external recognizer and say so.
        if (info.defaultIsOurStub && selected == null) {
            selected = RecognizerInfo.preferredExternal(this)
            if (selected != null) {
                Spike.log.log(
                    SpikeId.S2, "Probe", "RecognizerAutoPinned", EventResult.WARN,
                    detail = "system default is our own stub (${info.defaultRaw}); " +
                        "pinned ${selected?.flattenToShortString()}",
                )
            }
        }

        with(root) {
            heading("S-2  AudioRecord → SpeechRecognizer")
            note("Hypothesis under test (R-05): the Galaxy recognizer accepts audio through EXTRA_AUDIO_SOURCE (Mode P). The documentation says a recognizer MAY ignore it and open the mic itself, so this must be measured, not assumed.")

            heading("Provider (GV-08)")
            providerView = body(info.render(), mono = true)
            if (info.defaultIsOurStub) {
                providerView.setTextColor(Color.rgb(0xC6, 0x28, 0x28))
                body(
                    "⚠ The system default recognizer is AURA's own registration stub.\n" +
                        "It returns ERROR_CLIENT by design, so an unpinned run would\n" +
                        "measure nothing. Pin an external recognizer below before\n" +
                        "recording any GV-09 / GV-10 result."
                ).setTextColor(Color.rgb(0xC6, 0x28, 0x28))
            }
            if (info.externalServices.isEmpty()) {
                note("No external RecognitionService is visible. If that is unexpected, check the <queries> declaration for android.speech.RecognitionService in the manifest — without it the provider is invisible on Android 11+.")
            }

            recognizerButton = button(recognizerLabel()) { cycleRecognizer() }
            note("Tap to cycle. Pinning uses createSpeechRecognizer(Context, ComponentName). Record the chosen provider with every S-2 result — EXTRA_AUDIO_SOURCE support is a property of the provider, not of Android.")

            supportView = body("ko-KR support: (not checked)", mono = true)
            button("Check recognition support (ko-KR)") {
                supportView.text = "ko-KR support: checking…"
                RecognizerInfo.checkKoreanSupport(this@S2Activity, selected) {
                    supportView.text = "ko-KR support: $it"
                }
            }

            heading("Pre-roll (DEVICE_TUNABLE, not a product constant)")
            preRollInput = input("preRollMs", Spike.config.preRollMs.toString())
            button("Apply pre-roll") {
                Spike.putConfig(this@S2Activity, "preRollMs", preRollInput.text.toString())
                toast("preRollMs = ${Spike.config.preRollMs}")
            }
            note("Mode P only. This is how much audio from BEFORE the wake instant is handed to the recognizer, so the first syllable is not lost.")

            heading("Run")
            note("Mode P needs the capture service running (we keep the mic). Mode H releases it, waits for the release to actually complete, beeps, then lets the recognizer take the mic — speak AFTER the beep.")
            button("Start Mode P (pipe)") {
                if (!AudioCaptureService.isAlive()) {
                    AudioCaptureService.start(this@S2Activity, reason = "S2 Mode P")
                }
                root.postDelayed({ probe.start(SpeechMode.MODE_P_PIPE, selected) }, 600)
            }
            button("Start Mode H (handoff)") { probe.start(SpeechMode.MODE_H_HANDOFF, selected) }
            button("Stop (close audio / stop listening)") { probe.stop() }
            button("Cancel") { probe.cancel() }
            button("Refresh provider info") { build(); render(probe.current()) }

            divider()
            stateView = body("", mono = true)
        }
        render(probe.current())
    }

    private fun recognizerLabel(): String {
        val s = selected
        return "Recognizer: " + (s?.flattenToShortString() ?: "system default")
    }

    private fun cycleRecognizer() {
        if (choices.size <= 1) {
            toast("No alternative recognition service is visible")
            return
        }
        val i = choices.indexOf(selected)
        selected = choices[(i + 1) % choices.size]
        recognizerButton.text = recognizerLabel()
        Spike.log.log(
            SpikeId.S2, "Probe", "RecognizerSelected", EventResult.INFO,
            detail = selected?.flattenToShortString() ?: "system default",
        )
    }

    private fun render(s: SpikeSpeechProbe.Snapshot) {
        if (!::stateView.isInitialized) return
        stateView.text = buildString {
            appendLine("Mode      : ${if (s.mode == SpeechMode.MODE_P_PIPE) "P (pipe)" else "H (handoff)"}")
            appendLine("Recognizer: ${s.recognizer?.flattenToShortString() ?: "system default"}")
            appendLine("Running   : ${s.running}")
            appendLine("Verdict   : ${s.modePVerdict}")
            appendLine()
            appendLine("Partial   : ${s.partial.ifEmpty { "-" }}")
            appendLine("Final     : ${s.finalText.ifEmpty { "-" }}")
            appendLine("Segments  : ${if (s.segments.isEmpty()) "-" else s.segments.joinToString(" | ")}")
            appendLine("Error     : ${s.error.ifEmpty { "-" }}")
            appendLine()
            appendLine("Latency   : start=${s.startLatencyMs ?: "-"}ms " +
                "firstPartial=${s.firstPartialLatencyMs ?: "-"}ms final=${s.finalLatencyMs ?: "-"}ms")
            appendLine("Pipe      : ${s.pipeBytesWritten} bytes written, ${s.pipeFramesDropped} frames dropped")
            appendLine()
            appendLine("Timeline (brief §12 — recorded for future endpoint tuning):")
            if (s.timeline.isEmpty()) appendLine("  -") else s.timeline.forEach { appendLine("  $it") }
            appendLine()
            appendLine("NOTE: onResults is a SEGMENT result. It is displayed only and is")
            appendLine("deliberately not wired to anything that sends (INV-6).")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
