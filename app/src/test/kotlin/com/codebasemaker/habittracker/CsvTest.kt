package com.codebasemaker.habittracker

import com.codebasemaker.habittracker.backup.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun `null, empty, quotes, commas and newlines round-trip`() {
        val rows = listOf(
            listOf("a", null, "", "x,y", "say \"hi\"", "line1\nline2", "\\N", "  spaced "),
            listOf(null, null),
            listOf(""),
        )
        val text = rows.joinToString("") { Csv.writeRow(it) }
        assertEquals(rows, Csv.parse(text))
    }
}
