package ir.factory.entryexit.data

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mirrors Firestore's "persons", "logs", and "inspections" collections into the local Room
 * cache in real time.
 * This is what makes every guard's phone and the admin's device/web panel agree on the same
 * data: whichever device made a change, Firestore fans it back out to every listener (including
 * the device that wrote it), and this class writes that into Room so the existing LiveData-based
 * UI updates automatically — no UI code needed to change for the sync itself.
 *
 * [imageUri] is deliberately never part of the synced Firestore document (profile/equipment
 * photos are picked via the device's own gallery and only make sense as a local content:// URI
 * on that device) — see the merge step below that preserves whatever local photo was already
 * set, instead of letting a remote update wipe it out.
 */
class CloudSync(private val db: AppDatabase) {

    private val firestore = FirebaseFirestore.getInstance()
    private var personsListener: ListenerRegistration? = null
    private var logsListener: ListenerRegistration? = null
    private var inspectionsListener: ListenerRegistration? = null
    private var itemLogsListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (personsListener != null) return // already running

        personsListener = firestore.collection(COLLECTION_PERSONS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    for (change in snapshot.documentChanges) {
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                val incoming = change.document.toObject(PersonEntity::class.java)
                                val existingLocal = db.personDao().getById(incoming.id)
                                // Preserve the locally-assigned photo; it never lives in Firestore.
                                db.personDao().upsert(incoming.copy(imageUri = existingLocal?.imageUri))
                            }
                            DocumentChange.Type.REMOVED -> db.personDao().deleteById(change.document.id)
                        }
                    }
                }
            }

        // Bounded window for the live "recent activity" feed / dashboard ticker. Full-range
        // Excel exports and AI summaries query Firestore directly instead (see Repository),
        // so historical accuracy for reports is never limited by this window.
        logsListener = firestore.collection(COLLECTION_LOGS)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(500)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    for (change in snapshot.documentChanges) {
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                db.logDao().upsert(change.document.toObject(LogEntity::class.java))
                            }
                            DocumentChange.Type.REMOVED -> db.logDao().deleteById(change.document.id)
                        }
                    }
                }
            }

        // Weekly machinery inspections. Unbounded (no .limit()): the fleet is small and one
        // inspection per vehicle per week keeps total volume low, so — unlike the "recent
        // activity" logs window — there's no need to trade completeness for a bounded query.
        inspectionsListener = firestore.collection(COLLECTION_INSPECTIONS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    for (change in snapshot.documentChanges) {
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                db.inspectionDao().upsert(change.document.toObject(InspectionEntity::class.java))
                            }
                            DocumentChange.Type.REMOVED -> db.inspectionDao().deleteById(change.document.id)
                        }
                    }
                }
            }

        // Goods entry/exit ("ورود و خروج اقلام و کالاها"). Unbounded, same reasoning as
        // inspections: this is far lower volume than the traffic log, so completeness matters
        // more than trimming the window.
        itemLogsListener = firestore.collection(COLLECTION_ITEM_LOGS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    for (change in snapshot.documentChanges) {
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                db.itemLogDao().upsert(change.document.toObject(ItemLogEntity::class.java))
                            }
                            DocumentChange.Type.REMOVED -> db.itemLogDao().deleteById(change.document.id)
                        }
                    }
                }
            }
    }

    fun stop() {
        personsListener?.remove()
        personsListener = null
        logsListener?.remove()
        logsListener = null
        inspectionsListener?.remove()
        inspectionsListener = null
        itemLogsListener?.remove()
        itemLogsListener = null
    }

    companion object {
        const val COLLECTION_PERSONS = "persons"
        const val COLLECTION_LOGS = "logs"
        const val COLLECTION_INSPECTIONS = "inspections"
        const val COLLECTION_ITEM_LOGS = "itemLogs"
        const val COLLECTION_USERS = "users"
        const val META_DOC_FLEET_SEEDED = "meta/fleetSeeded"
    }
}
