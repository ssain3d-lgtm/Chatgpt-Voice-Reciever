package com.ssain3d.gptvoicereceiver.core.log

import com.ssain3d.gptvoicereceiver.core.session.TurnId

/** Which Technical Spike an event belongs to (MVP_PLAN.md §1). */
enum class SpikeId {
    /** VoiceInteractionService + screen-off wake feasibility. */
    S1,

    /** AudioRecord -> SpeechRecognizer pipeline feasibility. */
    S2,

    /** Official ChatGPT app accessibility injection / send feasibility. */
    S3,

    /** Not tied to one spike: permissions, roles, app lifecycle. */
    APP,
}

enum class EventResult { OK, FAIL, WARN, INFO }

/**
 * One structured debug record.
 *
 * Fields are exactly the minimum set required by the Spike brief:
 * timestamp, spike id, optional turn id, component, event, result, latency, error.
 *
 * SECURITY_PRIVACY.md §5: this type never carries audio, never carries the Porcupine
 * AccessKey, and never carries ChatGPT answer text. Raw transcripts reach [detail]
 * only through [DebugLog.transcript], which masks unless transcript logging is
 * explicitly enabled (default OFF).
 */
data class DebugEvent(
    val atEpochMs: Long,
    val spike: SpikeId,
    val component: String,
    val event: String,
    val result: EventResult = EventResult.INFO,
    val turnId: TurnId? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
    val detail: String? = null,
) {
    /** Single-line form used by the on-device log viewer and by diagnostics export. */
    fun format(clock: TimeFormatter): String = buildString {
        append(clock.format(atEpochMs))
        append(' ').append(result.name.padEnd(4))
        append(' ').append(spike.name.padEnd(3))
        append(' ').append(component).append('/').append(event)
        turnId?.let { append(" [").append(it).append(']') }
        latencyMs?.let { append(" +").append(it).append("ms") }
        detail?.let { append(" | ").append(it) }
        error?.let { append(" | ERR=").append(it) }
    }
}

/** Injected so `core` stays free of platform date formatting. */
fun interface TimeFormatter {
    fun format(epochMs: Long): String
}
