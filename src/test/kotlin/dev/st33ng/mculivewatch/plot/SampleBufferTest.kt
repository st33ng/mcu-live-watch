package dev.st33ng.mculivewatch.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the retention and drain semantics that the live chart depends on.
 *
 * These are the properties that were missing before and caused unbounded memory growth:
 * the buffer must never exceed its capacity, and a reader must never see more than one window.
 */
class SampleBufferTest {

    @Test
    fun `buffer never retains more than its capacity`() {
        val buffer = SampleBuffer(capacity = 100)

        repeat(1_000) { i ->
            buffer.add(timestampMs = i.toLong(), value = i.toDouble())
        }

        val samples = buffer.snapshot()
        assertEquals(100, samples.size)
        assertEquals(900.0, samples.first().value, 0.0)
        assertEquals(999.0, samples.last().value, 0.0)
    }

    @Test
    fun `drain returns only new samples and advances the cursor`() {
        val buffer = SampleBuffer(capacity = 100)
        val collected = ArrayList<SampleBuffer.Sample>()

        buffer.add(1_000, 1.0)
        buffer.add(2_000, 2.0)
        var cursor = buffer.drain(0L, collected)

        assertEquals(2, collected.size)
        assertEquals(2L, cursor)

        collected.clear()
        buffer.add(3_000, 3.0)
        cursor = buffer.drain(cursor, collected)

        assertEquals(1, collected.size)
        assertEquals(3.0, collected.single().value, 0.0)
        assertEquals(3L, cursor)
    }

    @Test
    fun `drain skips samples lost to overwrite instead of growing`() {
        val buffer = SampleBuffer(capacity = 10)
        val collected = ArrayList<SampleBuffer.Sample>()

        repeat(100) { i -> buffer.add(i.toLong(), i.toDouble()) }

        // First read after a long pause: only the retained window is returned.
        buffer.drain(0L, collected)
        assertEquals(10, collected.size)
        assertEquals(90.0, collected.first().value, 0.0)

        // Reading again with the advanced cursor yields nothing.
        collected.clear()
        val cursor = buffer.drain(100L, collected)
        assertTrue(collected.isEmpty())
        assertEquals(100L, cursor)
    }

    @Test
    fun `drain on empty buffer is a no-op`() {
        val buffer = SampleBuffer(capacity = 4)
        val collected = ArrayList<SampleBuffer.Sample>()

        val cursor = buffer.drain(0L, collected)

        assertTrue(collected.isEmpty())
        assertEquals(0L, cursor)
    }
}
