package com.codebasemaker.habittracker.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebasemaker.habittracker.App
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.Repository
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.CellSource
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.MonthGrid
import com.codebasemaker.habittracker.domain.MonthGridBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * Shared state for all screens. Selected month/day live in SavedStateHandle so they survive
 * fold/unfold and process death.
 */
class MainViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val app: App = App.instance
    val repo: Repository = app.repo

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    val month: StateFlow<YearMonth> = saved.getStateFlow(KEY_MONTH, YearMonth.now().toString())
        .map { YearMonth.parse(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, YearMonth.now())
    val date: StateFlow<LocalDate> = saved.getStateFlow(KEY_DATE, LocalDate.now().toString())
        .map { LocalDate.parse(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    val snapshot: StateFlow<AppSnapshot?> = repo.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val grid: StateFlow<MonthGrid?> = combine(repo.snapshot, month, today) { s, m, t ->
        MonthGridBuilder(s.scoring, t).build(m, s.tasks, s.marks, s.weekOverrides)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun refreshToday() { _today.value = LocalDate.now() }

    fun setMonth(m: YearMonth) { saved[KEY_MONTH] = m.toString() }
    fun setDate(d: LocalDate) {
        saved[KEY_DATE] = d.toString()
        saved[KEY_MONTH] = YearMonth.from(d).toString()
    }

    // ---- marks ----

    /** Tap on a cell: cycles ● → – → X → cleared. Changing a derived cell marks it as an override. */
    fun cycle(taskId: String, date: LocalDate, cell: Cell) {
        val manual = if (cell.source == CellSource.MANUAL || cell.source == CellSource.OVERRIDE) cell.display.toState() else null
        val next = when (manual) {
            null -> MarkState.DONE
            MarkState.DONE -> MarkState.SKIP
            MarkState.SKIP -> MarkState.MISSED
            else -> null
        }
        val hadDerived = cell.source == CellSource.DERIVED || cell.source == CellSource.OVERRIDE
        setMark(taskId, date, next, hadDerived && next != MarkState.DONE)
    }

    fun setMark(taskId: String, date: LocalDate, state: MarkState?, isOverride: Boolean, note: String? = null) =
        viewModelScope.launch { repo.setMark(taskId, date, state, isOverride, note) }

    fun setWeekOverride(taskId: String, due: LocalDate, state: MarkState?) =
        viewModelScope.launch { repo.setWeekOverride(taskId, due, state) }

    fun closeOutDay(d: LocalDate) = viewModelScope.launch { snapshot.value?.let { repo.closeOutDay(d, it) } }

    // ---- values ----

    fun saveValue(v: TrackerValueEntity) = viewModelScope.launch { repo.saveValue(v) }
    fun deleteValue(v: TrackerValueEntity) = viewModelScope.launch { repo.deleteValue(v.trackerId, v.date, v.seq) }
    fun addNap(v: TrackerValueEntity) = viewModelScope.launch {
        repo.saveValue(v.copy(seq = repo.nextSeq(v.trackerId, v.date)))
    }

    fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    companion object {
        private const val KEY_MONTH = "month"
        private const val KEY_DATE = "date"
    }
}

fun com.codebasemaker.habittracker.domain.CellDisplay.toState(): MarkState? = when (this) {
    com.codebasemaker.habittracker.domain.CellDisplay.DONE -> MarkState.DONE
    com.codebasemaker.habittracker.domain.CellDisplay.SKIP -> MarkState.SKIP
    com.codebasemaker.habittracker.domain.CellDisplay.MISSED -> MarkState.MISSED
    com.codebasemaker.habittracker.domain.CellDisplay.EMPTY -> null
}
