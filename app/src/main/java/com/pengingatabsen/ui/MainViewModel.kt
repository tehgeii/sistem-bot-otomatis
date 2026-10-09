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

    /** Alarm jam buka yang terlewat (tampil di layar Hari ini). */
    val missedAlarms = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    fun setMissedAlarms(list: List<String>) { missedAlarms.value = list }

    // ---------- Impor jadwal dari KRS SiAdin ----------

    sealed class KrsImport {
        data object Idle : KrsImport()
        data object Loading : KrsImport()
        data class Ready(val all: List<com.pengingatabsen.logic.CourseData>, val fresh: List<com.pengingatabsen.logic.CourseData>) : KrsImport()
        data class Failed(val message: String) : KrsImport()
        data class Done(val added: Int) : KrsImport()
    }

    val krsImport = kotlinx.coroutines.flow.MutableStateFlow<KrsImport>(KrsImport.Idle)

    /** Buka halaman Akademik → KRS SiAdin di latar (login otomatis yang sama), baca kartu, susun jadwal. */
    fun readKrs() = viewModelScope.launch {
        if (krsImport.value is KrsImport.Loading) return@launch
        krsImport.value = KrsImport.Loading
        val settings = store.current()
        val credentials = if (settings.autoLogin) store.siadinLogin() else null
        com.pengingatabsen.data.DiagLog.add("impor KRS: mulai")
        val result = com.pengingatabsen.launch.SiadinChecker.check(
            Graph.appContext,
            com.pengingatabsen.launch.TargetApps.SIADIN_ORIGIN + "/akademik",
            credentials,
            courseName = "",
            extractScript = com.pengingatabsen.launch.SiadinScripts.CARD_TEXTS_SCRIPT,
        ) { com.pengingatabsen.data.DiagLog.add("impor KRS: $it") }
        val texts = com.pengingatabsen.data.OfficialSync.cardTexts(result.extracted)
        val parsed = com.pengingatabsen.logic.KrsParser.parse(texts)
        // Kartu KRS juga memuat persentase kehadiran resmi: sekalian disimpan.
        com.pengingatabsen.data.OfficialSync.absorb(Graph.appContext, texts)
        com.pengingatabsen.data.DiagLog.add("impor KRS: ${texts.size} kartu → ${parsed.size} jadwal (${result.state})")
        krsImport.value = when {
            parsed.isNotEmpty() -> KrsImport.Ready(parsed, repo.newFromKrs(parsed))
            result.state == com.pengingatabsen.launch.PresensiState.LOGIN_FAILED ->
                KrsImport.Failed("Login SiAdin ditolak. Perbarui NIM/password di Pengaturan → SiAdin web.")
            credentials == null -> KrsImport.Failed("Simpan NIM & password SiAdin dulu di Pengaturan → SiAdin web.")
            else -> KrsImport.Failed("KRS tidak terbaca (${result.detail.substringBefore(" |")}). Coba lagi sebentar lagi.")
        }
    }

    fun importKrs(replaceAll: Boolean) = viewModelScope.launch {
        val ready = krsImport.value as? KrsImport.Ready ?: return@launch
        val added = repo.importFromKrs(ready.all, replaceAll)
        com.pengingatabsen.data.DiagLog.add("impor KRS: ${if (replaceAll) "ganti semua" else "tambah"} → $added jadwal")
        krsImport.value = KrsImport.Done(added)
    }

    fun closeKrs() { krsImport.value = KrsImport.Idle }

    // ---------- Kehadiran resmi SiAdin ----------

    val officialSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun syncOfficial(onDone: (String) -> Unit) = viewModelScope.launch {
        if (officialSyncing.value) return@launch
        officialSyncing.value = true
        val msg = runCatching { com.pengingatabsen.data.OfficialSync.syncFromKrs(Graph.appContext) }
            .getOrElse { "Gagal sinkron (${it.javaClass.simpleName})." }
        officialSyncing.value = false
        onDone(msg)
    }

    fun save(course: Course) = viewModelScope.launch { repo.saveCourse(course) }
    fun addReplacement(source: Course, draft: com.pengingatabsen.ui.schedule.ReplacementDraft) = viewModelScope.launch {
        repo.addReplacement(source, draft.date, draft.openMinute, draft.closeMinute, draft.room, draft.skipRegularOn)
    }
    fun delete(course: Course) = viewModelScope.launch { repo.deleteCourse(course) }
    fun setActive(course: Course, active: Boolean) = viewModelScope.launch { repo.setActive(course, active) }
    fun holidayToday(course: Course) = viewModelScope.launch { repo.holidayToday(course) }
    fun skipThisWeek(course: Course) = viewModelScope.launch { repo.skipThisWeek(course) }
    fun clearSkip(course: Course) = viewModelScope.launch { repo.clearSkip(course) }
    fun pauseAll(until: java.time.LocalDate) = viewModelScope.launch { repo.pauseAll(until) }
    fun resumeAll() = viewModelScope.launch { repo.resumeAll() }
    fun resend(record: AttendanceRecord) = viewModelScope.launch { repo.resend(record.id) }

    fun exportSchedule(onReady: (String) -> Unit) = viewModelScope.launch { onReady(repo.exportSchedule()) }
    fun previewImport(text: String, onResult: (Int) -> Unit) = viewModelScope.launch { onResult(repo.previewImport(text)) }
    fun importSchedule(text: String, onDone: (Int) -> Unit) = viewModelScope.launch { onDone(repo.importSchedule(text)) }
}
