package com.ssain3d.gptvoicereceiver.debug

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.assistant.AssistantBridge
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.audio.WakeFeedback

/**
 * S-1 console.
 *
 * The number that decides GV-03 is the zero-frame count, not "is the service running".
 * A service that is up but handed a muted stream looks healthy from every other angle,
 * so this screen puts liveness, zero frames and the screen/keyguard state next to each
 * other and refreshes them while the screen is off.
 */
class S1Activity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var live: TextView

    private val tick = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        build()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun build() {
        root.removeAllViews()
        with(root) {
            heading("S-1  VoiceInteractionService + screen-off wake")
            note("Hypothesis under test (R-02): an FGS started by a VoiceInteractionService keeps receiving real microphone audio with the screen off. Android documents the exception; whether One UI honours it is what we are measuring.")

            heading("Live capture")
            live = body("…", mono = true)

            heading("Actions")
            button("Start capture (manual)") {
                AudioCaptureService.start(this@S1Activity, reason = "S1 screen")
            }
            button("Stop capture") { AudioCaptureService.stop(this@S1Activity) }
            button("Test wake feedback (haptic + beep + session)") {
                WakeFeedback.signal(this@S1Activity)
            }
            button("Show assistant session only") {
                val shown = AssistantBridge.requestSession(
                    Bundle().apply {
                        putString(AssistantBridge.EXTRA_ACTION, AssistantBridge.ACTION_SHOW_ONLY)
                    }
                )
                if (!shown) {
                    android.widget.Toast.makeText(
                        this@S1Activity,
                        "Not the current digital assistant — set it on the dashboard first (GV-01).",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }

            heading("How to run GV-03 (screen OFF)")
            note(
                """
                1. Grant the microphone, set this app as the digital assistant.
                2. Confirm 'VIS bound by system = ✓' on the dashboard. The capture
                   service must have been started by onReady(), not by the button above.
                3. Unlock the phone, then turn the screen OFF with the power button.
                4. Wait 1 min / 5 min / 30 min. Speak near the phone at each point.
                5. Turn the screen on and read the AudioLevel lines in Logs.

                Reading the result:
                   zeroFrames climbing, consecZero large   -> FAIL. The stream is muted.
                                                              Look for "Foreground service
                                                              started from background can
                                                              not have ... microphone
                                                              access" in logcat.
                   peak/rms varying with your voice        -> PASS. The mic is live.
                """.trimIndent()
            )

            heading("Wake word")
            note("Porcupine needs two files that are NOT in the repository (licensed material):\n  app/src/main/assets/wakeword/keyword.ppn   (custom \"헤이 지피티\")\n  app/src/main/assets/wakeword/params_ko.pv  (Korean parameters)\nand PICOVOICE_ACCESS_KEY in local.properties.\nWithout them the app runs normally and GV-03 is still measurable; only GV-02 (detection/false-accept rate) is not.")
        }
        updateLive()
    }

    private fun updateLive() {
        if (!::live.isInitialized) return
        val s = AudioCaptureService.snapshot()
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)

        live.text = buildString {
            appendLine("liveness        ${s.liveness}")
            appendLine("service alive   ${AudioCaptureService.isAlive()}")
            appendLine("running         ${s.running}")
            appendLine("AudioRecord     ${s.audioRecordState} / ${s.recordingState}")
            appendLine("source          ${s.audioSource}  ${s.sampleRateHz} Hz  frame=${s.frameLength}")
            appendLine("frames read     ${s.framesRead}")
            appendLine("last rms/peak   ${"%.1f".format(s.lastRms)} / ${s.lastPeak}")
            appendLine("zero frames     ${s.allZeroFrames}  (consec ${s.consecutiveAllZeroFrames})")
            appendLine("quiet frames    ${s.quietFrames}")
            appendLine("read errors     ${s.readErrors}")
            appendLine("wake engine     ${s.wakeEngineName}")
            appendLine("wake status     ${s.wakeStatus}")
            appendLine("wake detections ${s.wakeDetections}")
            appendLine("last wake       ${s.lastWakeAtEpochMs?.let { Spike.timeFormatter.format(it) } ?: "-"}")
            appendLine("screen on       ${pm?.isInteractive}")
            appendLine("keyguard locked ${km?.isKeyguardLocked}")
            appendLine("VIS bound       ${AssistantBridge.isAssistantBound}")
            appendLine("last session    ${AssistantBridge.lastSessionEvent}")
            s.lastError?.let { appendLine("last error      $it") }
            AudioCaptureService.lastStartFailure?.let { appendLine("\nSTART FAILED    $it") }
        }
    }
}
