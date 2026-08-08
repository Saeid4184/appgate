package ir.factory.entryexit.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Wraps Firebase Auth for email/password sign-in, plus loading each user's role from the
 * "users/{uid}" Firestore document (Firebase Auth alone has no concept of app-specific roles).
 *
 * Account creation policy: the very FIRST account ever created for this project automatically
 * becomes ADMIN (so there's always at least one admin without needing manual Firestore console
 * work). Every account after that defaults to GUARD; an existing admin promotes further admins
 * later by editing that guard's "role" field in the Firestore console (Settings screen also
 * exposes this for convenience once signed in as admin).
 */
class AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = firestore.collection("users")

    fun isSignedIn(): Boolean = auth.currentUser != null

    suspend fun signIn(email: String, password: String): Result<UserProfile> {
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return Result.failure(IllegalStateException("ورود ناموفق بود"))
            loadProfile(uid)
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    /** Registers a brand-new guard account. The name is stored for display ("who did this
     *  check-in/out") throughout the app. */
    suspend fun signUp(email: String, password: String, displayName: String): Result<UserProfile> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return Result.failure(IllegalStateException("ثبت‌نام ناموفق بود"))

            val isFirstEverUser = usersCollection.limit(1).get().await().isEmpty
            val role = if (isFirstEverUser) UserRole.ADMIN else UserRole.GUARD

            val profile = UserProfile(uid = uid, name = displayName.trim(), email = email.trim(), role = role)
            usersCollection.document(uid).set(
                mapOf(
                    "name" to profile.name,
                    "email" to profile.email,
                    "role" to profile.role.name,
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()

            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    suspend fun loadProfile(uid: String): Result<UserProfile> {
        return try {
            val doc = usersCollection.document(uid).get().await()
            if (!doc.exists()) {
                return Result.failure(IllegalStateException("پروفایل کاربری یافت نشد؛ با مدیر تماس بگیرید"))
            }
            val profile = UserProfile(
                uid = uid,
                name = doc.getString("name") ?: "",
                email = doc.getString("email") ?: "",
                role = UserRole.fromStringOrDefault(doc.getString("role"))
            )
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(mapAuthError(e))
        }
    }

    /** Called on app start if Firebase already has a signed-in user (e.g. app was reopened),
     *  so we don't force a re-login every time. */
    suspend fun restoreSessionIfSignedIn(): Result<UserProfile>? {
        val uid = auth.currentUser?.uid ?: return null
        return loadProfile(uid)
    }

    fun signOut() {
        auth.signOut()
        Session.clear()
    }

    private fun mapAuthError(e: Exception): Exception {
        val message = when {
            e.message?.contains("badly formatted", ignoreCase = true) == true -> "فرمت ایمیل نامعتبر است"
            e.message?.contains("password is invalid", ignoreCase = true) == true -> "رمز عبور اشتباه است"
            e.message?.contains("no user record", ignoreCase = true) == true -> "کاربری با این ایمیل یافت نشد"
            e.message?.contains("email address is already in use", ignoreCase = true) == true -> "این ایمیل قبلاً ثبت شده است"
            e.message?.contains("network", ignoreCase = true) == true -> "اتصال اینترنت برقرار نیست"
            e.message?.contains("at least 6 characters", ignoreCase = true) == true -> "رمز عبور باید حداقل ۶ کاراکتر باشد"
            else -> e.message ?: "خطای ورود/ثبت‌نام"
        }
        return IllegalStateException(message)
    }
}
