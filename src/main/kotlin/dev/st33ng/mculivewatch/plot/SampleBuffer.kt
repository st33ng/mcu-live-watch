package dev.st33ng.mculivewatch.plot

import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-capacity ring buffer of numeric samples for one variable.
 *
 * The previous implementation kept every sample forever in a `MutableList<Pair<Long, CppValue>>`
 * and copied the whole list on each repaint. At 1 kHz that is ~70 KiB/s per variable, so a single
 * variable leaked hundreds of MiB per hour. This buffer has a hard capacity, stores primitives
 * (no boxing), and supports incremental draining by a monotonically increasing sequence number.
 */
class SampleBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    private val timestampsMs = LongArray(capacity)
    private val values = DoubleArray(capacity)

    /** Total number of samples ever written. Monotonic, never reset. */
    @Volatile
    var totalWritten: Long = 0L
        private set

    private var head = 0

    /** Drops every retained sample but keeps the sequence increasing, so readers stay consistent. */
    fun clear() {
        synchronized(this) {
            head = 0
            totalWritten = 0L
        }
    }

    fun add(timestampMs: Long, value: Double) {
        synchronized(this) {
            timestampsMs[head] = timestampMs
            values[head] = value
            head = (head + 1) % capacity
            totalWritten++
        }
    }

    /**
     * Copies samples written after [fromSequence] into [out], oldest first.
     *
     * Returns the new cursor to pass on the next call. If the reader fell behind by more than
     * [capacity], only the retained window is returned and the cursor jumps to the oldest retained
     * sample — the chart degrades to a lower effective rate instead of growing without bound.
     */
    fun drain(fromSequence: Long, out: MutableList<Sample>): Long {
        synchronized(this) {
            val written = totalWritten
            if (written <= fromSequence) return fromSequence

            val oldestRetained = (written - capacity).coerceAtLeast(0L)
            val start = fromSequence.coerceAtLeast(oldestRetained)
            val count = written - start
            if (count <= 0) return written

            val available = count.coerceAtMost(capacity.toLong()).toInt()
            val skip = (count - available).toInt()
            val firstIndex = ((head - available + capacity) % capacity)
            for (i in 0 until available) {
                val idx = (firstIndex + i) % capacity
                out.add(Sample(timestampsMs[idx], values[idx]))
            }
            return written - skip
        }
    }

    fun snapshot(): List<Sample> {
        val result = ArrayList<Sample>(capacity.coerceAtMost(MAX_SNAPSHOT))
        drain(0L, result)
        return result
    }

    data class Sample(val timestampMs: Long, val value: Double)

    companion object {
        /** 60 s at 1 kHz keeps short history without unbounded growth. */
        const val DEFAULT_CAPACITY = 60_000
        private const val MAX_SNAPSHOT = 8192
    }
}

/**
 * Registry of per-variable sample buffers.
 *
 * Replaces the old global `LiveExpressionTable.valueHistory` map that nothing ever trimmed.
 */
object SampleStore {
    private val buffers = ConcurrentHashMap<String, SampleBuffer>()

    fun bufferFor(variable: String): SampleBuffer =
        buffers.getOrPut(variable) { SampleBuffer() }

    fun remove(variable: String) {
        buffers.remove(variable)
    }

    fun clear() {
        buffers.clear()
    }

    fun variables(): Set<String> = buffers.keys.toSet()
}
