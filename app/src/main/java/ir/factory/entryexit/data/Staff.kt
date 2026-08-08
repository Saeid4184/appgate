package ir.factory.entryexit.data

/**
 * The factory's fixed personnel roster (name, department, and — for management — their exact
 * title). Used once, on first launch, to pre-populate the `persons` table alongside [Fleet],
 * so the Personnel tab opens already organized by department. Users can still register
 * additional personnel manually afterwards.
 */
object Staff {

    private data class StaffMember(
        val name: String,
        val department: Department,
        val title: String? = null
    )

    private val members = listOf(
        // امور مالی
        StaffMember("ایمان عزیزی", Department.FINANCE),
        StaffMember("خانم فرشته شمشکی", Department.FINANCE),
        StaffMember("خانم انجیاری", Department.FINANCE),
        StaffMember("خانم آتنا نبیئی", Department.FINANCE),
        StaffMember("اشکان حشمت پور", Department.FINANCE),

        // انبار
        StaffMember("علی کیا شمشکی", Department.WAREHOUSE),
        StaffMember("محمد صمدی", Department.WAREHOUSE),

        // آزمایشگاه
        StaffMember("بهنام بیگی", Department.LAB),
        StaffMember("محمد علی اکبری", Department.LAB),
        StaffMember("کامران محبی", Department.LAB),
        StaffMember("وحید دشتی", Department.LAB),

        // بچینگ
        StaffMember("امید طاهری", Department.BATCHING),
        StaffMember("حسین علیخانی", Department.BATCHING),
        StaffMember("حسین یاراحمدی", Department.BATCHING),

        // تحصیلدار
        StaffMember("مهدی الیاسی", Department.COLLECTIONS),

        // تدارکات
        StaffMember("قاسم انصاری", Department.PROCUREMENT),
        StaffMember("کاوه بهرامی", Department.PROCUREMENT),

        // ترانسپورت
        StaffMember("عرفان شکوری", Department.TRANSPORT),
        StaffMember("محمود پور مرادی", Department.TRANSPORT),
        StaffMember("معین حبیبی", Department.TRANSPORT),

        // تعمیرگاه
        StaffMember("امیر مرادی", Department.WORKSHOP),
        StaffMember("حاجی محمد", Department.WORKSHOP),
        StaffMember("سعید انصاری", Department.WORKSHOP),

        // حراست
        StaffMember("سعید رضایی", Department.SECURITY),
        StaffMember("محمد حسین جراح زاده", Department.SECURITY),
        StaffMember("هاشم گودرزی", Department.SECURITY),
        StaffMember("عزیز یار احمدی", Department.SECURITY),

        // خدمات
        StaffMember("حسن صادقی", Department.SERVICES),

        // راننده لودر
        StaffMember("حسین رازگردانی", Department.LOADER_DRIVER),
        StaffMember("حسین یاری", Department.LOADER_DRIVER),

        // فروش (حامد پیروزمند لیست شده به‌عنوان «مدیر فروش» در بخش مدیریت)
        StaffMember("امیر بهادر مبهوت", Department.SALES),
        StaffMember("معصومه شیری", Department.SALES),
        StaffMember("مهسا میرزائی", Department.SALES),
        StaffMember("علی زمانی", Department.SALES),
        StaffMember("محمد منیعی", Department.SALES),
        StaffMember("عبداللهیان", Department.SALES),
        StaffMember("واحد پور", Department.SALES),
        StaffMember("پارسا گنجگانی", Department.SALES),

        // ماشین‌آلات (پرسنل، نه ناوگان)
        StaffMember("امیر محمد رحمتی", Department.MACHINERY_DEPT),
        StaffMember("ابراهیم زرگر", Department.MACHINERY_DEPT),
        StaffMember("امیر حسین سیاهوشی", Department.MACHINERY_DEPT),
        StaffMember("خانم الهام آسیجانی", Department.MACHINERY_DEPT),
        StaffMember("خانم افسانه موسوی", Department.MACHINERY_DEPT),
        StaffMember("پیمان گنجگانی", Department.MACHINERY_DEPT),

        // مدیریت
        StaffMember("امیر بصیر", Department.MANAGEMENT, "مدیر آزمایشگاه"),
        StaffMember("پژمان نقلی", Department.MANAGEMENT, "مدیر ماشین‌آلات"),
        StaffMember("خانم ساریه شیعه نژاد", Department.MANAGEMENT, "مدیر انبار و تدارکات"),
        StaffMember("علی راستگوپسند", Department.MANAGEMENT, "مدیریت رخش ترابر"),
        StaffMember("خانم الهه راستگوپسند", Department.MANAGEMENT, "مدیریت سبحان بتن"),
        StaffMember("سعید فضلی زاده", Department.MANAGEMENT, "مدیر ترانسپورت"),
        StaffMember("خانم شیوا شیبانی", Department.MANAGEMENT, "مدیریت مالی"),
        StaffMember("حامد پیروزمند", Department.MANAGEMENT, "مدیر فروش"),
        StaffMember("خانم نجمه پهلوانیان", Department.MANAGEMENT, "مسئول دفتر"),
        StaffMember("اسماعیل یاراحمدی", Department.MANAGEMENT, "مسئول کارگاه و مدیر بچینگ"),
        StaffMember("محمد زندیه", Department.MANAGEMENT, "مدیر حراست")
    )

    /** Builds the full list of [PersonEntity] rows to insert on first run. */
    fun buildInitialRoster(): List<PersonEntity> = members.map { m ->
        PersonEntity(
            name = m.name,
            type = PersonType.PERSONNEL.name,
            group = m.department.displayName,
            extraInfo = m.title
        )
    }
}
