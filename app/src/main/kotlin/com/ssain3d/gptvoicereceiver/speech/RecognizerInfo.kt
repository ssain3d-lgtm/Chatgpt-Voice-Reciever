package com.ssain3d.gptvoicereceiver.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * GV-08. Establishes *which* recognizer we are talking to before any conclusion is
 * drawn from S-2: `EXTRA_AUDIO_SOURCE` support is a property of the provider
 * implementation, not of Android (ANDROID_CONSTRAINTS.md §8), so a Mode P result is
 * meaningless without naming the provider it was measured against.
 *
 * It also guards against a trap this app creates for itself. To appear in the
 * digital-assistant picker at all, AURA must declare a `RecognitionService`
 * (ANDROID_CONSTRAINTS.md §1) — and ours is a stub that answers `ERROR_CLIENT`.
 * If selecting AURA as the assistant also points `voice_recognition_service` at that
 * stub, then S-2 would be testing our own deliberate failure and reporting it as
 * "Galaxy does not support EXTRA_AUDIO_SOURCE". That is the worst possible outcome
 * of this spike: a confident, wrong NO on a hypothesis that was never tested.
 *
 * So the provider is always resolved and compared against our own package, and S-2
 * can be pinned to an explicit external component via
 * `SpeechRecognizer.createSpeechRecognizer(Context, ComponentName)`.
 */
object RecognizerInfo {

    /**
     * `Settings.Secure` key for the current recognition service. It has no public
     * constant (the field is hidden), so the documented key name is used directly.
     * Read-only; equivalent to `adb shell settings get secure voice_recognition_service`.
     */
    private const val KEY_VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    data class Info(
        val defaultComponent: ComponentName?,
        val defaultRaw: String?,
        val recognitionAvailable: Boolean,
        val onDeviceAvailable: Boolean,
        /** Installed recognition services excluding ours. These are safe to test against. */
        val externalServices: List<ComponentName>,
        /** True when the system default recognizer is AURA's own registration stub. */
        val defaultIsOurStub: Boolean,
    ) {
        fun render(): String = buildString {
            appendLine("default provider     : ${defaultRaw ?: "<unset>"}")
            appendLine("recognition available: $recognitionAvailable")
            appendLine("on-device available  : $onDeviceAvailable")
            appendLine("external services    : ${externalServices.size}")
            externalServices.forEach { appendLine("   ${it.flattenToShortString()}") }
            if (defaultIsOurStub) {
                appendLine()
                appendLine("!! The system default recognizer is OUR OWN stub.")
                appendLine("!! It always returns ERROR_CLIENT by design.")
                appendLine("!! S-2 must be pinned to an external service, or every")
                appendLine("!! Mode P / Mode H result would be meaningless.")
            }
        }
    }

    fun read(context: Context): Info {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, KEY_VOICE_RECOGNITION_SERVICE)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val default = raw?.let { ComponentName.unflattenFromString(it) }

        return Info(
            defaultComponent = default,
            defaultRaw = raw,
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            onDeviceAvailable = runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false),
            externalServices = listRecognitionServices(context)
                .filter { it.packageName != context.packageName },
            defaultIsOurStub = default?.packageName == context.packageName,
        )
    }

    /**
     * Every installed `RecognitionService`, ours included.
     *
     * Requires the `<queries><intent><action android:name="android.speech.
     * RecognitionService" /></intent></queries>` declaration the SpeechRecognizer docs
     * mandate from Android 11; without it this returns an empty list and the whole of
     * S-2 is unmeasurable.
     */
    fun listRecognitionServices(context: Context): List<ComponentName> = runCatching {
        context.packageManager
            .queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.ResolveInfoFlags.of(0L),
            )
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * Picks a recognizer that is safe to measure against.
     *
     * @return the component to pin S-2 to, or null meaning "the system default is fine".
     */
    fun preferredExternal(context: Context): ComponentName? {
        val info = read(context)
        if (!info.defaultIsOurStub) return null
        val external = info.externalServices
        // Prefer the platform providers if present; otherwise take whatever exists.
        return external.firstOrNull { it.packageName.startsWith("com.google.android") }
            ?: external.firstOrNull { it.packageName.startsWith("com.samsung") }
            ?: external.firstOrNull()
    }

    /**
     * `checkRecognitionSupport` (API 33). Asynchronous, main thread, and it may simply
     * never call back on some providers — hence the caller-visible timeout in the UI
     * rather than an indefinite "checking…".
     *
     * @param component pin the query to this recognizer, or null for the system default.
     */
    fun checkKoreanSupport(
        context: Context,
        component: ComponentName?,
        onResult: (String) -> Unit,
    ) {
        if (component == null && read(context).defaultIsOurStub) {
            onResult(
                "refused: the system default recognizer is AURA's own stub. " +
                    "Pin an external recognizer first."
            )
            return
        }

        val recognizer = runCatching {
            if (component == null) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            }
        }.getOrElse {
            onResult("createSpeechRecognizer failed: $it")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_KO)
        }

        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                val text = buildString {
                    append("installedOnDevice=").append(recognitionSupport.installedOnDeviceLanguages)
                    append(" pendingOnDevice=").append(recognitionSupport.pendingOnDeviceLanguages)
                    append(" supportedOnDevice=").append(recognitionSupport.supportedOnDeviceLanguages)
                    append(" online=").append(recognitionSupport.onlineLanguages)
                }
                Spike.log.log(
                    SpikeId.S2, "RecognitionSupport", "ko-KR", EventResult.OK,
                    detail = "provider=${component?.flattenToShortString() ?: "default"} $text",
                )
                onResult(text)
                runCatching { recognizer.destroy() }
            }

            override fun onError(error: Int) {
                val text = "checkRecognitionSupport error=$error (${errorName(error)})"
                Spike.log.log(
                    SpikeId.S2, "RecognitionSupport", "ko-KR", EventResult.FAIL, error = text,
                )
                onResult(text)
                runCatching { recognizer.destroy() }
            }
        }

        runCatching {
            recognizer.checkRecognitionSupport(intent, context.mainExecutor, callback)
        }.onFailure {
            onResult("checkRecognitionSupport threw: $it")
            runCatching { recognizer.destroy() }
        }
    }

    const val LANGUAGE_KO = "ko-KR"

    fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "UNKNOWN($code)"
    }
}
