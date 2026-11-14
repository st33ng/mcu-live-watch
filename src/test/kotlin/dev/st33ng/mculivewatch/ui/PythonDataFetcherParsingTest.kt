package dev.st33ng.mculivewatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Verifies that both output formats of the ELF parser are understood.
 *
 * The legacy format is what the current `main.py` prints, so a regression here would silently
 * empty the symbol table.
 */
class PythonDataFetcherParsingTest {

    private val fetcher = PythonDataFetcher("python3", "/dev/null")

    @Test
    fun `parses the legacy prose line produced by main py`() {
        val line = "htim15 - Адрес: 0x20000b0c - Размер: 76 байт - Тип данных: TIM_TypeDef"

        val symbol = fetcher.parseLineForTest(line)

        assertNotNull(symbol)
        assertEquals("htim15", symbol!!.name)
        assertEquals(0x20000b0cL, symbol.address)
        assertEquals(76L, symbol.size)
        assertEquals("TIM_TypeDef", symbol.type)
    }

    @Test
    fun `parses a json line`() {
        val line = """{"name":"counter","addr":"0x20000004","size":4,"type":"uint32_t"}"""

        val symbol = fetcher.parseLineForTest(line)

        assertNotNull(symbol)
        assertEquals("counter", symbol!!.name)
        assertEquals(0x20000004L, symbol.address)
        assertEquals(4L, symbol.size)
        assertEquals("uint32_t", symbol.type)
    }

    @Test
    fun `ignores separator and unrelated lines`() {
        assertNull(fetcher.parseLineForTest("---- symbols ----"))
        assertNull(fetcher.parseLineForTest(""))
        assertNull(fetcher.parseLineForTest("Ошибка: file not found"))
    }

    @Test
    fun `parses real script output when python and script are available`() {
        val script = File("/Users/timtim/Work/PythonProjects/ElfParser/main.py")
        val elf = File("/Users/timtim/Documents/deepseek-harness/default-workspace/ldtest")
        if (!script.isFile) return

        // Only an integration smoke check: skipped when the local fixtures are absent.
        val elfFile = elf.walkTopDown().firstOrNull {
            it.isFile && it.extension.equals("elf", ignoreCase = true)
        } ?: return

        val python = "/Users/timtim/.local/pipx/venvs/pyelftools/bin/python3"
        if (!File(python).canExecute()) return

        val symbols = PythonDataFetcher(python, script.absolutePath)
            .fetchData("-p", elfFile.absolutePath, "-t")

        assertEquals(true, symbols.isNotEmpty())
    }
}
