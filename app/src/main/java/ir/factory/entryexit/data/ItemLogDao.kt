package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ItemLogDao {

    /** Used only by the Firestore sync mirror, and for instant local echo on submit. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: ItemLogEntity)

    @Query("DELETE FROM item_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM item_logs ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<ItemLogEntity>>

    @Query("SELECT * FROM item_logs WHERE direction = :direction ORDER BY timestamp DESC")
    fun getByDirection(direction: String): LiveData<List<ItemLogEntity>>

    /** Every OUT record not yet marked returned — backs the "در انتظار برگشت" sub-tab. */
    @Query("SELECT * FROM item_logs WHERE direction = 'OUT' AND isReturned = 0 ORDER BY timestamp DESC")
    fun getPendingReturns(): LiveData<List<ItemLogEntity>>

    @Query("SELECT * FROM item_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ItemLogEntity?

    @Query("SELECT * FROM item_logs WHERE timestamp >= :sinceMillis")
    fun getSince(sinceMillis: Long): LiveData<List<ItemLogEntity>>

    // ---- Autocomplete: every distinct value ever entered, so the suggestion list keeps
    //      growing on its own as new records come in from any device (Room mirrors Firestore). ----

    @Query("SELECT DISTINCT itemName FROM item_logs WHERE itemName != '' ORDER BY itemName ASC")
    suspend fun distinctItemNames(): List<String>

    @Query("SELECT DISTINCT storeName FROM item_logs WHERE storeName IS NOT NULL AND storeName != '' ORDER BY storeName ASC")
    suspend fun distinctStores(): List<String>

    @Query("SELECT DISTINCT buyerName FROM item_logs WHERE buyerName IS NOT NULL AND buyerName != '' ORDER BY buyerName ASC")
    suspend fun distinctBuyers(): List<String>

    @Query("SELECT DISTINCT orderedByName FROM item_logs WHERE orderedByName IS NOT NULL AND orderedByName != '' ORDER BY orderedByName ASC")
    suspend fun distinctOrderedBy(): List<String>

    @Query("SELECT DISTINCT department FROM item_logs WHERE department IS NOT NULL AND department != '' ORDER BY department ASC")
    suspend fun distinctDepartments(): List<String>

    @Query("SELECT DISTINCT carrierName FROM item_logs WHERE carrierName IS NOT NULL AND carrierName != '' ORDER BY carrierName ASC")
    suspend fun distinctCarriers(): List<String>

    @Query("SELECT DISTINCT reason FROM item_logs WHERE reason IS NOT NULL AND reason != '' ORDER BY reason ASC")
    suspend fun distinctReasons(): List<String>
}
