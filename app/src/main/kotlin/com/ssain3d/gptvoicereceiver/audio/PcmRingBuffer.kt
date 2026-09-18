package com.ssain3d.gptvoicereceiver.audio

/**
 * Fixed-capacity circular buffer of 16-bit PCM samples (ARCHITECTURE.md §4.2).
 *
 * Purpose in the Spike: hold the pre-roll so that, in Mode P, the audio from *before*
 * the wake instant can be handed to the recognizer and the first syllable of the
 * question is not lost (R-05).
 *
 * SECURITY_PRIVACY.md P-2 / §4: memory only. There is no code path that writes these
 * samples to a file, and the Spike deliberately does not provide one.
 *
 * Single writer (the audio thread), occasional reader (the main thread at wake time);
 * both guarded by the monitor, which is cheap relative to a 32 ms frame.
 */
class PcmRingBuffer(val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val buffer = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var filled = 0

    @Synchronized
    fun write(source: ShortArray, count: Int = source.size) {
        var offset = 0
        var remaining = count.coerceAtMost(source.size)

        // A burst larger than the buffer can only leave its tail behind.
        if (remaining > capacitySamples) {
            offset = remaining - capacitySamples
            remaining = capacitySamples
        }

        while (remaining > 0) {
            val chunk = minOf(remaining, capacitySamples - writeIndex)
            System.arraycopy(source, offset, buffer, writeIndex, chunk)
            writeIndex = (writeIndex + chunk) % capacitySamples
            offset += chunk
            remaining -= chunk
            filled = minOf(capacitySamples, filled + chunk)
        }
    }

    /** @return up to [samples] most recent samples, oldest first. */
    @Synchronized
    fun readMostRecent(samples: Int): ShortArray {
        val n = samples.coerceAtMost(filled)
        if (n <= 0) return ShortArray(0)
        val out = ShortArray(n)
        val start = ((writeIndex - n) % capacitySamples + capacitySamples) % capacitySamples
        val firstChunk = minOf(n, capacitySamples - start)
        System.arraycopy(buffer, start, out, 0, firstChunk)
        if (firstChunk < n) {
            System.arraycopy(buffer, 0, out, firstChunk, n - firstChunk)
        }
        return out
    }

    @Synchronized
    fun availableSamples(): Int = filled

    @Synchronized
    fun clear() {
        writeIndex = 0
        filled = 0
        buffer.fill(0)
    }
}
