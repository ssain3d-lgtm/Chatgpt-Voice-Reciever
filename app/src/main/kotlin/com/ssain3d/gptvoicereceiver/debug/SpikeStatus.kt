package com.ssain3d.gptvoicereceiver.debug

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.ssain3d.gptvoicereceiver.BuildConfig
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.assistant.AssistantBridge
import com.ssain3d.gptvoicereceiver.assistant.GptVoiceInteractionService
import com.ssain3d.gptvoicereceiver.chatgptbridge.ChatGptAccessibilityService
import com.ssain3d.gptvoicereceiver.wakeword.PorcupineWakeWordEngine

/**
 * The onboarding checklist from brief §14, as queryable state.
 *
 * Deliberately uses three states, not two. Several things here genuinely cannot be
 * read by a third-party app — Samsung's "Never sleeping apps" list has no public API —
 * and reporting those as ✗ would be a false negative that sends the user chasing a
 * problem that may not exist. Unknown is reported as unknown (ADR-012).
 *
 * Nothing here works around a permission. Android 13+ Restricted Settings in
 * particular is an intentional security control (SECURITY_PRIVACY.md §6); the app
 * explains the legitimate route and never attempts to bypass it.
 */
data class SpikeStatus(
    val microphone: Tri,
    val notifications: Tri,
    val assistantRole: Tri,
    val assistantSecureSetting: String?,
    val voiceInteractionSetting: String?,
    val assistantBound: Tri,
    val accessibilityEnabled: Tri,
    val accessibilityConnected: Tri,
    val batteryOptimizationIgnored: Tri,
    val neverSleeping: Tri,
    val chatGptInstalled: Tri,
    val chatGptVersion: String?,
    val porcupineKey: Tri,
    val porcupineIssue: String?,
) {
    companion object {

        fun read(context: Context): SpikeStatus {
            val pm = context.packageManager

            val chatGpt = runCatching { pm.getPackageInfo(Spike.CHATGPT_PACKAGE, 0) }.getOrNull()
            val power = context.getSystemService(PowerManager::class.java)

            val secureAssistant = secure(context, "assistant")
            val secureVis = secure(context, "voice_interaction_service")
            val ours = ComponentName(context, GptVoiceInteractionService::class.java).flattenToString()

            val roleHeld = runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            }.getOrDefault(false)

            // The Secure setting is the ground truth; the role API is a cross-check.
            val settingMatchesUs = secureVis?.contains(context.packageName) == true ||
                secureVis == ours

            val porcupineIssue = PorcupineWakeWordEngine.describeMissing(context)

            return SpikeStatus(
                microphone = (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED).tri(),
                notifications = (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED).tri(),
                assistantRole = (roleHeld || settingMatchesUs).tri(),
                assistantSecureSetting = secureAssistant,
                voiceInteractionSetting = secureVis,
                assistantBound = AssistantBridge.isAssistantBound.tri(),
                accessibilityEnabled = ChatGptAccessibilityService.isEnabledInSettings(context).tri(),
                accessibilityConnected = ChatGptAccessibilityService.isConnected().tri(),
                batteryOptimizationIgnored = runCatching {
                    power?.isIgnoringBatteryOptimizations(context.packageName)
                }.getOrNull()?.tri() ?: Tri.UNKNOWN,
                // Samsung's sleeping-apps list has no public API. Not knowable from here.
                neverSleeping = Tri.UNKNOWN,
                chatGptInstalled = (chatGpt != null).tri(),
                chatGptVersion = chatGpt?.versionName,
                porcupineKey = when {
                    BuildConfig.PICOVOICE_ACCESS_KEY.isBlank() -> Tri.NO
                    porcupineIssue != null -> Tri.UNKNOWN
                    else -> Tri.YES
                },
                porcupineIssue = porcupineIssue,
            )
        }

        private fun secure(context: Context, key: String): String? = runCatching {
            Settings.Secure.getString(context.contentResolver, key)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        // --------------------------------------------------------- settings intents

        /**
         * ROLE_ASSISTANT cannot be requested via a dialog on every device, so this may
         * legitimately return null. The voice-input settings screen is the route that
         * always works, and on a Galaxy it is where Bixby is swapped out.
         */
        fun requestAssistantRoleIntent(context: Context): Intent? = runCatching {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
            rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()

        fun assistantSettingsIntent(): Intent =
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        fun appInfoIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )

        fun notificationSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        /**
         * The battery-optimization LIST, not the per-app allow dialog: the dialog needs
         * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, and the Spike does not ask for a
         * permission it can do without.
         */
        fun batterySettingsIntent(): Intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
