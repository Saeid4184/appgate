package ir.factory.entryexit.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * A single weekly "بازدید ظاهری" (visual/exterior) inspection of one machine, filled in by a
 * guard from the tab-5 checklist. [id] is the Firestore document ID, same pattern as
 * [PersonEntity]/[LogEntity].
 *
 * [personId]/[personName]/[driverName]/[group] are denormalized (copied at submission time) so
 * history and exports stay accurate even if the machine's roster entry is later edited/renamed.
 * [category] is [MachineryCategory.name], stored explicitly rather than re-derived from [group]
 * so old records keep reading correctly even if classification keywords change later.
 * [partsJson] is a JSON array of {"name": partName, "ok": true/false} — one entry per item in
 * [InspectionCatalog.partsFor] for that category, in order. Kept as JSON (not a Room-relational
 * table) to avoid a schema migration every time the part list changes, mirroring how this
 * project already avoids heavy dependencies (see XlsxWriter's own comments).
 * [approvedCount]/[rejectedCount] are precomputed at submission time so list screens and exports
 * never need to re-parse [partsJson] just to show a summary badge.
 */
@Entity(tableName = "inspections")
data class InspectionEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val personId: String = "",
    val personName: String = "",
    val driverName: String? = null,
    val group: String? = null,
    val category: String = "", // matches MachineryCategory.name
    val partsJson: String = "",
    val approvedCount: Int = 0,
    val rejectedCount: Int = 0,
    val notes: String? = null,
    val performedByUid: String? = null,
    val performedByName: String? = null,
    val timestamp: Long = 0L
)
