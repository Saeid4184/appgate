package ir.factory.entryexit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/**
 * A registered person / machine / driver / visitor.
 *
 * [id] is the Firestore document ID (shared across every guard's phone and the web panel) —
 * Room's @PrimaryKey here is just that same string, not a locally auto-generated number, so
 * IDs never collide between devices writing at the same time.
 * [isInside] is the single source of truth for whether they are currently inside the factory.
 * [group] is a department name for personnel, or a fleet/model group for machinery.
 * [imageUri] is a LOCAL content:// URI (profile photos are not currently synced to the cloud —
 * see README for why — so each device shows its own locally-assigned photos only).
 * [lastEventAt] is updated on every check-in/out and used to sort "currently inside" lists.
 * [isBlacklisted] blocks future check-ins for this person/machine.
 *
 * NOTE: [isInside]/[isBlacklisted] are explicitly pinned to their exact Firestore field names
 * via @PropertyName. Without this, Firestore's Java/Android SDK silently strips the "is" prefix
 * from boolean getters (isInside() -> field "inside") when serializing via .set(entity), which
 * would then mismatch the literal "isInside" key used by Repository's raw .update(mapOf(...))
 * calls — two different keys for the same logical field, silently breaking status sync.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    val type: String = "", // matches PersonType.name
    @ColumnInfo(name = "group_name") val group: String? = null,
    val extraInfo: String? = null,
    val imageUri: String? = null,
    @get:PropertyName("isInside") @set:PropertyName("isInside")
    var isInside: Boolean = false,
    val lastEventAt: Long = 0L,
    @get:PropertyName("isBlacklisted") @set:PropertyName("isBlacklisted")
    var isBlacklisted: Boolean = false
)
