package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * Spike S-3.
 *
 * Scope, twice over (SECURITY_PRIVACY.md P-4 / §6):
 *   1. The service is declared with `packageNames="com.openai.chatgpt"`, so the system
 *      only ever delivers us events from that app.
 *   2. [activeChatGptRoot] re-checks the root window's package before returning
 *      anything, so even an unexpected event cannot lead to another app being read.
 *
 * ARCHITECTURE.md §6.3: accessibility events are NOT forwarded into session state. The
 * service keeps a coalesced note of the last window it saw, and the probe polls it.
 * That is what keeps an event storm from becoming a state-machine storm.
 */
class ChatGptAccessibilityService : AccessibilityService() {

    @Volatile
    var lastWindowPackage: String? = null
        private set

    @Volatile
    var lastWindowClass: String? = null
        private set

    @Volatile
    var lastWindowAtMs: Long = 0
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Spike.log.log(
            SpikeId.S3, "A11yService", "Connected", EventResult.OK,
            detail = "scoped to ${Spike.CHATGPT_PACKAGE}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != Spike.CHATGPT_PACKAGE) return
        // Coalesced: just remember what we saw. No traversal, no logging per event —
        // typing in ChatGPT produces a flood of TYPE_WINDOW_CONTENT_CHANGED.
        lastWindowPackage = pkg
        lastWindowClass = event.className?.toString()
        lastWindowAtMs = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Spike.log.log(SpikeId.S3, "A11yService", "Interrupt", EventResult.WARN)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Spike.log.log(SpikeId.S3, "A11yService", "Unbind", EventResult.WARN)
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Spike.log.log(SpikeId.S3, "A11yService", "Destroyed", EventResult.WARN)
        super.onDestroy()
    }

    /**
     * @return the active window root, but ONLY when it belongs to ChatGPT. Returning
     *         null for anything else is the enforcement point for P-4.
     */
    fun activeChatGptRoot(): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val pkg = root.packageName?.toString()
        if (pkg != Spike.CHATGPT_PACKAGE) {
            Spike.log.log(
                SpikeId.S3, "A11yService", "ForeignWindowIgnored", EventResult.WARN,
                detail = "foreground package is $pkg — bring ChatGPT to the front first",
            )
            return null
        }
        return root
    }

    companion object {
        @Volatile
        private var instance: ChatGptAccessibilityService? = null

        fun get(): ChatGptAccessibilityService? = instance

        /** Bound and running right now. */
        fun isConnected(): Boolean = instance != null

        /**
         * Whether the user has enabled us in Settings. Distinct from [isConnected]:
         * enabled-but-not-connected is a real state on Samsung after an update, and
         * conflating the two makes GV-20 unreadable.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${ChatGptAccessibilityService::class.java.name}"
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
