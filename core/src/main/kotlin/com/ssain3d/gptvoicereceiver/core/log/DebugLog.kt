package com.ssain3d.gptvoicereceiver.core.log

import com.ssain3d.gptvoicereceiver.core.session.TurnId

/**
 * In-memory ring buffer of [DebugEvent] (SECURITY_PRIVACY.md §5, ARCHITECTURE.md §3.1).
 *
 * - Memory only. Nothing is written to disk unless the user explicitly exports.
 * - Bounded: oldest events are dropped once [capacity] is reached.
 * - Audio is never stored, here or anywhere else.
 *
 * Thread-safe: audio thread, main thread and the accessibility service thread all
 * append to it.
 */
class DebugLog(
    val capacity: Int = DEFAULT_CAPACITY,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<DebugEvent>(capacity)
    private var droppedCount: Long = 0

    /**
     * SECURITY_PRIVACY.md §5: raw transcript text is recorded only in a debug build
     * with the user opt-in ON. Default OFF.
     */
    @Volatile
    var transcriptLoggingEnabled: Boolean = false

    fun add(event: DebugEvent) {
        synchronized(lock) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
                droppedCount++
            }
            buffer.addLast(event)
        }
    }

    fun log(
        spike: SpikeId,
        component: String,
        event: String,
        result: EventResult = EventResult.INFO,
        turnId: TurnId? = null,
        latencyMs: Long? = null,
        error: String? = null,
        detail: String? = null,
    ) = add(
        DebugEvent(
            atEpochMs = now(),
            spike = spike,
            component = component,
            event = event,
            result = result,
            turnId = turnId,
            latencyMs = latencyMs,
            error = error,
            detail = detail,
        )
    )

    /** Oldest first. */
    fun snapshot(): List<DebugEvent> = synchronized(lock) { buffer.toList() }

    fun size(): Int = synchronized(lock) { buffer.size }

    /** How many events fell off the front since the last [clear]. */
    fun dropped(): Long = synchronized(lock) { droppedCount }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            droppedCount = 0
        }
    }

    /**
     * Masks a transcript unless transcript logging is explicitly enabled.
     *
     * Release behaviour and the default debug behaviour record only the length, so a
     * diagnostics export cannot leak what the user said.
     */
    fun transcript(text: String?): String {
        if (text == null) return "<null>"
        return if (transcriptLoggingEnabled) text else "<text len=${text.length}>"
    }

    fun export(formatter: TimeFormatter): String {
        val events = snapshot()
        val dropped = dropped()
        return buildString {
            appendLine("# AURA Galaxy Technical Spike — diagnostics")
            appendLine("# events=${events.size} capacity=$capacity dropped=$dropped")
            appendLine("# transcriptLogging=${if (transcriptLoggingEnabled) "ON" else "OFF"}")
            appendLine("# No audio is recorded by this app. See docs/SECURITY_PRIVACY.md")
            appendLine()
            events.forEach { appendLine(it.format(formatter)) }
        }
    }

    companion object {
        /** SECURITY_PRIVACY.md §5 initial value. */
        const val DEFAULT_CAPACITY = 200
    }
}
