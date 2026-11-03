package com.codebasemaker.habittracker.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.TrackerDef
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.CellDisplay
import com.codebasemaker.habittracker.domain.CellSource
import com.codebasemaker.habittracker.domain.DayGridColumn
import com.codebasemaker.habittracker.domain.InstanceStatus
import com.codebasemaker.habittracker.domain.MonthGrid
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.WeekCalendar
import com.codebasemaker.habittracker.domain.toRange
import com.codebasemaker.habittracker.ui.Fmt
import com.codebasemaker.habittracker.ui.Values
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth

/**
 * The whole Month tab (Daily, Weekly & Monthly, Values, Summary) on one US Letter landscape page.
 * Everything comes from the same [MonthGrid] and [Values] helpers as the screen, so the two agree.
 */
object MonthPdf {
    /** US Letter landscape, in PDF points (1/72 in). */
    const val PAGE_W = 792
    const val PAGE_H = 612

    fun fileName(month: YearMonth) = "habit-tracker-$month.pdf"

    fun write(out: OutputStream, snap: AppSnapshot, grid: MonthGrid, today: LocalDate) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            MonthPage(snap, grid, today).draw(page.canvas)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}

/** Draws the page in PDF points; scale the canvas first to draw it at another size (e.g. a preview bitmap). */
class MonthPage(private val snap: AppSnapshot, private val grid: MonthGrid, private val today: LocalDate) {
    private companion object {
        const val MARGIN = 24f
        const val HEADER_H = 28f
        const val FOOTER_H = 12f
        const val RIGHT_W = 236f
        const val GAP = 14f
        const val COL_HEADER_H = 72f
        const val DATE_W = 30f
        const val SECTION_GAP = 6f
        const val MIN_TASK_W = 10f

        const val INK = 0xFF1A1A1A.toInt()
        const val MUTED = 0xFF666666.toInt()
        const val LINE = 0xFFBDBDBD.toInt()
        const val WEEK_LINE = 0xFF555555.toInt()
        const val NA_FILL = 0xFFEEEEEE.toInt()
        const val TODAY_FILL = 0xFFE3EEFB.toInt()
        const val CARRY_FILL = 0xFFF4F1E8.toInt()
        const val DONE = 0xFF1F6F5C.toInt()
        const val SKIP = 0xFF6B7280.toInt()
        const val MISSED = 0xFFB3261E.toInt()
        const val OVERRIDE = 0xFFE8A33D.toInt()
        const val DERIVED_ALPHA = 115 // 45 %
    }

    private val range = grid.month.toRange()
    private val weeks = WeekCalendar(snap.scoring.weekStart)
    private val trackers = snap.trackers.sortedBy { it.sortOrder }.filter { t -> range.days.any { t.isActiveOn(it) } }
    private val xpwColumns = grid.dayColumns.filter { it.weekResults.isNotEmpty() }

    // Linear text so widths measured at one size scale exactly to another.
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { color = INK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** False after [draw] if the day table or the right panel ran past its area. */
    var fits = true
        private set

    fun draw(c: Canvas) {
        fill.color = 0xFFFFFFFF.toInt()
        c.drawRect(0f, 0f, MonthPdf.PAGE_W.toFloat(), MonthPdf.PAGE_H.toFloat(), fill)
        drawHeader(c)
        drawDayTable(c, MARGIN, MARGIN + HEADER_H, MonthPdf.PAGE_W - MARGIN - RIGHT_W - GAP, MonthPdf.PAGE_H - MARGIN - FOOTER_H)
        drawRightPanel(c, MonthPdf.PAGE_W - MARGIN - RIGHT_W, MARGIN + HEADER_H, MonthPdf.PAGE_H - MARGIN - FOOTER_H)
        drawFooter(c, MonthPdf.PAGE_H - MARGIN)
    }

    // ---- header & footer ------------------------------------------------------------------

    private fun drawHeader(c: Canvas) {
        font(15f, bold = true)
        c.drawText("Habit Tracker — ${Fmt.month(grid.month)}", MARGIN, MARGIN + 13f, text)
        val t = grid.score.toDate
        val m = grid.score.month
        val right = MonthPdf.PAGE_W - MARGIN
        font(8.5f, bold = true)
        drawRight(c, "To date  ${Fmt.pts(t.earned)} / ${Fmt.pts(t.possible)}  ·  ${Fmt.pct(t.percent)}", right, MARGIN + 7f)
        font(8.5f, color = MUTED)
        drawRight(c, "Month  ${Fmt.pts(m.earned)} / ${Fmt.pts(m.possible)}  ·  ${Fmt.pct(m.percent)}", right, MARGIN + 18f)
    }

    private fun drawFooter(c: Canvas, baseline: Float) {
        var x = MARGIN
        val s = 6f
        val cy = baseline - 2.5f
        font(6.5f, color = MUTED)
        for ((d, label) in listOf(CellDisplay.DONE to "done", CellDisplay.SKIP to "skipped / not needed", CellDisplay.MISSED to "missed")) {
            glyph(c, d, false, x + s / 2, cy, s)
            c.drawText(label, x + s + 3f, baseline, text)
            x += s + 3f + text.measureText(label) + 10f
        }
        val rest = "faded = automatic   orange corner = changed by hand   grey = not scheduled"
        c.drawText(rest, x, baseline, text)
        drawRight(c, "Generated ${Fmt.long(today)}", MonthPdf.PAGE_W - MARGIN, baseline)
    }

    // ---- day table: habits + values, one row per day -------------------------------------

    private sealed interface Column {
        val width: Float
    }
    private class TaskColumn(val col: DayGridColumn, override var width: Float) : Column
    private class ValueColumn(val tracker: TrackerDef, override var width: Float) : Column

    private fun drawDayTable(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val rowH = minOf(16f, (bottom - top - COL_HEADER_H) / grid.days.size)
        var valueFont = 6.5f

        // Natural widths: square habit cells; value columns as wide as their widest entry.
        font(valueFont)
        val taskCols = grid.dayColumns.map { TaskColumn(it, rowH) }
        val valueCols = trackers.map { t ->
            val widest = grid.days.maxOf { d -> if (t.isActiveOn(d)) text.measureText(Values.display(snap, t, d)) else 0f }
            ValueColumn(t, (widest + 5f).coerceIn(rowH, 90f))
        }
        val gap = if (taskCols.isNotEmpty() && valueCols.isNotEmpty()) SECTION_GAP else 0f
        val avail = right - left - DATE_W - gap
        val valuesW = valueCols.sumOf { it.width.toDouble() }.toFloat()
        if (taskCols.size * rowH + valuesW > avail) {
            // Too wide: narrow the habit cells first (marks stay legible), then shrink everything.
            val taskW = ((avail - valuesW) / taskCols.size).coerceIn(MIN_TASK_W, rowH)
            taskCols.forEach { it.width = taskW }
            val total = taskCols.size * taskW + valuesW
            if (total > avail) {
                val k = avail / total
                taskCols.forEach { it.width *= k }
                valueCols.forEach { it.width *= k }
                valueFont = maxOf(3.5f, valueFont * k)
            }
        }
        val columns: List<Pair<Float, Column>> = buildList {
            var x = left + DATE_W
            taskCols.forEach { add(x to it); x += it.width }
            x += gap
            valueCols.forEach { add(x to it); x += it.width }
        }
        val gridTop = top + COL_HEADER_H

        if (columns.isEmpty()) {
            font(8f, color = MUTED)
            c.drawText("No tasks or trackers this month.", left, gridTop + 12f, text)
            return
        }

        // Section labels and rotated column headers.
        font(6.5f, bold = true, color = MUTED)
        c.drawText("Habits", left, top + 7f, text)
        valueCols.firstOrNull()?.let { first -> c.drawText("Values", columns.first { it.second === first }.first, top + 7f, text) }
        for ((x, col) in columns) {
            val (name, sub) = when (col) {
                is TaskColumn -> col.col.header.name to taskSub(col.col)
                is ValueColumn -> col.tracker.latest.name to Values.unitLabel(snap, col.tracker.latest)
            }
            rotated(c, name + (sub?.let { "  $it" } ?: ""), x, col.width, gridTop - 2f, COL_HEADER_H - 12f, minOf(6.5f, col.width * 0.85f))
        }

        // Rows.
        for ((i, d) in grid.days.withIndex()) {
            val y = gridTop + i * rowH
            if (d == today) {
                fill.color = TODAY_FILL
                c.drawRect(left, y, columns.last().let { it.first + it.second.width }, y + rowH, fill)
            }
            font(minOf(7f, rowH * 0.55f), bold = true)
            c.drawText("${d.dayOfMonth}", left + 2f, y + rowH * 0.7f, text)
            font(minOf(6f, rowH * 0.45f), color = MUTED)
            c.drawText(Fmt.dow2(d), left + 15f, y + rowH * 0.7f, text)

            for ((x, col) in columns) {
                val r = RectF(x, y, x + col.width, y + rowH)
                when (col) {
                    is TaskColumn -> markCell(c, r, col.col.cells[d])
                    is ValueColumn -> {
                        val active = col.tracker.isActiveOn(d)
                        if (!active) { fill.color = NA_FILL; c.drawRect(r, fill) }
                        cellBorder(c, r)
                        if (active) {
                            font(valueFont)
                            centered(c, fit(Values.display(snap, col.tracker, d), r.width() - 2f), r)
                        }
                    }
                }
            }
        }
        // Week separators across the whole table.
        stroke.color = WEEK_LINE
        stroke.strokeWidth = 1.1f
        val tableRight = columns.last().let { it.first + it.second.width }
        if (tableRight > right + 0.5f || gridTop + grid.days.size * rowH > bottom + 0.5f) fits = false
        for ((i, d) in grid.days.withIndex()) {
            if (weeks.weekContaining(d).endInclusive == d && d != grid.days.last()) {
                val y = gridTop + (i + 1) * rowH
                c.drawLine(left, y, tableRight, y, stroke)
            }
        }
    }

    private fun taskSub(col: DayGridColumn): String? = col.header.thresholdLabel ?: when (col.header.type) {
        ScheduleType.X_PER_WEEK -> col.weekResults.firstOrNull()?.goal?.let { "$it×/wk" }
        ScheduleType.EVERY_N -> snap.taskVersions[col.header.taskId]
            ?.lastOrNull { !LocalDate.parse(it.effectiveFrom).isAfter(range.endInclusive) }
            ?.intervalDays?.let { "every ${it}d" }
        else -> null
    }

    // ---- right panel: weekly, monthly, summary --------------------------------------------

    private fun drawRightPanel(c: Canvas, left: Float, top: Float, bottom: Float) {
        // Measure at scale 1, then shrink everything if it would run off the page.
        val perTask = grid.score.perTask.filter { it.month.possible > 0 }
        val weeklyLines = 1 + maxOf(1, grid.weeklyRows.size + xpwColumns.size) + if (grid.weekColumns.lastOrNull()?.scoredInMonth == false) 1 else 0
        val monthlyLines = maxOf(1, grid.monthlyRows.size)
        val summaryLines = 1 + perTask.size + 1 + 1 + maxOf(1, trackers.size)
        val titles = 3
        val spacing = 5f + 5f + 3f // after Weekly, after Monthly, after the habit total
        val needed = (weeklyLines + monthlyLines + summaryLines) * 11f + titles * 16f + spacing + 4f
        val s = minOf(1f, (bottom - top) / needed)
        val line = 11f * s
        var y = top

        fun title(t: String) {
            font(9f * s, bold = true)
            c.drawText(t, left, y + 9f * s, text)
            stroke.color = LINE; stroke.strokeWidth = 0.5f
            c.drawLine(left, y + 12f * s, left + RIGHT_W, y + 12f * s, stroke)
            y += 16f * s
        }

        // Weekly: weekly tasks plus X-per-week results, one column per week due date.
        title("Weekly & X per week")
        val wcW = 26f
        val nameW = RIGHT_W - grid.weekColumns.size * wcW
        if (grid.weeklyRows.isEmpty() && xpwColumns.isEmpty()) {
            font(7f * s, color = MUTED); c.drawText("No weekly tasks this month.", left, y + 8f * s, text); y += line
        } else {
            font(6.5f * s, bold = true, color = MUTED)
            c.drawText("Due", left, y + 8f * s, text)
            grid.weekColumns.forEachIndexed { i, wc ->
                font(6.5f * s, bold = true, color = if (wc.scoredInMonth) MUTED else OVERRIDE)
                centered(c, "${wc.due.monthValue}/${wc.due.dayOfMonth}" + if (wc.scoredInMonth) "" else "*",
                    RectF(left + nameW + i * wcW, y, left + nameW + (i + 1) * wcW, y + line))
            }
            y += line
            for (row in grid.weeklyRows) {
                label(c, row.header.name, "${row.header.points} pts", left, y, nameW, line, s)
                grid.weekColumns.forEachIndexed { i, wc ->
                    val r = RectF(left + nameW + i * wcW, y, left + nameW + (i + 1) * wcW, y + line)
                    if (!wc.scoredInMonth) { fill.color = CARRY_FILL; c.drawRect(r, fill) }
                    val cell = row.cells[wc.due]
                    val sq = minOf(r.height(), r.width())
                    markCell(c, RectF(r.centerX() - sq / 2, r.top, r.centerX() + sq / 2, r.bottom), cell)
                }
                y += line
            }
            for (col in xpwColumns) {
                label(c, col.header.name, "${col.weekResults.first().goal}×/wk · ${col.header.points} pts", left, y, nameW, line, s)
                grid.weekColumns.forEachIndexed { i, wc ->
                    val r = RectF(left + nameW + i * wcW, y, left + nameW + (i + 1) * wcW, y + line)
                    if (!wc.scoredInMonth) { fill.color = CARRY_FILL; c.drawRect(r, fill) }
                    cellBorder(c, r)
                    col.weekResults.firstOrNull { it.due == wc.due }?.let { inst ->
                        font(6.5f * s, bold = true, color = when (inst.status) {
                            InstanceStatus.EARNED, InstanceStatus.SKIPPED -> DONE
                            InstanceStatus.PARTIAL -> OVERRIDE
                            InstanceStatus.MISSED -> MISSED
                            else -> MUTED
                        })
                        centered(c, "${inst.done}/${inst.goal}" + if (inst.overridden) "*" else "", r)
                    }
                }
                y += line
            }
            grid.weekColumns.lastOrNull()?.takeIf { !it.scoredInMonth }?.let { wc ->
                font(6f * s, color = MUTED)
                val month = wc.due.month.name.lowercase().replaceFirstChar(Char::uppercase)
                c.drawText("* week ending ${Fmt.short(wc.due)} is scored in $month", left, y + 7.5f * s, text)
                y += line
            }
        }
        y += 5f * s

        title("Monthly — due ${Fmt.short(range.endInclusive)}")
        if (grid.monthlyRows.isEmpty()) {
            font(7f * s, color = MUTED); c.drawText("No monthly tasks this month.", left, y + 8f * s, text); y += line
        }
        for (row in grid.monthlyRows) {
            label(c, row.header.name, "${row.header.points} pts", left, y, RIGHT_W - wcW, line, s)
            markCell(c, RectF(left + RIGHT_W - wcW / 2 - line / 2, y, left + RIGHT_W - wcW / 2 + line / 2, y + line), row.cells[range.endInclusive])
            y += line
        }
        y += 5f * s

        title("Summary")
        font(6.5f * s, bold = true, color = MUTED)
        c.drawText("Habits", left, y + 8f * s, text)
        drawRight(c, "to date", left + RIGHT_W - 34f, y + 8f * s)
        drawRight(c, "%", left + RIGHT_W, y + 8f * s)
        y += line
        for (ts in perTask) {
            val t = snap.tasks.firstOrNull { it.id == ts.taskId } ?: continue
            val v = t.versionOn(range.endInclusive) ?: t.sortedVersions.last()
            font(7f * s)
            c.drawText(fit(v.name, RIGHT_W - 110f), left, y + 8f * s, text)
            font(7f * s, color = MUTED)
            drawRight(c, "${Fmt.pts(ts.toDate.earned)} / ${Fmt.pts(ts.toDate.possible)}  (month ${Fmt.pts(ts.month.possible)})", left + RIGHT_W - 34f, y + 8f * s)
            font(7f * s)
            drawRight(c, Fmt.pct(ts.toDate.percent), left + RIGHT_W, y + 8f * s)
            y += line
        }
        val total = grid.score.toDate
        font(7f * s, bold = true)
        c.drawText("Total", left, y + 8f * s, text)
        drawRight(c, "${Fmt.pts(total.earned)} / ${Fmt.pts(total.possible)}", left + RIGHT_W - 34f, y + 8f * s)
        drawRight(c, Fmt.pct(total.percent), left + RIGHT_W, y + 8f * s)
        y += line + 3f * s

        font(6.5f * s, bold = true, color = MUTED)
        c.drawText("Values", left, y + 8f * s, text)
        y += line
        if (trackers.isEmpty()) { font(7f * s, color = MUTED); c.drawText("No trackers this month.", left, y + 8f * s, text) }
        for (t in trackers) {
            font(7f * s)
            val name = fit(t.latest.name, 78f)
            c.drawText(name, left, y + 8f * s, text)
            font(6.5f * s, color = MUTED)
            drawRight(c, fit(Values.monthSummary(snap, t, range), RIGHT_W - 82f), left + RIGHT_W, y + 8f * s)
            y += line
        }
        if (y > bottom + 0.5f) fits = false
    }

    private fun label(c: Canvas, name: String, sub: String, x: Float, y: Float, w: Float, h: Float, s: Float) {
        font(6f * s, color = MUTED)
        val subW = text.measureText(sub)
        drawRight(c, sub, x + w - 4f, y + h * 0.72f)
        font(7f * s)
        c.drawText(fit(name, w - subW - 10f), x, y + h * 0.72f, text)
    }

    // ---- primitives -----------------------------------------------------------------------

    private fun markCell(c: Canvas, r: RectF, cell: Cell?) {
        if (cell == null) { fill.color = NA_FILL; c.drawRect(r, fill) }
        cellBorder(c, r)
        if (cell == null) return
        val size = minOf(r.width(), r.height())
        glyph(c, cell.display, cell.source == CellSource.DERIVED, r.centerX(), r.centerY(), size * 0.42f)
        if (cell.source == CellSource.OVERRIDE) {
            val t = size * 0.3f
            fill.color = OVERRIDE
            c.drawPath(Path().apply { moveTo(r.right - t, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + t); close() }, fill)
        }
    }

    private fun glyph(c: Canvas, d: CellDisplay, derived: Boolean, cx: Float, cy: Float, s: Float) {
        val alpha = if (derived) DERIVED_ALPHA else 255
        when (d) {
            CellDisplay.DONE -> { fill.color = DONE; fill.alpha = alpha; c.drawCircle(cx, cy, s / 2, fill) }
            CellDisplay.SKIP -> { fill.color = SKIP; fill.alpha = alpha; c.drawRect(cx - s / 2, cy - s * 0.1f, cx + s / 2, cy + s * 0.1f, fill) }
            CellDisplay.MISSED -> {
                stroke.color = MISSED; stroke.alpha = alpha; stroke.strokeWidth = s * 0.18f
                c.drawLine(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, stroke)
                c.drawLine(cx - s / 2, cy + s / 2, cx + s / 2, cy - s / 2, stroke)
            }
            CellDisplay.EMPTY -> {}
        }
        fill.alpha = 255; stroke.alpha = 255
    }

    private fun cellBorder(c: Canvas, r: RectF) {
        stroke.color = LINE; stroke.strokeWidth = 0.35f
        c.drawRect(r, stroke)
    }

    private fun font(size: Float, bold: Boolean = false, color: Int = INK) {
        text.textSize = size
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.color = color
    }

    private fun drawRight(c: Canvas, s: String, right: Float, baseline: Float) =
        c.drawText(s, right - text.measureText(s), baseline, text)

    private fun centered(c: Canvas, s: String, r: RectF) {
        val fm = text.fontMetrics
        c.drawText(s, r.centerX() - text.measureText(s) / 2, r.centerY() - (fm.ascent + fm.descent) / 2, text)
    }

    /** Text rotated to read bottom-up inside a column header of [width] × [height] ending at [bottom]. */
    private fun rotated(c: Canvas, s: String, x: Float, width: Float, bottom: Float, height: Float, size: Float) {
        font(size)
        val fm = text.fontMetrics
        c.save()
        c.translate(x + width / 2 - (fm.ascent + fm.descent) / 2, bottom)
        c.rotate(-90f)
        c.drawText(fit(s, height), 0f, 0f, text)
        c.restore()
    }

    /** [s] shortened with an ellipsis to fit [maxW] at the current font. */
    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 0 && text.measureText(s.take(n) + "…") > maxW) n--
        return if (n == 0) "" else s.take(n).trimEnd() + "…"
    }
}
