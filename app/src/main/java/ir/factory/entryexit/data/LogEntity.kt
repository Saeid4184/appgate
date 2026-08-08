package ir.factory.entryexit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * An immutable historical record of a single check-in or check-out event — the basis for the
 * Excel export and the admin dashboard's activity feed. [id] is the Firestore document ID.
 * [personName]/[type]/[group] are denormalized (copied at event time) so history remains
 * accurate even if the person record is later edited.
 * [performedByUid]/[performedByName] record WHICH signed-in guard/admin made this entry —
 * this is the whole point of per-guard accounts: every record is attributable.
 * [detail] holds context specific to the event: department visited (visitors), assigned
 * vehicle (drivers), or cargo/load type (machinery departures).
 */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val personId: String = "",
    val personName: String = "",
    val type: String = "",
    @ColumnInfo(name = "group_name") val group: String? = null,
    val action: String = "", // "IN" or "OUT"
    val timestamp: Long = 0L,
    val detail: String? = null,
    val performedByUid: String? = null,
    val performedByName: String? = null
)
