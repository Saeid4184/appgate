package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PersonDao {

    /** Used only by the Firestore sync mirror: insert-or-replace a document snapshot as-is. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(person: PersonEntity)

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deleteById(id: String)

    @Update
    suspend fun update(person: PersonEntity)

    /** Full roster for a category, sorted so section headers (by group) come out in order. */
    @Query("SELECT * FROM persons WHERE type = :type ORDER BY group_name ASC, name ASC")
    fun getByType(type: String): LiveData<List<PersonEntity>>

    /** Currently-inside roster, most recently checked-in first. */
    @Query("SELECT * FROM persons WHERE type = :type AND isInside = 1 ORDER BY lastEventAt DESC")
    fun getInsideByType(type: String): LiveData<List<PersonEntity>>

    /** Everyone currently inside, across every category — used by the admin dashboard. */
    @Query("SELECT * FROM persons WHERE isInside = 1 ORDER BY lastEventAt DESC")
    fun getAllInside(): LiveData<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PersonEntity?

    @Query("SELECT COUNT(*) FROM persons WHERE type = :type AND name = :name AND id != :excludeId")
    suspend fun countByNameAndType(type: String, name: String, excludeId: String): Int

    @Query("SELECT COUNT(*) FROM persons WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT COUNT(*) FROM persons WHERE type = :type AND isInside = 1")
    suspend fun countInsideByType(type: String): Int

    /** Quick search across every category (used by the global search screen). */
    @Query(
        "SELECT * FROM persons WHERE name LIKE '%' || :query || '%' " +
            "OR group_name LIKE '%' || :query || '%' ORDER BY type ASC, name ASC"
    )
    fun searchAll(query: String): LiveData<List<PersonEntity>>

    /** All personnel/machinery for setup screens (photo assignment), sorted by group. */
    @Query("SELECT * FROM persons WHERE type = :type ORDER BY group_name ASC, name ASC")
    suspend fun getByTypeOnce(type: String): List<PersonEntity>
}
