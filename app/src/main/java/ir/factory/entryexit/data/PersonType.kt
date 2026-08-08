package ir.factory.entryexit.data

/**
 * The four top-level tabs of the app.
 * [displayName] is shown in the UI; the enum [name] is the stable value stored in the DB.
 */
enum class PersonType(val displayName: String) {
    PERSONNEL("پرسنل"),
    MACHINERY("ماشین‌آلات"),
    VISITOR("مراجعین و مهمانان"),
    DRIVER("رانندگان")
}
