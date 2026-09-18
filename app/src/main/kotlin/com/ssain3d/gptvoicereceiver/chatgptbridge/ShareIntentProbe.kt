package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.assistant.AssistantBridge
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * Spike S-3, Plan B (GV-21). CHATGPT_BRIDGE.md §4.
 *
 * Why it is tested even when Plan A works: ADR-015 makes Plan B the deciding factor in
 * the Go/No-Go. If Plan A fails and Plan B still needs a user tap on Send, the
 * fully hands-free ChatGPT App Bridge is NO-GO — so "does a share target exist" and
 * "can it send without a tap" are separate questions that both need real answers.
 *
 * The share text is never decorated with a prefix — CHATGPT_BRIDGE.md §4 forbids
 * polluting the user's text.
 */
class ShareIntentProbe(private val context: Context) {

    data class TargetInfo(
        val exists: Boolean,
        val activities: List<String>,
        val chatGptInstalled: Boolean,
        val chatGptVersion: String?,
    ) {
        fun render(): String = buildString {
            appendLine("ChatGPT installed: $chatGptInstalled" + (chatGptVersion?.let { " (v$it)" } ?: ""))
            appendLine("ACTION_SEND text/plain target exists: $exists")
            if (activities.isEmpty()) {
                appendLine("  (no matching activity)")
            } else {
                activities.forEach { appendLine("  $it") }
            }
        }
    }

    fun queryTarget(): TargetInfo {
        val pm = context.packageManager

        val installed = runCatching {
            pm.getPackageInfo(Spike.CHATGPT_PACKAGE, 0)
        }.getOrNull()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(Spike.CHATGPT_PACKAGE)
        }
        val matches = runCatching {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        }.getOrDefault(emptyList())

        val info = TargetInfo(
            exists = matches.isNotEmpty(),
            activities = matches.map {
                "${it.activityInfo.packageName}/${it.activityInfo.name}"
            },
            chatGptInstalled = installed != null,
            chatGptVersion = installed?.versionName,
        )

        Spike.log.log(
            SpikeId.S3, "ShareIntent", "QueryTarget",
            if (info.exists) EventResult.OK else EventResult.FAIL,
            detail = "installed=${info.chatGptInstalled} version=${info.chatGptVersion} " +
                "targets=${info.activities.size}",
        )
        return info
    }

    /**
     * Fires the share.
     *
     * Prefers the assistant path so the result is comparable with Plan A's launch, and
     * ALWAYS records which path was used — a share from a foreground activity and a
     * share via `startAssistantActivity` are not the same test.
     */
    fun share(text: String): String {
        val viaAssistant = AssistantBridge.requestSession(
            Bundle().apply {
                putString(AssistantBridge.EXTRA_ACTION, AssistantBridge.ACTION_LAUNCH_CHATGPT)
                putString(
                    AssistantBridge.EXTRA_LAUNCH_INTENT_KIND,
                    AssistantBridge.LAUNCH_KIND_SHARE,
                )
                putString(AssistantBridge.EXTRA_SHARE_TEXT, text)
            }
        )
        if (viaAssistant) {
            Spike.log.log(
                SpikeId.S3, "ShareIntent", "Share", EventResult.INFO,
                detail = "path=startAssistantActivity len=${text.length}",
            )
            return "Shared via startAssistantActivity (assistant path)."
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(Spike.CHATGPT_PACKAGE)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val outcome = runCatching { context.startActivity(intent) }
        Spike.log.log(
            SpikeId.S3, "ShareIntent", "Share",
            if (outcome.isSuccess) EventResult.OK else EventResult.FAIL,
            error = outcome.exceptionOrNull()?.toString(),
            detail = "path=startActivity(foreground fallback) len=${text.length}",
        )
        return if (outcome.isSuccess) {
            "Shared via plain startActivity — NOT the assistant path. " +
                "Set this app as the digital assistant to test the real path."
        } else {
            "Share failed: ${outcome.exceptionOrNull()}"
        }
    }
}
