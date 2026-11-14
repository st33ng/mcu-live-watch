package dev.st33ng.mculivewatch.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the helper that replaced the hand-rolled process handling.
 *
 * The original code read `process.inputStream` before calling `waitFor(timeout)`, so the timeout
 * never applied. That is why the settings dialog sat on "detecting..." instead of failing after a
 * few seconds.
 */
class ProcessRunnerTest {

    @Test
    fun `captures stdout and exit code`() {
        val result = ProcessRunner.run(listOf("/bin/sh", "-c", "echo hello; exit 3"), 10_000)

        assertFalse(result.timedOut)
        assertEquals(3, result.exitCode)
        assertTrue(result.stdout.contains("hello"))
    }

    @Test
    fun `kills a child that never exits`() {
        val started = System.currentTimeMillis()
        val result = ProcessRunner.run(listOf("/bin/sh", "-c", "sleep 60"), 700)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("expected a timeout, got ${result.exitCode}", result.timedOut)
        // Must return close to the deadline, not after the child finishes.
        assertTrue("took ${elapsed}ms, timeout was 700ms", elapsed < 8_000)
    }

    @Test
    fun `drains output larger than a pipe buffer without deadlocking`() {
        // ~400 KB, comparable to the SEGGER device list. If stdout is not drained concurrently the
        // child blocks on write and this test hangs instead of failing.
        val result = ProcessRunner.run(
            listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 20000 ]; do echo 'ST_1234567890123456789'; i=\$((i+1)); done"),
            30_000,
        )

        assertFalse("timed out — output was not drained concurrently", result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals(20_000, result.stdout.lineSequence().count { it.isNotBlank() })
    }

    @Test
    fun `missing executable is reported instead of thrown`() {
        val result = ProcessRunner.run(listOf("/definitely/not/here/binary"), 2_000)

        assertFalse(result.ok)
        assertEquals(-1, result.exitCode)
    }
}
