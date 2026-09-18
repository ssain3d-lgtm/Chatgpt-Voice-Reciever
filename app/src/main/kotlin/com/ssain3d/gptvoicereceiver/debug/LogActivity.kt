package com.ssain3d.gptvoicereceiver.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import java.io.File

/**
 * Log viewer (brief §13).
 *
 * Export writes to the app's own cache directory and is shared via a plain text
 * ACTION_SEND so no FileProvider or extra permission is needed. Audio is never
 * exported — there is none to export (SECURITY_PRIVACY.md §4).
 */
class LogActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var output: TextView
    private var filter: SpikeId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(scroller(column { root = this }))
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun build() {
        root.removeAllViews()
        with(root) {
            heading("Debug log")
            note("in-memory ring buffer, capacity ${Spike.log.capacity} — oldest events are dropped")

            button("All") { filter = null; refresh() }
            button("S-1 only") { filter = SpikeId.S1; refresh() }
            button("S-2 only") { filter = SpikeId.S2; refresh() }
            button("S-3 only") { filter = SpikeId.S3; refresh() }

            button("Copy diagnostics") {
                val text = Spike.exportDiagnostics()
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("AURA diagnostics", text))
                toast("Copied ${text.length} chars")
            }
            button("Export diagnostics") { export() }
            button("Clear") { Spike.log.clear(); refresh() }
            button("Refresh") { refresh() }

            val transcriptOn = Spike.log.transcriptLoggingEnabled
            button("Transcript logging: ${if (transcriptOn) "ON" else "OFF"} (tap to toggle)") {
                Spike.putConfig(
                    this@LogActivity, "transcriptLogging",
                    if (transcriptOn) "false" else "true",
                )
                build()
                refresh()
            }
            note("Default OFF. When OFF, recognizer text is recorded only as a length, so an exported log cannot leak what was said.")

            divider()
            output = body("", mono = true)
        }
    }

    private fun refresh() {
        if (!::output.isInitialized) return
        val events = Spike.log.snapshot().filter { filter == null || it.spike == filter }
        output.text = if (events.isEmpty()) {
            "(no events${filter?.let { " for $it" } ?: ""})"
        } else {
            events.joinToString("\n") { it.format(Spike.timeFormatter) }
        }
    }

    private fun export() {
        runCatching {
            val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "aura-spike-${System.currentTimeMillis()}.txt")
            file.writeText(Spike.exportDiagnostics())
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "AURA Galaxy Spike diagnostics")
                        putExtra(Intent.EXTRA_TEXT, file.readText())
                    },
                    "Export diagnostics",
                )
            )
        }.onFailure { toast("Export failed: ${it.message}") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
