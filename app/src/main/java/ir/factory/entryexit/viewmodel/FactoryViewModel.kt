package ir.factory.entryexit.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import ir.factory.entryexit.data.AppDatabase
import ir.factory.entryexit.data.CloudSync
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.InspectionPartResult
import ir.factory.entryexit.data.LogEntity
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Repository
import ir.factory.entryexit.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single ViewModel shared by MainActivity and all four tab fragments
 * (via `by activityViewModels()`), so every screen sees the same live data.
 */
class FactoryViewModel(app: Application) : AndroidViewModel(app) {

    val repository: Repository = run {
        val db = AppDatabase.getInstance(app)
        Repository(db.personDao(), db.logDao(), CloudSync(db), db.inspectionDao(), db.itemLogDao())
    }

    init {
        // Only starts pulling data once a user is actually signed in (LoginActivity gates this).
        if (Session.isSignedIn()) {
            repository.startSync()
            viewModelScope.launch { repository.ensureFleetSeeded() }
        }
    }

    fun personsByType(type: PersonType): LiveData<List<PersonEntity>> = repository.getPersonsByType(type)

    fun insideByType(type: PersonType): LiveData<List<PersonEntity>> = repository.getInsidePersonsByType(type)

    fun allCurrentlyInside(): LiveData<List<PersonEntity>> = repository.getAllCurrentlyInside()

    fun recentActivity(type: PersonType): LiveData<List<LogEntity>> = repository.getRecentActivityByType(type)

    private val searchQuery = MutableLiveData("")
    val searchResults: LiveData<List<PersonEntity>> = searchQuery.switchMap { query ->
        if (query.isBlank()) {
            MutableLiveData<List<PersonEntity>>(emptyList())
        } else {
            repository.search(query)
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun search(query: String): LiveData<List<PersonEntity>> = repository.search(query)

    fun addPerson(
        name: String,
        type: PersonType,
        group: String?,
        extraInfo: String?,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch { onResult(repository.addPerson(name, type, group, extraInfo)) }
    }

    fun checkIn(personId: String, detail: String? = null, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkIn(personId, detail, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkOut(personId: String, detail: String? = null, onResult: (Result<PersonEntity>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkOut(personId, detail, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkInVisitor(name: String, department: String, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkInVisitor(name, department, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun checkInDriver(name: String, vehicle: String, onResult: (Result<Unit>) -> Unit) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.checkInDriver(name, vehicle, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    private suspend fun triggerBackup() {
        withContext(Dispatchers.IO) {
            ir.factory.entryexit.util.BackupManager.backupNow(getApplication())
        }
    }

    fun updatePersonImage(personId: String, imageUri: String?, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.updatePersonImage(personId, imageUri)) }
    }

    fun updatePerson(personId: String, name: String, group: String?, extraInfo: String?, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.updatePerson(personId, name, group, extraInfo)) }
    }

    fun setBlacklisted(personId: String, blacklisted: Boolean, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.setBlacklisted(personId, blacklisted)) }
    }

    fun loadRosterOnce(type: PersonType, onResult: (List<PersonEntity>) -> Unit) {
        viewModelScope.launch {
            val roster = withContext(Dispatchers.IO) { repository.getRosterOnce(type) }
            onResult(roster)
        }
    }

    fun exportRange(startInclusive: Long, endInclusive: Long, onResult: (List<LogEntity>) -> Unit) {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) { repository.getLogsInRange(startInclusive, endInclusive) }
            onResult(logs)
        }
    }

    /** Dump-truck/dump-trailer 24h dispatch-count safety check (see [Repository
     *  .countDispatchesInLast24h]) — checked right after a machinery checkout completes. */
    fun countDispatchesInLast24h(personId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.countDispatchesInLast24h(personId) }
            onResult(count)
        }
    }

    /** Live per-vehicle "سرویس امروز" feed for mixers — see [Repository.getMachineryDispatchesSince]. */
    fun machineryDispatchesSince(sinceMillis: Long): LiveData<List<LogEntity>> =
        repository.getMachineryDispatchesSince(sinceMillis)

    fun currentlyInsideCounts(onResult: (Map<PersonType, Int>) -> Unit) {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                PersonType.values().associateWith { repository.countCurrentlyInside(it) }
            }
            onResult(counts)
        }
    }

    // ---- Weekly machinery inspections ("بازدید ظاهری") ----

    fun allInspections(): LiveData<List<InspectionEntity>> = repository.getAllInspections()

    fun getPerson(personId: String, onResult: (PersonEntity?) -> Unit) {
        viewModelScope.launch {
            val person = withContext(Dispatchers.IO) { repository.getPersonById(personId) }
            onResult(person)
        }
    }

    fun submitInspection(
        person: PersonEntity,
        parts: List<InspectionPartResult>,
        notes: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.submitInspection(person, parts, notes, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun inspectionsInRange(startInclusive: Long, endInclusive: Long, onResult: (List<InspectionEntity>) -> Unit) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.getInspectionsInRange(startInclusive, endInclusive) }
            onResult(list)
        }
    }

    /** Used by [ir.factory.entryexit.ui.InspectionFormActivity] to pre-flag still-open defects
     *  from this vehicle's previous inspection on the diagram before the guard taps anything. */
    fun latestInspectionFor(personId: String, onResult: (InspectionEntity?) -> Unit) {
        viewModelScope.launch {
            val inspection = withContext(Dispatchers.IO) { repository.getLatestInspectionForPerson(personId) }
            onResult(inspection)
        }
    }

    /** Marks one part on one past inspection record as repaired — the repair-closure step of
     *  the open-defects list. */
    fun markPartRepaired(inspectionId: String, partName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.markPartRepaired(inspectionId, partName)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    // ---- Goods entry/exit ("ورود و خروج اقلام و کالاها") ----

    fun itemLogsByDirection(direction: String): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getItemLogs(direction)

    fun pendingReturnItemLogs(): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getPendingReturnItemLogs()

    fun itemLogsSince(sinceMillis: Long): LiveData<List<ir.factory.entryexit.data.ItemLogEntity>> =
        repository.getItemLogsSince(sinceMillis)

    fun addItemLogIn(
        itemName: String,
        storeName: String?,
        buyerName: String?,
        orderedByName: String?,
        department: String?,
        invoiceNumber: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.addItemLogIn(itemName, storeName, buyerName, orderedByName, department, invoiceNumber, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun addItemLogOut(
        itemName: String,
        exitSlipNumber: String?,
        carrierName: String?,
        orderedByName: String?,
        reason: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = Session.currentUser
        viewModelScope.launch {
            val result = repository.addItemLogOut(itemName, exitSlipNumber, carrierName, orderedByName, reason, user?.uid, user?.name)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun markItemReturned(itemLogId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repository.markItemReturned(itemLogId)
            if (result.isSuccess) triggerBackup()
            onResult(result)
        }
    }

    fun itemLogsInRange(startInclusive: Long, endInclusive: Long, onResult: (List<ir.factory.entryexit.data.ItemLogEntity>) -> Unit) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.getItemLogsInRange(startInclusive, endInclusive) }
            onResult(list)
        }
    }

    /** Fetched fresh every time a ورود/خروج dialog opens, so suggestions always reflect the
     *  latest values entered from any device. */
    fun itemLogSuggestions(onResult: (Repository.ItemLogSuggestions) -> Unit) {
        viewModelScope.launch {
            val suggestions = withContext(Dispatchers.IO) { repository.getItemLogSuggestions() }
            onResult(suggestions)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopSync()
    }
}
