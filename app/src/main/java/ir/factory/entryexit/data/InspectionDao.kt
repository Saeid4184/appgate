package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InspectionDao {

    /** Used by the Firestore sync mirror and by submitInspection's instant local echo. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(inspection: InspectionEntity)

    @Query("DELETE FROM inspections WHERE id = :id")
    suspend fun deleteById(id: String)

    /** All inspections, newest first. Small enough dataset (a handful of vehicles per week)
     *  that the list screen groups "latest per vehicle" client-side from this, same pattern
     *  already used for grouping persons by department in GroupedPersonAdapter. */
    @Query("SELECT * FROM inspections ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<InspectionEntity>>

    @Query("SELECT * FROM inspections WHERE personId = :personId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForPerson(personId: String): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun getById(id: String): InspectionEntity?
}
