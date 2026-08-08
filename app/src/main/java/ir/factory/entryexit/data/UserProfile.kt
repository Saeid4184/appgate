package ir.factory.entryexit.data

/** A signed-in guard/admin's identity, loaded from the "users/{uid}" Firestore document
 *  right after sign-in and cached for the session (see [Session]). */
data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val role: UserRole = UserRole.GUARD
)
