package com.ssain3d.gptvoicereceiver.core.session

/**
 * INV-3, made executable.
 *
 * Asynchronous producers (the recognizer, the accessibility service, timers) keep
 * delivering after a turn has moved on. [TurnGate] is the single place that decides
 * whether an inbound turn-scoped event still belongs to the current turn.
 *
 * The full `SessionStateMachine` (ARCHITECTURE.md §6) is NOT part of the Technical
 * Spike. This is only the staleness rule it will later use, isolated so that S-2 and
 * S-3 probes cannot accidentally act on a late callback.
 *
 * Not thread-safe by itself; the Spike drives it from the main thread. v0.1 will own
 * it inside the state-machine actor (ARCHITECTURE.md §7.1), which serialises access.
 */
class TurnGate {

    var current: TurnId = TurnId.NONE
        private set

    /** Number of events rejected as stale. Surfaced in the debug UI for race diagnosis. */
    var staleCount: Int = 0
        private set

    fun open(turnId: TurnId) {
        current = turnId
    }

    fun close() {
        current = TurnId.NONE
    }

    /**
     * @return true when [turnId] is the turn currently in progress.
     *         A false result must lead to the event being discarded and logged as
     *         `StaleEvent` (SECURITY_PRIVACY.md §5), never applied.
     */
    fun accepts(turnId: TurnId): Boolean {
        val ok = turnId != TurnId.NONE && turnId == current
        if (!ok) staleCount++
        return ok
    }

    fun resetCounters() {
        staleCount = 0
    }
}
