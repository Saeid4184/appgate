package ir.factory.entryexit.data

/** GUARD can check people/machines in and out and manage the roster. ADMIN additionally sees
 *  the dashboard, reports/Excel export, AI analysis, initial photo setup, and the web panel
 *  link, and can manage the blacklist and other guards' accounts. */
enum class UserRole(val displayName: String) {
    GUARD("نگهبان"),
    ADMIN("مدیر");

    companion object {
        fun fromStringOrDefault(value: String?): UserRole =
            runCatching { valueOf(value ?: GUARD.name) }.getOrDefault(GUARD)
    }
}
