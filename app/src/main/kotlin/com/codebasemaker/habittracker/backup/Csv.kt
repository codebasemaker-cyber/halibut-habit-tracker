package com.codebasemaker.habittracker.backup

/**
 * RFC 4180 CSV with one extension: an unquoted `\N` field is SQL NULL, so null and the empty
 * string ("") survive a round trip.
 */
object Csv {
    const val NULL = "\\N"

    fun writeRow(fields: List<String?>): String = fields.joinToString(",") { f ->
        when {
            f == null -> NULL
            f.isEmpty() || f.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || f == NULL ->
                "\"" + f.replace("\"", "\"\"") + "\""
            else -> f
        }
    } + "\r\n"

    fun parse(text: String): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        var row = mutableListOf<String?>()
        val field = StringBuilder()
        var quoted = false
        var inQuotes = false
        var i = 0
        var fieldStarted = false

        fun endField() {
            val v = field.toString()
            row.add(if (!quoted && v == NULL) null else v)
            field.clear(); quoted = false; fieldStarted = false
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> { inQuotes = true; quoted = true; fieldStarted = true }
                ',' -> endField()
                '\r' -> {}
                '\n' -> { endField(); rows.add(row); row = mutableListOf() }
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        if (fieldStarted || row.isNotEmpty()) { endField(); rows.add(row) }
        return rows
    }
}
