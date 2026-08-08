package ir.factory.entryexit.data

/** Departments used to sub-categorize personnel. */
enum class Department(val displayName: String) {
    MANAGEMENT("مدیریت"),
    SALES("فروش"),
    FINANCE("امور مالی"),
    SECURITY("حراست"),
    BATCHING("بچینگ"),
    TRANSPORT("ترانسپورت"),
    MACHINERY_DEPT("ماشین‌آلات"),
    WAREHOUSE("انبار"),
    LAB("آزمایشگاه"),
    COLLECTIONS("تحصیلدار"),
    PROCUREMENT("تدارکات"),
    WORKSHOP("تعمیرگاه"),
    SERVICES("خدمات"),
    LOADER_DRIVER("راننده لودر");

    companion object {
        fun fromDisplayNameOrNull(name: String): Department? = values().firstOrNull { it.displayName == name }
    }
}
