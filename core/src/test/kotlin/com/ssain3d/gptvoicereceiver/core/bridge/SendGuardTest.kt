package com.ssain3d.gptvoicereceiver.core.bridge

import com.ssain3d.gptvoicereceiver.core.session.TurnId
import com.ssain3d.gptvoicereceiver.core.session.TurnIdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC.md §10 S8 — duplicate sends must be 0, and that target is not
 * adjustable. These tests are the executable form of that requirement.
 */
class SendGuardTest {

    @Test
    fun `first claim is granted`() {
        val guard = SendGuard()
        assertEquals(SendGuard.Claim.GRANTED, guard.claim(TurnId(1)))
    }

    @Test
    fun `second claim for the same turn is refused`() {
        val guard = SendGuard()
        val turn = TurnId(7)
        assertEquals(SendGuard.Claim.GRANTED, guard.claim(turn))
        assertEquals(SendGuard.Claim.ALREADY_CLAIMED, guard.claim(turn))
        assertEquals(SendGuard.Claim.ALREADY_CLAIMED, guard.claim(turn))
    }

    @Test
    fun `NONE and non-positive ids are invalid`() {
        val guard = SendGuard()
        assertEquals(SendGuard.Claim.INVALID, guard.claim(TurnId.NONE))
        assertEquals(SendGuard.Claim.INVALID, guard.claim(TurnId(-3)))
    }

    @Test
    fun `an older turn can never be claimed after a newer one`() {
        val guard = SendGuard()
        assertEquals(SendGuard.Claim.GRANTED, guard.claim(TurnId(10)))
        // A late retry path resurfacing turn 9 must not be able to send.
        assertEquals(SendGuard.Claim.ALREADY_CLAIMED, guard.claim(TurnId(9)))
        assertEquals(SendGuard.Claim.ALREADY_CLAIMED, guard.claim(TurnId(1)))
    }

    @Test
    fun `guard survives many turns without unbounded state`() {
        val guard = SendGuard()
        val gen = TurnIdGenerator()
        repeat(100_000) {
            assertEquals(SendGuard.Claim.GRANTED, guard.claim(gen.next()))
        }
        assertEquals(100_000, guard.grantedCount())
        assertEquals(TurnId(100_000), guard.highWaterMark())
        // And the very first turn is still, correctly, unclaimable.
        assertEquals(SendGuard.Claim.ALREADY_CLAIMED, guard.claim(TurnId(1)))
    }

    @Test
    fun `concurrent claims for one turn grant exactly once`() {
        repeat(200) {
            val guard = SendGuard()
            val turn = TurnId(42)
            val threads = 16
            val granted = java.util.concurrent.atomic.AtomicInteger(0)
            val barrier = java.util.concurrent.CyclicBarrier(threads)

            val workers = List(threads) {
                Thread {
                    barrier.await()
                    if (guard.claim(turn) == SendGuard.Claim.GRANTED) granted.incrementAndGet()
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }

            assertEquals(1, granted.get(), "exactly one thread may click Send")
        }
    }

    @Test
    fun `isClaimed does not mutate the guard`() {
        val guard = SendGuard()
        assertFalse(guard.isClaimed(TurnId(5)))
        assertEquals(SendGuard.Claim.GRANTED, guard.claim(TurnId(5)))
        assertTrue(guard.isClaimed(TurnId(5)))
        assertTrue(guard.isClaimed(TurnId(4)), "older ids are also spent")
        assertFalse(guard.isClaimed(TurnId(6)))
        assertFalse(guard.isClaimed(TurnId.NONE))
        assertEquals(1, guard.grantedCount())
    }
}
