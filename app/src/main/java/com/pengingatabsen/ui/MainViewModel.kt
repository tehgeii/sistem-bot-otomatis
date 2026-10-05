package com.pengingatabsen.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pengingatabsen.Graph
import com.pengingatabsen.data.AppSettings
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.Course
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val repo = Graph.repository
    private val store = Graph.settings

    val courses: StateFlow<List<Course>?> =
        repo.courses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val history: StateFlow<List<AttendanceRecord>?> =
        repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings?> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(course: Course) = viewModelScope.launch { repo.saveCourse(course) }
    fun delete(course: Course) = viewModelScope.launch { repo.deleteCourse(course) }
    fun setActive(course: Course, active: Boolean) = viewModelScope.launch { repo.setActive(course, active) }
    fun holidayToday(course: Course) = viewModelScope.launch { repo.holidayToday(course) }
    fun skipThisWeek(course: Course) = viewModelScope.launch { repo.skipThisWeek(course) }
    fun clearSkip(course: Course) = viewModelScope.launch { repo.clearSkip(course) }
    fun resend(record: AttendanceRecord) = viewModelScope.launch { repo.resend(record.id) }

    fun exportSchedule(onReady: (String) -> Unit) = viewModelScope.launch { onReady(repo.exportSchedule()) }
    fun previewImport(text: String, onResult: (Int) -> Unit) = viewModelScope.launch { onResult(repo.previewImport(text)) }
    fun importSchedule(text: String, onDone: (Int) -> Unit) = viewModelScope.launch { onDone(repo.importSchedule(text)) }
}
