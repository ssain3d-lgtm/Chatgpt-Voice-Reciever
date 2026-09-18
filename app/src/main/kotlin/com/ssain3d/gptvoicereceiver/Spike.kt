package com.ssain3d.gptvoicereceiver

import android.content.Context
import android.content.SharedPreferences
import com.ssain3d.gptvoicereceiver.core.bridge.SendGuard
import com.ssain3d.gptvoicereceiver.core.config.SpikeConfig
import com.ssain3d.gptvoicereceiver.core.log.DebugLog
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.log.TimeFormatter
import com.ssain3d.gptvoicereceiver.core.session.TurnIdGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide Spike state.
 *
 * A plain object is correct here because ADR-014 keeps the Spike single-process: the
 * VoiceInteractionService, the session, the capture service, the accessibility service
 * and the debug activities all share this instance, which is exactly what makes one
 * unified log view possible.
 */
object Spike {

    const val TAG = "GptVoice"

    /** CHATGPT_BRIDGE.md. The only place the target package name is written down. */
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    val log = DebugLog(capacity = 400)

    /** Every turn in the process, including S-3's synthetic bridge turns. */
    val turnIds = TurnIdGenerator()

    /**
     * ONE guard for the whole process. Sharing it is the point: no matter which screen
     * or code path initiates a send, the same monotonic claim applies (R-08 / S8).
     */
    val sendGuard = SendGuard()

    @Volatile
    var config: SpikeConfig = SpikeConfig()
        private set

    private const val PREFS = "aura_spike"

    val timeFormatter = TimeFormatter { epochMs ->
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
    }

    fun init(context: Context) {
        reloadConfig(context)
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** INV-8: these come from settings, never from constants in the calling code. */
    fun reloadConfig(context: Context): SpikeConfig {
        val stored = prefs(context).all.entries
            .mapNotNull { (k, v) -> v?.toString()?.let { k to it } }
            .toMap()
        val result = SpikeConfig.parse(stored)
        config = result.config
        result.warnings.forEach {
            log.log(SpikeId.APP, "Config", "Warning", EventResult.WARN, detail = it)
        }
        log.transcriptLoggingEnabled = result.config.transcriptLogging
        return result.config
    }

    fun putConfig(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
        reloadConfig(context)
    }

    fun exportDiagnostics(): String = log.export(timeFormatter)
}
