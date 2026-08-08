package ir.factory.entryexit.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId

/**
 * A single "ورود کالا" (goods received) or "خروج کالا" (goods dispatched) record — tab 6,
 * "ورود و خروج اقلام و کالاها". Same Firestore-document-ID-as-Room-primary-key pattern as
 * [PersonEntity]/[LogEntity]/[InspectionEntity].
 *
 * One entity covers both directions (rather than two separate tables) since most fields are
 * either shared ([itemName], [timestamp]) or map 1:1 in meaning between the two forms
 * ([orderedByName] is "دستور خرید" for an entry and "به دستور چه کسی" for an exit) — splitting
 * into two tables would just duplicate the same shape twice.
 *
 * IN-only fields: [storeName], [buyerName], [department], [invoiceNumber].
 * OUT-only fields: [exitSlipNumber], [carrierName], [reason], [isReturned]/[returnedAt].
 *
 * [isReturned] starts false on every OUT record and is flipped (with [returnedAt] stamped) once
 * the item physically comes back to the factory — this is what powers the "در انتظار برگشت"
 * sub-tab. It's intentionally not an alert/badge anywhere else, just a filterable status.
 */
@Entity(tableName = "item_logs")
data class ItemLogEntity(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val direction: String = "", // "IN" or "OUT" — see Repository.ITEM_DIRECTION_IN/OUT
    val itemName: String = "",
    val timestamp: Long = 0L,

    // ---- ورود کالا ----
    val storeName: String? = null,
    val buyerName: String? = null,
    val department: String? = null,
    val invoiceNumber: String? = null,

    // ---- خروج کالا ----
    val exitSlipNumber: String? = null,
    val carrierName: String? = null,
    val reason: String? = null,
    val isReturned: Boolean = false,
    val returnedAt: Long? = null,

    // ---- مشترک ----
    /** "دستور خرید" (ورود) یا "به دستور چه کسی" (خروج) — یک فیلد، معنای متفاوت بسته به جهت. */
    val orderedByName: String? = null,
    val performedByUid: String? = null,
    val performedByName: String? = null
)
