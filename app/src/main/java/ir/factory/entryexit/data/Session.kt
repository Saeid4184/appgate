package ir.factory.entryexit.data

/**
 * In-memory holder for the currently signed-in user's profile, populated once at sign-in/app
 * start (see AuthRepository.loadCurrentSession) and read synchronously everywhere the UI needs
 * to know "who is this" or "are they an admin" without an extra async Firestore round-trip.
 */
object Session {
    @Volatile
    var currentUser: UserProfile? = null
        private set

    fun set(profile: UserProfile?) {
        currentUser = profile
    }

    fun clear() {
        currentUser = null
    }

    fun isSignedIn(): Boolean = currentUser != null

    fun isAdmin(): Boolean = currentUser?.role == UserRole.ADMIN

    /** Falls back to "نگهبان" if, somehow, a write happens before the session is loaded. */
    fun currentUserOrGuestLabel(): String = currentUser?.name?.takeIf { it.isNotBlank() } ?: "کاربر نامشخص"
}
