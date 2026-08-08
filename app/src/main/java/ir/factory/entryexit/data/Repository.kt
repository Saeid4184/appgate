package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * Firestore is the source of truth; Room (via [CloudSync]) is a local, offline-friendly mirror
 * that the UI reads from. Every write here goes to Firestore — Firestore's own offline queue
 * means a write made with no signal is queued locally and sent automatically once reconnected,
 * and CloudSync's listeners then reflect it into every device's Room cache, including this one.
 *
 * The core business rule (a person cannot be checked in again until checked out) is still
 * enforced here, reading the latest known state from the local Room mirror before writing.
 */
class Repository(
    private val personDao: PersonDao,
    private val logDao: LogDao,
    private val cloudSync: CloudSync,
    private val inspectionDao: InspectionDao,
    private val itemLogDao: ItemLogDao
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val personsCol = firestore.collection(CloudSync.COLLECTION_PERSONS)
    private val logsCol = firestore.collection(CloudSync.COLLECTION_LOGS)
    private val inspectionsCol = firestore.collection(CloudSync.COLLECTION_INSPECTIONS)
    private val itemLogsCol = firestore.collection(CloudSync.COLLECTION_ITEM_LOGS)

    fun startSync() = cloudSync.start()
    fun stopSync() = cloudSync.stop()

    fun getPersonsByType(type: PersonType): LiveData<List<PersonEntity>> = personDao.getByType(type.name)

    fun getInsidePersonsByType(type: PersonType): LiveData<List<PersonEntity>> =
        personDao.getInsideByType(type.name)

    /** Everyone currently inside, across every category — for the admin dashboard. */
    fun getAllCurrentlyInside(): LiveData<List<PersonEntity>> = personDao.getAllInside()

    fun getRecentActivityByType(type: PersonType, limit: Int = 10): LiveData<List<LogEntity>> =
        logDao.getRecentByType(type.name, limit)

    fun search(query: String): LiveData<List<PersonEntity>> = personDao.searchAll(query)

    /** Inserts the fixed machinery fleet AND personnel roster exactly once ACROSS ALL DEVICES,
     *  using a Firestore transaction on a flag document so two guards opening the app for the
     *  first time at the same moment can't both seed duplicates. */
    suspend fun ensureFleetSeeded() {
        // already mirrored locally -> nothing to do, whichever device/order got there first
        if (personDao.countByType(PersonType.MACHINERY.name) > 0 && personDao.countByType(PersonType.PERSONNEL.name) > 0) return

        val flagRef = firestore.document(CloudSync.META_DOC_FLEET_SEEDED)
        val wonRace = try {
            firestore.runTransaction { tx ->
                val snap = tx.get(flagRef)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(flagRef, mapOf("seeded" to true, "seededAt" to System.currentTimeMillis()))
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }

        if (wonRace == true) {
            val batch = firestore.batch()
            for (entity in Fleet.buildInitialRoster() + Staff.buildInitialRoster()) {
                val docRef = personsCol.document()
                batch.set(docRef, entity.copy(id = docRef.id))
            }
            batch.commit().await()
        }
    }

    /** Registers a brand-new person/machine (name-only, or with a department/group). */
    suspend fun addPerson(name: String, type: PersonType, group: String? = null, extraInfo: String? = null): Result<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("نام نمی‌تواند خالی باشد"))
        }
        if (personDao.countByNameAndType(type.name, trimmed, excludeId = "") > 0) {
            return Result.failure(IllegalStateException("این نام قبلاً ثبت شده است"))
        }
        val docRef = personsCol.document()
        val entity = PersonEntity(
            id = docRef.id,
            name = trimmed,
            type = type.name,
            group = group?.trim()?.ifEmpty { null },
            extraInfo = extraInfo?.trim()?.ifEmpty { null }
        )
        return try {
            docRef.set(entity).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Photos are local-only (a content:// URI only makes sense on the device that picked it),
     *  so this updates Room directly and is intentionally NOT synced to Firestore. */
    suspend fun updatePersonImage(personId: String, imageUri: String?): Result<Unit> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))
        personDao.update(fresh.copy(imageUri = imageUri))
        return Result.success(Unit)
    }

    /** Edits an existing person/machine's name, department/group, and extra info. */
    suspend fun updatePerson(personId: String, name: String, group: String?, extraInfo: String?): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("نام نمی‌تواند خالی باشد"))
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("مورد یافت نشد"))

        if (!trimmed.equals(fresh.name, ignoreCase = false)) {
            val duplicateCount = personDao.countByNameAndType(fresh.type, trimmed, excludeId = personId)
            if (duplicateCount > 0) {
                return Result.failure(IllegalStateException("این نام قبلاً برای مورد دیگری ثبت شده است"))
            }
        }

        return try {
            personsCol.document(personId).update(
                mapOf(
                    "name" to trimmed,
                    "group" to group?.trim()?.ifEmpty { null },
                    "extraInfo" to extraInfo?.trim()?.ifEmpty { null }
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    suspend fun getRosterOnce(type: PersonType): List<PersonEntity> = personDao.getByTypeOnce(type.name)

    /** Blocks or unblocks future check-ins for this person/machine. */
    suspend fun setBlacklisted(personId: String, blacklisted: Boolean): Result<Unit> {
        return try {
            personsCol.document(personId).update("isBlacklisted", blacklisted).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Check a person **in**. Fails if they are already marked as inside, or blacklisted.
     * [performedByUid]/[performedByName] record which signed-in guard did this.
     */
    suspend fun checkIn(
        personId: String,
        detail: String? = null,
        performedByUid: String?,
        performedByName: String?
    ): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))

        if (fresh.isBlacklisted) {
            return Result.failure(IllegalStateException("${fresh.name} در لیست سیاه قرار دارد و اجازه ورود ندارد"))
        }
        if (fresh.isInside) {
            return Result.failure(IllegalStateException("${fresh.name} قبلاً ورود ثبت کرده و هنوز خروج نزده است"))
        }

        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(isInside = true, lastEventAt = now)

        return try {
            personsCol.document(personId).update(mapOf("isInside" to true, "lastEventAt" to now)).await()
            val logRef = logsCol.document()
            logRef.set(
                LogEntity(
                    id = logRef.id,
                    personId = fresh.id,
                    personName = fresh.name,
                    type = fresh.type,
                    group = fresh.group,
                    action = ACTION_IN,
                    timestamp = now,
                    detail = detail?.trim()?.ifEmpty { null },
                    performedByUid = performedByUid,
                    performedByName = performedByName
                )
            ).await()
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /**
     * Check a person **out**. Fails if they are not currently inside. [detail] optionally
     * records the cargo/load type for a machinery departure.
     */
    suspend fun checkOut(
        personId: String,
        detail: String? = null,
        performedByUid: String?,
        performedByName: String?
    ): Result<PersonEntity> {
        val fresh = personDao.getById(personId) ?: return Result.failure(IllegalStateException("فرد یافت نشد"))

        if (!fresh.isInside) {
            return Result.failure(IllegalStateException("${fresh.name} ورودی ثبت‌شده‌ای ندارد"))
        }

        val now = System.currentTimeMillis()
        if (fresh.lastEventAt > 0 && now - fresh.lastEventAt < MIN_EVENT_INTERVAL_MS) {
            return Result.failure(IllegalStateException("برای ${fresh.name} چند ثانیه پیش رویدادی ثبت شده؛ از ثبت تکراری جلوگیری شد."))
        }
        val updated = fresh.copy(isInside = false, lastEventAt = now)

        return try {
            personsCol.document(personId).update(mapOf("isInside" to false, "lastEventAt" to now)).await()
            val logRef = logsCol.document()
            logRef.set(
                LogEntity(
                    id = logRef.id,
                    personId = fresh.id,
                    personName = fresh.name,
                    type = fresh.type,
                    group = fresh.group,
                    action = ACTION_OUT,
                    timestamp = now,
                    detail = detail?.trim()?.ifEmpty { null },
                    performedByUid = performedByUid,
                    performedByName = performedByName
                )
            ).await()
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** One-step flow for a guest: register + immediately check in against the department
     *  they're visiting. Every visit creates a fresh record (guests are transient). */
    suspend fun checkInVisitor(name: String, department: String, performedByUid: String?, performedByName: String?): Result<Unit> {
        val trimmedName = name.trim()
        val trimmedDept = department.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("نام مهمان نمی‌تواند خالی باشد"))
        if (trimmedDept.isEmpty()) return Result.failure(IllegalArgumentException("وارد کردن واحد مورد مراجعه الزامی است"))

        val docRef = personsCol.document()
        val entity = PersonEntity(id = docRef.id, name = trimmedName, type = PersonType.VISITOR.name)
        return try {
            docRef.set(entity).await()
            personDao.upsert(entity) // make it visible locally immediately, don't wait on the listener round-trip
            checkIn(docRef.id, trimmedDept, performedByUid, performedByName).map { }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** One-step flow for a driver: register + immediately check in against the vehicle
     *  they're assigned to for this trip. */
    suspend fun checkInDriver(name: String, vehicle: String, performedByUid: String?, performedByName: String?): Result<Unit> {
        val trimmedName = name.trim()
        val trimmedVehicle = vehicle.trim()
        if (trimmedName.isEmpty()) return Result.failure(IllegalArgumentException("نام راننده نمی‌تواند خالی باشد"))

        val docRef = personsCol.document()
        val entity = PersonEntity(id = docRef.id, name = trimmedName, type = PersonType.DRIVER.name)
        return try {
            docRef.set(entity).await()
            personDao.upsert(entity)
            // ورود راننده یعنی حضور برای شیفت/سرویس، نه یک سفر مشخص با یک ماشین خاص — بنابراین
            // ماشین صرفاً یک یادداشت اختیاری در همان اولین ثبت است، نه فیلد الزامی.
            checkIn(docRef.id, trimmedVehicle.ifEmpty { null }, performedByUid, performedByName).map { }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Real-time count (not range-bound) — used for the "currently inside right now" metric. */
    suspend fun countCurrentlyInside(type: PersonType): Int = personDao.countInsideByType(type.name)

    /** How many times this vehicle has been dispatched (checked OUT) in the rolling 24 hours
     *  ending now. Backs the dump-truck/dump-trailer safety check: 4 mine-to-factory runs a day
     *  is normal, 5 can happen with light city traffic, but 6+ in one day should raise a flag —
     *  possible driver fatigue/impairment, an unlogged driver swap, or unsafe over-driving. */
    suspend fun countDispatchesInLast24h(personId: String): Int =
        logDao.countActionsSince(personId, ACTION_OUT, System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS)

    /** Every dispatch (OUT) for the whole Machinery roster since [sinceMillis] — used to build
     *  the mixers' "سرویس امروز" per-vehicle count. For mixers what matters is the running count
     *  of services, not exact timing, so this is intentionally a live feed the fragment can
     *  re-tally client-side rather than a one-shot query. */
    fun getMachineryDispatchesSince(sinceMillis: Long): LiveData<List<LogEntity>> =
        logDao.getActionsSince(PersonType.MACHINERY.name, ACTION_OUT, sinceMillis)

    /**
     * Queries Firestore DIRECTLY for the exact date range (not the bounded local mirror), so
     * Excel exports and AI summaries are always complete regardless of how much history the
     * "recent activity" window happens to be mirroring locally at that moment.
     */
    suspend fun getLogsInRange(startInclusive: Long, endInclusive: Long): List<LogEntity> {
        return try {
            val snapshot = logsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(LogEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Weekly machinery inspections ("بازدید ظاهری") ----

    /** Every submitted inspection, newest first — the tab-5 list groups this client-side to
     *  show only the latest record per vehicle, same lightweight pattern as grouping the
     *  roster by department. */
    fun getAllInspections(): LiveData<List<InspectionEntity>> = inspectionDao.getAllLive()

    suspend fun getPersonById(personId: String): PersonEntity? = personDao.getById(personId)

    /** The vehicle's previous inspection, if any — used by [ir.factory.entryexit.ui
     *  .InspectionFormActivity] to pre-flag on the diagram anything that was WARN/BAD last
     *  time and never got marked repaired, before the guard has tapped anything this week. */
    suspend fun getLatestInspectionForPerson(personId: String): InspectionEntity? =
        inspectionDao.getLatestForPerson(personId)

    /** Saves one vehicle's weekly checklist. [parts] must list every item from
     *  [InspectionCatalog.partsFor] for that vehicle's category, in order. */
    suspend fun submitInspection(
        person: PersonEntity,
        parts: List<InspectionPartResult>,
        notes: String?,
        performedByUid: String?,
        performedByName: String?
    ): Result<Unit> {
        // Carry forward "still broken since ..." for anything that was already WARN/BAD last
        // time and still isn't OK now, so the diagram/report can tell a recurring defect from
        // a brand-new one instead of everything looking equally fresh every week.
        val previous = inspectionDao.getLatestForPerson(person.id)
        val previousByName = previous?.let { InspectionJson.parse(it.partsJson).associateBy { p -> p.name } }.orEmpty()
        val partsWithRecurrence = parts.map { part ->
            if (part.status == PartStatus.OK) return@map part
            val prior = previousByName[part.name] ?: return@map part
            if (prior.status == PartStatus.OK) return@map part
            part.copy(recurringSinceTimestamp = prior.recurringSinceTimestamp ?: previous?.timestamp)
        }

        val approved = partsWithRecurrence.count { it.ok }
        val rejected = partsWithRecurrence.size - approved
        val partsJson = InspectionJson.serialize(partsWithRecurrence)

        val docRef = inspectionsCol.document()
        val entity = InspectionEntity(
            id = docRef.id,
            personId = person.id,
            personName = person.name,
            driverName = person.extraInfo,
            group = person.group,
            category = MachineryCategory.classify(person.group).name,
            partsJson = partsJson,
            approvedCount = approved,
            rejectedCount = rejected,
            notes = notes?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName,
            timestamp = System.currentTimeMillis()
        )
        return try {
            docRef.set(entity).await()
            inspectionDao.upsert(entity) // instant local visibility, don't wait on the listener
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Closes the loop on one defect: stamps [InspectionPartResult.repairedAt] on the named
     *  part of the given inspection record, leaving everything else (including its own
     *  approved/rejected counts — this is history, not a re-inspection) untouched. Used by the
     *  open-defects list so "3 مورد ایراد" doesn't just sit there forever once someone actually
     *  fixes the mirror. */
    suspend fun markPartRepaired(inspectionId: String, partName: String): Result<Unit> {
        val fresh = inspectionDao.getById(inspectionId)
            ?: return Result.failure(IllegalStateException("بازدید یافت نشد"))
        val updatedParts = InspectionJson.parse(fresh.partsJson).map { part ->
            if (part.name == partName) part.copy(repairedAt = System.currentTimeMillis()) else part
        }
        val updated = fresh.copy(partsJson = InspectionJson.serialize(updatedParts))
        return try {
            inspectionsCol.document(inspectionId).set(updated).await()
            inspectionDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Queries Firestore directly for the exact date range, same reasoning as getLogsInRange:
     *  exports must be complete regardless of what the unbounded-but-still-local mirror has
     *  pulled down at this exact moment. */
    suspend fun getInspectionsInRange(startInclusive: Long, endInclusive: Long): List<InspectionEntity> {
        return try {
            val snapshot = inspectionsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(InspectionEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Goods entry/exit ("ورود و خروج اقلام و کالاها") ----

    fun getItemLogs(direction: String): LiveData<List<ItemLogEntity>> = itemLogDao.getByDirection(direction)

    /** OUT records not yet marked returned — backs the "در انتظار برگشت" sub-tab. Deliberately
     *  not surfaced anywhere as an alert/badge, just a filterable list (see chat: return status
     *  isn't considered urgent enough to warrant one). */
    fun getPendingReturnItemLogs(): LiveData<List<ItemLogEntity>> = itemLogDao.getPendingReturns()

    fun getAllItemLogs(): LiveData<List<ItemLogEntity>> = itemLogDao.getAllLive()

    fun getItemLogsSince(sinceMillis: Long): LiveData<List<ItemLogEntity>> = itemLogDao.getSince(sinceMillis)

    /** Registers a "ورود کالا": item type, store, buyer, who ordered the purchase, department,
     *  invoice number. */
    suspend fun addItemLogIn(
        itemName: String,
        storeName: String?,
        buyerName: String?,
        orderedByName: String?,
        department: String?,
        invoiceNumber: String?,
        performedByUid: String?,
        performedByName: String?
    ): Result<Unit> {
        val trimmedItem = itemName.trim()
        if (trimmedItem.isEmpty()) return Result.failure(IllegalArgumentException("نام قطعه یا جنس نمی‌تواند خالی باشد"))

        val docRef = itemLogsCol.document()
        val entity = ItemLogEntity(
            id = docRef.id,
            direction = ITEM_DIRECTION_IN,
            itemName = trimmedItem,
            timestamp = System.currentTimeMillis(),
            storeName = storeName?.trim()?.ifEmpty { null },
            buyerName = buyerName?.trim()?.ifEmpty { null },
            department = department?.trim()?.ifEmpty { null },
            invoiceNumber = invoiceNumber?.trim()?.ifEmpty { null },
            orderedByName = orderedByName?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName
        )
        return try {
            docRef.set(entity).await()
            itemLogDao.upsert(entity) // instant local visibility, don't wait on the listener round-trip
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Registers a "خروج کالا": item type, exit-slip number, carrier, whose order it left
     *  under, and the reason. [isReturned] always starts false — flipped later via
     *  [markItemReturned] once/if the item comes back. */
    suspend fun addItemLogOut(
        itemName: String,
        exitSlipNumber: String?,
        carrierName: String?,
        orderedByName: String?,
        reason: String?,
        performedByUid: String?,
        performedByName: String?
    ): Result<Unit> {
        val trimmedItem = itemName.trim()
        if (trimmedItem.isEmpty()) return Result.failure(IllegalArgumentException("نام قطعه یا جنس نمی‌تواند خالی باشد"))

        val docRef = itemLogsCol.document()
        val entity = ItemLogEntity(
            id = docRef.id,
            direction = ITEM_DIRECTION_OUT,
            itemName = trimmedItem,
            timestamp = System.currentTimeMillis(),
            exitSlipNumber = exitSlipNumber?.trim()?.ifEmpty { null },
            carrierName = carrierName?.trim()?.ifEmpty { null },
            reason = reason?.trim()?.ifEmpty { null },
            isReturned = false,
            orderedByName = orderedByName?.trim()?.ifEmpty { null },
            performedByUid = performedByUid,
            performedByName = performedByName
        )
        return try {
            docRef.set(entity).await()
            itemLogDao.upsert(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Ticks the "برگشت" checkbox: the item exited (e.g. for repair) has physically come back
     *  to the factory. */
    suspend fun markItemReturned(itemLogId: String): Result<Unit> {
        val fresh = itemLogDao.getById(itemLogId) ?: return Result.failure(IllegalStateException("مورد یافت نشد"))
        val now = System.currentTimeMillis()
        val updated = fresh.copy(isReturned = true, returnedAt = now)
        return try {
            itemLogsCol.document(itemLogId).update(mapOf("isReturned" to true, "returnedAt" to now)).await()
            itemLogDao.upsert(updated)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(networkAwareMessage(e)))
        }
    }

    /** Queries Firestore directly for the exact date range, same reasoning as getLogsInRange —
     *  reports/exports must be complete regardless of what's mirrored locally right now. */
    suspend fun getItemLogsInRange(startInclusive: Long, endInclusive: Long): List<ItemLogEntity> {
        return try {
            val snapshot = itemLogsCol
                .whereGreaterThanOrEqualTo("timestamp", startInclusive)
                .whereLessThanOrEqualTo("timestamp", endInclusive)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(ItemLogEntity::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Every distinct value ever entered per field, fetched together in one round trip — powers
     *  the autocomplete dropdowns on the ورود/خروج forms. The suggestion pool grows on its own
     *  as new records are added (from any device), since it's read straight from the table. */
    suspend fun getItemLogSuggestions(): ItemLogSuggestions = ItemLogSuggestions(
        itemNames = itemLogDao.distinctItemNames(),
        stores = itemLogDao.distinctStores(),
        buyers = itemLogDao.distinctBuyers(),
        orderedBy = itemLogDao.distinctOrderedBy(),
        departments = itemLogDao.distinctDepartments(),
        carriers = itemLogDao.distinctCarriers(),
        reasons = itemLogDao.distinctReasons()
    )

    data class ItemLogSuggestions(
        val itemNames: List<String>,
        val stores: List<String>,
        val buyers: List<String>,
        val orderedBy: List<String>,
        val departments: List<String>,
        val carriers: List<String>,
        val reasons: List<String>
    )

    companion object {
        const val ACTION_IN = "IN"
        const val ACTION_OUT = "OUT"

        const val ITEM_DIRECTION_IN = "IN"
        const val ITEM_DIRECTION_OUT = "OUT"

        /** Minimum time between two events for the same person/machine — blocks accidental
         *  double-taps or a race between two guards tapping the same card at once. */
        private const val MIN_EVENT_INTERVAL_MS = 60_000L

        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
    }
}

/** Turns a raw Firestore/network exception into a Persian message safe to show directly. */
private fun networkAwareMessage(e: Exception): String {
    val msg = e.message ?: return "خطا در ارتباط با سرور. اتصال اینترنت را بررسی کنید."
    return when {
        msg.contains("UNAVAILABLE", ignoreCase = true) -> "اتصال اینترنت برقرار نیست. تغییرات پس از اتصال مجدد ثبت می‌شود."
        msg.contains("PERMISSION_DENIED", ignoreCase = true) -> "دسترسی مجاز نیست. با مدیر سیستم تماس بگیرید."
        else -> msg
    }
}
