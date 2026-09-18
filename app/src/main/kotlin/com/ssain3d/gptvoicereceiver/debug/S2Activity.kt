package com.ssain3d.gptvoicereceiver.debug

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.core.speech.SpeechMode
import com.ssain3d.gptvoicereceiver.speech.RecognizerInfo
import com.ssain3d.gptvoicereceiver.speech.SpikeSpeechProbe

/**
 * S-2 console (brief §8-4).
 *
 * Shows the provider first, then the mode, then the recognizer's own callback
 * timeline. The timeline is the deliverable of brief §12: it is what a real
 * EndpointDetector will later be tuned against. No endpointing happens here, and the
 * final result is displayed and nothing more (INV-6).
 */
class S2Activity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var providerView: TextView
    private lateinit var supportView: TextView
    private lateinit var stateView: TextView
    private lateinit var preRollInput: EditText

    private val probe by lazy { SpikeSpeechProbe(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        build()
        probe.observe { render(it) }
    }

    private fun build() {
        root.removeAllViews()
        val info = RecognizerInfo.read(this)

        with(root) {
            heading("S-2  AudioRecord → SpeechRecognizer")
            note("Hypothesis under test (R-05): the Galaxy recognizer accepts audio through EXTRA_AUDIO_SOURCE (Mode P). The documentation says a recognizer MAY ignore it and open the mic itself, so this must be measured, not assumed.")

            heading("Provider (GV-08)")
            providerView = body(
                buildString {
                    appendLine("recognition provider : ${info.providerComponent ?: "<unset>"}")
                    appendLine("recognition available: ${info.recognitionAvailable}")
                    appendLine("on-device available  : ${info.onDeviceAvailable}")
                },
                mono = true,
            )
            supportView = body("ko-KR support: (not checked)", mono = true)
            button("Check recognition support (ko-KR)") {
                supportView.text = "ko-KR support: checking…"
                RecognizerInfo.checkKoreanSupport(this@S2Activity) {
                    supportView.text = "ko-KR support: $it"
                }
            }

            heading("Pre-roll (DEVICE_TUNABLE, not a product constant)")
            preRollInput = input("preRollMs", Spike.config.preRollMs.toString())
            button("Apply pre-roll") {
                Spike.putConfig(this@S2Activity, "preRollMs", preRollInput.text.toString())
                toastConfig()
            }
            note("Mode P only. This is how much audio from BEFORE the wake instant is handed to the recognizer, so the first syllable is not lost.")

            heading("Run")
            note("Mode P needs the capture service running (we keep the mic). Mode H releases it, beeps, then lets the recognizer take the mic — speak AFTER the beep.")
            button("Start Mode P (pipe)") {
                if (!AudioCaptureService.isAlive()) {
                    AudioCaptureService.start(this@S2Activity, reason = "S2 Mode P")
                }
                root.postDelayed({ probe.start(SpeechMode.MODE_P_PIPE) }, 600)
            }
            button("Start Mode H (handoff)") { probe.start(SpeechMode.MODE_H_HANDOFF) }
            button("Stop (close audio / stop listening)") { probe.stop() }
            button("Cancel") { probe.cancel() }

            divider()
            stateView = body("", mono = true)
        }
        render(probe.current())
    }

    private fun render(s: SpikeSpeechProbe.Snapshot) {
        if (!::stateView.isInitialized) return
        stateView.text = buildString {
            appendLine("Mode      : ${if (s.mode == SpeechMode.MODE_P_PIPE) "P (pipe)" else "H (handoff)"}")
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

    private fun toastConfig() {
        android.widget.Toast.makeText(
            this, "preRollMs = ${Spike.config.preRollMs}", android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
