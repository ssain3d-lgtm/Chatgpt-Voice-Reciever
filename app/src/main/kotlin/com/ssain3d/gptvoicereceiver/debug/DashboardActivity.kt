package com.ssain3d.gptvoicereceiver.debug

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import com.ssain3d.gptvoicereceiver.BuildConfig
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * The Spike's front door (brief §5): status at a glance, one entry per spike, and a
 * button for every setting the device test needs.
 *
 * It is a diagnostics console, not a product screen. Every line is here because some
 * GV item needs it recorded.
 */
class DashboardActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        val status = SpikeStatus.read(this)
        val capture = AudioCaptureService.snapshot()

        with(root) {
            heading("Galaxy Technical Spike")
            note(AudioCaptureService.buildInfo())
            note("app ${BuildConfig.VERSION_NAME} · minSdk 33 · targetSdk 35")

            heading("Status")
            statusRow("Microphone", status.microphone)
            statusRow("Notification", status.notifications)
            statusRow("Assistant role", status.assistantRole)
            statusRow(
                "  VIS bound by system", status.assistantBound,
                if (status.assistantBound == Tri.YES) "onReady fired" else "not bound",
            )
            statusRow("Accessibility enabled", status.accessibilityEnabled)
            statusRow("  service connected", status.accessibilityConnected)
            statusRow(
                "Battery optimization", status.batteryOptimizationIgnored,
                if (status.batteryOptimizationIgnored == Tri.YES) "exempt" else "not exempt",
            )
            statusRow("Never sleeping", Tri.UNKNOWN, "no public API — verify by hand")
            statusRow(
                "ChatGPT installed", status.chatGptInstalled,
                status.chatGptVersion?.let { "v$it" },
            )
            statusRow(
                "Porcupine key", status.porcupineKey,
                status.porcupineIssue ?: "present",
            )
            statusRow(
                "Capture service", capture.running.tri(),
                capture.liveness,
            )

            status.porcupineIssue?.let {
                note("⚠ $it")
                note("Wake-word detection (GV-02) is unavailable, but screen-off microphone liveness (GV-03) can still be measured — see the S-1 screen.")
            }

            note("secure assistant = ${status.assistantSecureSetting ?: "<unset>"}")
            note("secure voice_interaction_service = ${status.voiceInteractionSetting ?: "<unset>"}")

            heading("Spikes")
            button("S-1  Assistant / Wake") { startActivity(Intent(this@DashboardActivity, S1Activity::class.java)) }
            button("S-2  Audio → STT") { startActivity(Intent(this@DashboardActivity, S2Activity::class.java)) }
            button("Logs") { startActivity(Intent(this@DashboardActivity, LogActivity::class.java)) }

            heading("Permissions & settings")
            note("Nothing here bypasses a permission. Android 13+ Restricted Settings is an intentional security control; use App info → Allow restricted settings.")

            button("Grant microphone + notifications") {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                    REQ_PERMISSIONS,
                )
            }
            button("Set as digital assistant (role request)") {
                val intent = SpikeStatus.requestAssistantRoleIntent(this@DashboardActivity)
                if (intent == null) {
                    toast("Role dialog unavailable on this device — use the settings screen below")
                } else {
                    safeStart(intent)
                }
            }
            button("Open assistant settings (Galaxy: replace Bixby here)") {
                safeStart(SpikeStatus.assistantSettingsIntent())
            }
            button("Open accessibility settings") {
                safeStart(SpikeStatus.accessibilitySettingsIntent())
            }
            button("Open app info (Allow restricted settings)") {
                safeStart(SpikeStatus.appInfoIntent(this@DashboardActivity))
            }
            button("Open notification settings") {
                safeStart(SpikeStatus.notificationSettingsIntent(this@DashboardActivity))
            }
            button("Open battery optimization list") {
                safeStart(SpikeStatus.batterySettingsIntent())
            }

            heading("Capture service")
            button("Start capture manually") {
                AudioCaptureService.start(this@DashboardActivity, reason = "dashboard")
                postRender()
            }
            button("Stop capture") {
                AudioCaptureService.stop(this@DashboardActivity)
                postRender()
            }
            note("In normal operation the capture service is started by VoiceInteractionService.onReady(), which is what keeps it inside the documented while-in-use microphone exception. Starting it here is for testing only and does NOT prove GV-03.")

            heading("Diagnostics")
            button("Copy diagnostics") {
                val text = Spike.exportDiagnostics()
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("AURA diagnostics", text))
                toast("Copied ${text.length} chars")
            }
            button("Refresh") { render() }
            divider()
            note("No audio is ever recorded to disk. Transcript logging is OFF by default (SECURITY_PRIVACY.md §5).")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Spike.log.log(
            SpikeId.APP, "Permissions", "Result", EventResult.INFO,
            detail = permissions.zip(grantResults.toTypedArray()).joinToString { "${it.first}=${it.second}" },
        )
        render()
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { toast("Could not open: ${it.message}") }
    }

    private fun postRender() = root.postDelayed({ render() }, 400)

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_PERMISSIONS = 1
    }
}
