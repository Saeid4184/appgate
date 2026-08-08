package ir.factory.entryexit.data

/** The four operational sub-fleets within Machinery. A mixer truck can never physically carry
 *  aggregate, a dump truck can never carry ready-mix concrete, and so on — so the cargo/load
 *  options offered at checkout are scoped to whichever category the vehicle belongs to.
 *  Derived from the existing [PersonEntity.group] label (e.g. "میکسر - آمیکو") via keyword
 *  matching rather than a new database column, so no migration or re-entry of existing
 *  machinery is needed. */
enum class MachineryCategory(val displayName: String) {
    MIXER("میکسرها"),
    DUMP_TRUCK("کمپرسی و تریلی‌های حمل مصالح"),
    CONCRETE_PUMP("پمپ‌های انتقال بتن"),
    LOGISTICS("ماشین‌های تدارکات");

    companion object {
        /** Buckets a machinery roster group label into one of the four categories. Anything
         *  unrecognized (e.g. a newly, manually-added vehicle with a custom group name) falls
         *  back to LOGISTICS as a safe, general-purpose catch-all. */
        fun classify(group: String?): MachineryCategory {
            val g = group.orEmpty()
            return when {
                g.contains("میکسر") -> MIXER
                g.contains("پمپ") -> CONCRETE_PUMP
                g.contains("کمپرسی") || g.contains("کمپرسور") || g.contains("تریلر") ||
                    g.contains("تریلی") || g.contains("کامیون") -> DUMP_TRUCK
                else -> LOGISTICS
            }
        }

        /** Factory defaults, used until the admin customizes them per-category in Settings. */
        fun defaultCargoOptions(category: MachineryCategory): List<String> = when (category) {
            MIXER -> listOf("بتن آماده C20", "بتن آماده C25", "بتن آماده C30", "بتن آماده C35", "بدون بار (خالی)")
            DUMP_TRUCK -> listOf("شن و ماسه معدنی", "سنگ‌دانه", "نخاله و خاک", "بدون بار (خالی)")
            CONCRETE_PUMP -> listOf("پمپاژ بتن - پروژه داخلی", "پمپاژ بتن - پروژه بیرونی", "اعزام به تعمیرگاه", "بدون ماموریت")
            LOGISTICS -> listOf("گازوئیل", "قطعات و تجهیزات", "اعزام به تعمیرگاه", "بدون بار (خالی)")
        }
    }
}
