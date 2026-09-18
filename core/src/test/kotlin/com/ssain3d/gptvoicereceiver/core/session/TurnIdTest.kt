package com.ssain3d.gptvoicereceiver.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TurnIdTest {

    @Test
    fun `generator issues strictly increasing ids`() {
        val gen = TurnIdGenerator()
        val ids = List(100) { gen.next() }
        ids.zipWithNext().forEach { (a, b) ->
            assertTrue(b > a, "$b should follow $a")
        }
    }

    @Test
    fun `generator never issues NONE`() {
        val gen = TurnIdGenerator()
        repeat(10) { assertNotEquals(TurnId.NONE, gen.next()) }
    }

    @Test
    fun `current reports the last issued id`() {
        val gen = TurnIdGenerator()
        assertEquals(TurnId.NONE, gen.current())
        val first = gen.next()
        assertEquals(first, gen.current())
        val second = gen.next()
        assertEquals(second, gen.current())
    }

    @Test
    fun `ids are unique under concurrent issue`() {
        val gen = TurnIdGenerator()
        val perThread = 500
        val threads = 8
        val results = java.util.Collections.synchronizedList(mutableListOf<TurnId>())
        val workers = List(threads) {
            Thread { repeat(perThread) { results.add(gen.next()) } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(threads * perThread, results.size)
        assertEquals(threads * perThread, results.toSet().size, "turn ids must never repeat")
    }

    // ---- TurnGate: INV-3 ----

    @Test
    fun `gate accepts only the current turn`() {
        val gen = TurnIdGenerator()
        val gate = TurnGate()
        val turn1 = gen.next()
        val turn2 = gen.next()

        gate.open(turn1)
        assertTrue(gate.accepts(turn1))
        assertFalse(gate.accepts(turn2), "a different turn's event is stale")

        gate.open(turn2)
        assertFalse(gate.accepts(turn1), "a late event from the previous turn is stale")
        assertTrue(gate.accepts(turn2))
    }

    @Test
    fun `gate rejects everything once closed`() {
        val gen = TurnIdGenerator()
        val gate = TurnGate()
        val turn = gen.next()
        gate.open(turn)
        gate.close()
        assertFalse(gate.accepts(turn))
        assertFalse(gate.accepts(TurnId.NONE))
    }

    @Test
    fun `gate counts stale events for race diagnosis`() {
        val gen = TurnIdGenerator()
        val gate = TurnGate()
        gate.open(gen.next())
        val other = gen.next()

        repeat(3) { gate.accepts(other) }

        assertEquals(3, gate.staleCount)
        gate.resetCounters()
        assertEquals(0, gate.staleCount)
    }
}
