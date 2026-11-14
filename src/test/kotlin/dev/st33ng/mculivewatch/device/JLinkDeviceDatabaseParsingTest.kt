package dev.st33ng.mculivewatch.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device list is the source of truth for what J-Link accepts, so its parsing must be exact:
 * every entry becomes a name the user can pick, and no entry may be mangled.
 *
 * Input is the CSV written by J-Link Commander's `ExpDevList`:
 * `"ST", "STM32L100RC", "Cortex-M3", { {0x08000000, 0x00040000} }, {0x20000000, 0x00004000}`
 */
class JLinkDeviceDatabaseParsingTest {

    private fun device(raw: String, name: String): JLinkDeviceDatabase.Device =
        JLinkDeviceDatabase.parseLines(raw).single { it.name == name }

    @Test
    fun `parses manufacturer and name from quoted csv`() {
        val raw = """
            "ST", "STM32L100RC", "Cortex-M3", {0x08000000, 0x00040000}, {0x20000000, 0x00004000}
            "Atmel", "ATSAMD21G18", "Cortex-M0+", {0x00000000, 0x00040000}, {0x20000000, 0x00008000}
        """.trimIndent()

        val devices = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(2, devices.size)
        assertEquals("ST", device(raw, "STM32L100RC").manufacturer)
        assertEquals("Atmel", device(raw, "ATSAMD21G18").manufacturer)
    }

    @Test
    fun `header row is not a device`() {
        val raw = """
            "Manufacturer", "Device", "Core", {Flash areas}, {RAM areas}
            "ST", "STM32F030C8", "Cortex-M0", {0x08000000, 0x00010000}, {0x20000000, 0x00002000}
        """.trimIndent()

        val devices = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(1, devices.size)
        assertEquals("STM32F030C8", devices[0].name)
    }

    @Test
    fun `commas inside the brace sections do not split the row`() {
        // The flash/RAM areas contain commas; a naive split(',') would turn the core into a name.
        val raw = "\"ST\", \"STM32L100RC\", \"Cortex-M3\", { {0x08000000, 0x00040000}, {0x08080000, 0x00002000} }, " +
            "{0x20000000, 0x00004000}"

        val parsed = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(1, parsed.size)
        assertEquals("STM32L100RC", parsed[0].name)
        assertEquals("ST", parsed[0].manufacturer)
    }

    @Test
    fun `decorated names are kept verbatim`() {
        // SEGGER ships a second entry per part when optimized flash programming is available.
        val raw = "\"ST\", \"STM32L100RC (allow opt. bytes)\", \"Cortex-M3\", {}, {}"

        val parsed = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(1, parsed.size)
        assertEquals("STM32L100RC (allow opt. bytes)", parsed[0].name)
    }

    @Test
    fun `blank manufacturer falls back to Unspecified`() {
        val raw = "\"\", \"ARM7\", \"ARM7\", {}, {}"

        val parsed = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(1, parsed.size)
        assertEquals(JLinkDeviceDatabase.UNSPECIFIED, parsed[0].manufacturer)
    }

    @Test
    fun `malformed lines are skipped rather than crashing`() {
        val raw = """
            garbage without quotes

            "ST", "STM32L100RC", "Cortex-M3", {}, {}
            "unterminated, "broken
        """.trimIndent()

        val parsed = JLinkDeviceDatabase.parseLines(raw)

        assertEquals(1, parsed.size)
        assertEquals("STM32L100RC", parsed[0].name)
    }

    @Test
    fun `duplicate manufacturer and name pairs collapse`() {
        val raw = "\"ST\", \"STM32L100RC\", \"Cortex-M3\", {}, {}\n\"ST\", \"STM32L100RC\", \"Cortex-M3\", {}, {}"

        assertEquals(1, JLinkDeviceDatabase.parseLines(raw).size)
    }

    @Test
    fun `generic entries sort after real manufacturers`() {
        val raw = """
            "Unspecified", "ARM7", "ARM7", {}, {}
            "ST", "STM32F030C8", "Cortex-M0", {}, {}
        """.trimIndent()

        val parsed = JLinkDeviceDatabase.parseLines(raw)

        assertEquals("ST", parsed[0].manufacturer)
        assertEquals(JLinkDeviceDatabase.UNSPECIFIED, parsed[1].manufacturer)
    }

    /**
     * The bundled table is what makes the chooser open instantly and offline, so the resource must
     * be shipped and complete. This test fails if the gzipped export is not packaged with the plugin.
     */
    @Test
    fun `bundled device list is shipped and complete`() {
        val devices = JLinkDeviceDatabase.loadBundled()

        assertTrue("expected a large list, got ${devices.size}", devices.size > 10_000)
        assertTrue("STM32L100RC must exist", devices.any { it.name == "STM32L100RC" })
        assertTrue("CubeMX spelling must not exist", devices.none { it.name == "STM32L100RCTx" })
        assertTrue(devices.all { it.name.isNotBlank() })
        assertTrue("manufacturers must be resolved", devices.count { it.manufacturer == "ST" } > 1_000)

        val firstGeneric = devices.indexOfFirst { it.manufacturer == JLinkDeviceDatabase.UNSPECIFIED }
        assertTrue("generic entries must sort last", firstGeneric < 0 || firstGeneric > 10_000)
    }
}
