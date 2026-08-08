package ir.factory.entryexit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room is now a LOCAL CACHE mirroring Firestore (see CloudSync), not the primary source of
 * truth — Firestore is. This is what makes cross-device sync work: every write goes to
 * Firestore, and Firestore's own listeners update every device's Room cache (including the
 * one that made the write). Room still gives the UI instant, offline-friendly LiveData reads.
 */
@Database(
    entities = [PersonEntity::class, LogEntity::class, InspectionEntity::class, ItemLogEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun logDao(): LogDao
    abstract fun inspectionDao(): InspectionDao
    abstract fun itemLogDao(): ItemLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        const val DB_NAME = "factory_entry_exit.db"

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    // Version 2 switches primary keys from local auto-increment Longs to
                    // Firestore document ID strings (required for safe multi-device sync) —
                    // a one-time local cache reset; Firestore itself is unaffected.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
