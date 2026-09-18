package com.ssain3d.gptvoicereceiver.speech

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

/**
 * GV-08. Establishes *which* recognizer we are actually talking to before any
 * conclusion is drawn from S-2: `EXTRA_AUDIO_SOURCE` support is a property of the
 * provider implementation, not of Android (ANDROID_CONSTRAINTS.md §8), so a Mode P
 * result is meaningless without naming the provider it was measured against.
 */
object RecognizerInfo {

    /**
     * `Settings.Secure` key for the current recognition service. It has no public
     * constant (the field is hidden), so the documented key name is used directly.
     * Read-only; equivalent to `adb shell settings get secure voice_recognition_service`.
     */
    private const val KEY_VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    data class Info(
        val providerComponent: String?,
        val recognitionAvailable: Boolean,
        val onDeviceAvailable: Boolean,
    )

    fun read(context: Context): Info = Info(
        providerComponent = runCatching {
            Settings.Secure.getString(context.contentResolver, KEY_VOICE_RECOGNITION_SERVICE)
        }.getOrNull(),
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false),
    )

    /**
     * `checkRecognitionSupport` (API 33). Asynchronous, main thread, and it may simply
     * never call back on some providers — hence the caller-visible timeout in the UI
     * rather than an indefinite "checking…".
     */
    fun checkKoreanSupport(
        context: Context,
        onResult: (String) -> Unit,
    ) {
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrElse {
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
                    SpikeId.S2, "RecognitionSupport", "ko-KR", EventResult.OK, detail = text,
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
