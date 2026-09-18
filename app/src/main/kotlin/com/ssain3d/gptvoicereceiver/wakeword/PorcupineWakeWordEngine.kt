package com.ssain3d.gptvoicereceiver.wakeword

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import com.ssain3d.gptvoicereceiver.BuildConfig
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordEngine
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordStatus
import java.io.File

/**
 * ADR-002: the v0.1 wake-word candidate, behind core's [WakeWordEngine] so the
 * architecture does not depend on it.
 *
 * Asset contract — nothing here is committed (see .gitignore, SECURITY_PRIVACY.md §9):
 *
 *   app/src/main/assets/wakeword/keyword.ppn        custom "헤이 지피티" model
 *   app/src/main/assets/wakeword/params_ko.pv       Korean model parameters
 *
 * The Korean `params` file is required for a Korean keyword: Porcupine ships English
 * parameters by default, and a ko keyword with en parameters does not work. Licensing
 * terms for the AccessKey and for custom `.ppn` files MUST BE VERIFIED AGAINST CURRENT
 * PICOVOICE TERMS (R-13) — this file makes no claim about them.
 *
 * Every failure mode here is reported, never thrown (brief §7-3).
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val sensitivity: Float = DEFAULT_SENSITIVITY,
) : WakeWordEngine {

    private var porcupine: Porcupine? = null

    override var status: WakeWordStatus = WakeWordStatus.Stopped
        private set

    override val name: String get() = "Porcupine"

    override val frameLength: Int get() = porcupine?.frameLength ?: FALLBACK_FRAME_LENGTH

    override val sampleRate: Int get() = porcupine?.sampleRate ?: FALLBACK_SAMPLE_RATE

    override fun start(): WakeWordStatus {
        if (porcupine != null) {
            status = WakeWordStatus.Running
            return status
        }

        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank()) {
            status = WakeWordStatus.Unavailable(MISSING_KEY)
            return status
        }

        val keyword = copyAssetToCache(ASSET_KEYWORD)
        if (keyword == null) {
            status = WakeWordStatus.Unavailable(
                "missing asset $ASSET_DIR/$ASSET_KEYWORD (custom \"헤이 지피티\" .ppn)"
            )
            return status
        }
        // Optional: an English keyword works without it, a Korean one does not.
        val params = copyAssetToCache(ASSET_PARAMS)

        return try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keyword.absolutePath)
                .apply { params?.let { setModelPath(it.absolutePath) } }
                .setSensitivity(sensitivity)
                .build(context)

            status = WakeWordStatus.Running
            Spike.log.log(
                SpikeId.S1, "Porcupine", "Started", EventResult.OK,
                detail = "frameLength=$frameLength sampleRate=$sampleRate " +
                    "sensitivity=$sensitivity params=${params?.name ?: "<default/en>"}",
            )
            status
        } catch (t: Throwable) {
            // Wrong key, expired model, unsupported ABI — all reportable, none fatal.
            status = WakeWordStatus.Failed(t.message ?: t.toString())
            Spike.log.log(
                SpikeId.S1, "Porcupine", "InitFailed", EventResult.FAIL,
                error = t.message ?: t.toString(),
            )
            status
        }
    }

    override fun process(frame: ShortArray): Int {
        val p = porcupine ?: return WakeWordEngine.NO_DETECTION
        return try {
            p.process(frame)
        } catch (t: Throwable) {
            status = WakeWordStatus.Failed(t.message ?: t.toString())
            Spike.log.log(
                SpikeId.S1, "Porcupine", "ProcessFailed", EventResult.FAIL,
                error = t.message ?: t.toString(),
            )
            WakeWordEngine.NO_DETECTION
        }
    }

    override fun stop() {
        status = WakeWordStatus.Stopped
    }

    override fun release() {
        runCatching { porcupine?.delete() }
        porcupine = null
        status = WakeWordStatus.Stopped
    }

    /** Porcupine needs real filesystem paths, not asset streams. */
    private fun copyAssetToCache(name: String): File? = try {
        val out = File(context.cacheDir, "wakeword/$name")
        out.parentFile?.mkdirs()
        context.assets.open("$ASSET_DIR/$name").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ASSET_DIR = "wakeword"
        const val ASSET_KEYWORD = "keyword.ppn"
        const val ASSET_PARAMS = "params_ko.pv"

        const val MISSING_KEY = "Porcupine key missing"

        /** DEFAULT_INITIAL_VALUE / DEVICE_TUNABLE — tune against GV-02, do not freeze. */
        const val DEFAULT_SENSITIVITY = 0.5f

        private const val FALLBACK_FRAME_LENGTH = 512
        private const val FALLBACK_SAMPLE_RATE = 16_000

        /** What the debug UI should say when the engine cannot come up. */
        fun describeMissing(context: Context): String? {
            if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank()) return MISSING_KEY
            val assets = runCatching { context.assets.list(ASSET_DIR)?.toList() }
                .getOrNull().orEmpty()
            if (ASSET_KEYWORD !in assets) return "keyword model missing ($ASSET_DIR/$ASSET_KEYWORD)"
            if (ASSET_PARAMS !in assets) return "Korean params missing ($ASSET_DIR/$ASSET_PARAMS)"
            return null
        }
    }
}
