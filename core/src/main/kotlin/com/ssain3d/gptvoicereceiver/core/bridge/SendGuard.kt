package com.ssain3d.gptvoicereceiver.core.bridge

import com.ssain3d.gptvoicereceiver.core.session.TurnId

/**
 * The duplicate-send guard. This is the single most important invariant in the
 * product: PRODUCT_SPEC.md §10 S8 requires **0 duplicate sends** and says that target
 * is not adjustable; R-08 says one duplicate blocks release.
 *
 * Contract (CHATGPT_BRIDGE.md §7, INV-5):
 *
 *   1. [claim] is called **before** the Send click, not after. If the click throws,
 *      or the process dies mid-click, the turn is already burned and no code path
 *      exists that can click again.
 *   2. A second [claim] for the same turn returns [Claim.ALREADY_CLAIMED]; the caller
 *      returns `SendFailed("duplicate call")` and does not touch the UI.
 *
 * Implementation note — why a high-water mark rather than a set:
 *
 *   Turn ids are issued by [com.ssain3d.gptvoicereceiver.core.session.TurnIdGenerator]
 *   and strictly increase. So "已 claimed" is exactly "not greater than the highest id
 *   ever claimed", which needs no unbounded bookkeeping and cannot be defeated by
 *   eviction of old entries.
 *
 *   If a caller ever supplies a non-monotonic id (a bug, or a restarted generator),
 *   the guard REJECTS it. That is the deliberately safe direction: a missing send is
 *   recoverable by the user, a duplicate send is not.
 *
 * Thread-safe.
 */
class SendGuard {

    enum class Claim {
        /** Caller owns this turn's single send attempt. Proceed. */
        GRANTED,

        /** This turn was already attempted. Do not touch the UI. */
        ALREADY_CLAIMED,

        /** Id is not a real turn ([TurnId.NONE]). Programming error. */
        INVALID,
    }

    private val lock = Any()
    private var highWaterMark: Long = 0L
    private var grantedCount: Int = 0
    private var rejectedCount: Int = 0

    fun claim(turnId: TurnId): Claim = synchronized(lock) {
        if (turnId == TurnId.NONE || turnId.value <= 0L) {
            rejectedCount++
            return Claim.INVALID
        }
        if (turnId.value <= highWaterMark) {
            rejectedCount++
            return Claim.ALREADY_CLAIMED
        }
        highWaterMark = turnId.value
        grantedCount++
        Claim.GRANTED
    }

    /** Non-mutating query, for UI/diagnostics. Never use this to gate a send. */
    fun isClaimed(turnId: TurnId): Boolean =
        synchronized(lock) { turnId.value in 1..highWaterMark }

    fun grantedCount(): Int = synchronized(lock) { grantedCount }

    fun rejectedCount(): Int = synchronized(lock) { rejectedCount }

    fun highWaterMark(): TurnId = synchronized(lock) { TurnId(highWaterMark) }

    /**
     * Test/diagnostic only. There is no production path that clears the guard: a turn
     * that has been attempted stays attempted for the life of the process.
     */
    fun resetForTest() = synchronized(lock) {
        highWaterMark = 0L
        grantedCount = 0
        rejectedCount = 0
    }
}
