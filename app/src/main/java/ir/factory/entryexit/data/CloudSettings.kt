package ir.factory.entryexit.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * A handful of settings (right now: the Gemini API key) are meant to be shared between the
 * Android app and the web admin panel — both talk to the same factory, so asking the admin
 * to paste the same key twice is just friction. This stores them in a single small Firestore
 * document (`settings/app`) that both sides read from and write to, on top of each side's own
 * local cache (AppPreferences here, localStorage-equivalent on the web) for fast/offline access.
 */
object CloudSettings {

    private const val DOCUMENT_PATH = "settings/app"
    private const val FIELD_GEMINI_API_KEY = "geminiApiKey"

    /** Returns the shared key from Firestore, or null if nothing has ever been saved there
     *  (or the read fails, e.g. no network) — callers should fall back to the local copy. */
    suspend fun fetchAiApiKey(): String? = try {
        val doc = FirebaseFirestore.getInstance().document(DOCUMENT_PATH).get().await()
        doc.getString(FIELD_GEMINI_API_KEY)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Pushes the key up so the web panel (and any other device) picks it up without
     *  re-entering it. Best-effort: failures (e.g. offline) are swallowed since the local
     *  copy in AppPreferences is already saved and still works for this device. */
    suspend fun pushAiApiKey(key: String) {
        if (key.isBlank()) return
        try {
            FirebaseFirestore.getInstance().document(DOCUMENT_PATH)
                .set(mapOf(FIELD_GEMINI_API_KEY to key), com.google.firebase.firestore.SetOptions.merge())
                .await()
        } catch (e: Exception) {
            // Ignored — see docstring.
        }
    }
}
