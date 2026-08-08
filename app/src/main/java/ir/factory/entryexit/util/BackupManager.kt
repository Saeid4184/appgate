package ir.factory.entryexit.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ir.factory.entryexit.data.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies the live Room database file to a public backup location so the check-in/out history
 * survives even if the app is closed, reset, or reinstalled. Runs on every successful check-in
 * or check-out (cheap: SQLite files here stay well under a few MB), always from a background
 * thread — callers are expected to invoke it inside a coroutine.
 */
object BackupManager {

    private const val BACKUP_FOLDER = "ConcreteFactoryBackups"

    fun backupNow(context: Context) {
        try {
            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            if (!dbFile.exists()) return

            val fileName = "backup_${timestamp()}.db"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_FOLDER")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    dbFile.inputStream().use { it.copyTo(out) }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, BACKUP_FOLDER).apply { mkdirs() }
                val target = File(folder, fileName)
                dbFile.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            pruneOldBackups(context)
        } catch (_: Exception) {
            // Backups are best-effort: never crash the app (or block a check-in/out) over this.
        }
    }

    /** Keeps only the most recent [MAX_BACKUPS] copies to avoid filling up the device's storage. */
    private fun pruneOldBackups(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_FOLDER%")

        val ids = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                ids += cursor.getLong(idCol) to cursor.getLong(dateCol)
            }
        }
        if (ids.size > MAX_BACKUPS) {
            for ((id, _) in ids.drop(MAX_BACKUPS)) {
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private const val MAX_BACKUPS = 20
}
