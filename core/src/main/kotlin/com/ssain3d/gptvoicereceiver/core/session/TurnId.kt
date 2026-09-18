package com.ssain3d.gptvoicereceiver.core.session

import java.util.concurrent.atomic.AtomicLong

/**
 * One Wake Word detection = one turn.
 *
 * ARCHITECTURE.md §7.2 / INV-3: every turn-scoped call and every turn-scoped callback
 * carries a [TurnId]. Events whose id does not match the current turn are dropped,
 * never applied.
 */
@JvmInline
value class TurnId(val value: Long) : Comparable<TurnId> {

    override fun compareTo(other: TurnId): Int = value.compareTo(other.value)

    override fun toString(): String = "turn#$value"

    companion object {
        /** Sentinel for "no turn". Never issued by [TurnIdGenerator]. */
        val NONE: TurnId = TurnId(0L)
    }
}

/**
 * Issues strictly increasing turn ids.
 *
 * Monotonicity is relied upon by [com.ssain3d.gptvoicereceiver.core.bridge.SendGuard];
 * see that class for why.
 *
 * Not a global singleton: the Spike creates one per process and tests create their own.
 */
class TurnIdGenerator(startAt: Long = 0L) {
    private val counter = AtomicLong(startAt)

    fun next(): TurnId = TurnId(counter.incrementAndGet())

    /** Most recently issued id, or [TurnId.NONE] if none has been issued yet. */
    fun current(): TurnId = TurnId(counter.get())
}
