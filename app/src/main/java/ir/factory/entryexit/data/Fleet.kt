package ir.factory.entryexit.data

import ir.factory.entryexit.util.toPersianDigitsInString

/**
 * The factory's fixed machinery roster: real plate numbers, models, and usual drivers.
 * Used once, on first launch, to pre-populate the `persons` table so the Machinery tab opens
 * already organized by model/brand. Users can still register additional machinery manually
 * afterwards.
 *
 * [groupLabel] doubles as the section header in the Machinery tab AND is what
 * [MachineryCategory.classify] reads to bucket each vehicle into mixer/dump-truck/pump/logistics
 * for the checkout cargo options and the four filter chips.
 */
object Fleet {

    private data class FleetVehicle(
        val plate: String,
        val groupLabel: String,
        val driverName: String? = null
    )

    private val vehicles = listOf(
        // کمپرسی تریلی - فاو
        FleetVehicle("69744", "کمپرسی تریلی - فاو", "سیروس انصاری"),
        FleetVehicle("69747", "کمپرسی تریلی - فاو", "شاپور حبیب زاده"),
        FleetVehicle("69743", "کمپرسی تریلی - فاو", "علی پروین"),
        FleetVehicle("69752", "کمپرسی تریلی - فاو", "رضا شمس"),
        FleetVehicle("69759", "کمپرسی تریلی - فاو", "مهدی حبیبی"),
        FleetVehicle("69763", "کمپرسی تریلی - فاو", "نصرت الله حیدری"),

        // کمپرسی - آمیکو
        FleetVehicle("63476", "کمپرسی - آمیکو", "غلامی"),
        FleetVehicle("69246", "کمپرسی - آمیکو", "علی کریمی"),
        FleetVehicle("69733", "کمپرسی - آمیکو"),
        FleetVehicle("69726", "کمپرسی - آمیکو"),
        FleetVehicle("62596", "کمپرسی - آمیکو", "محمد ایلخانی"),

        // کمپرسی تریلی - اف هاش
        FleetVehicle("36135", "کمپرسی تریلی - اف هاش", "میثم بیگی"),

        // پمپ سوار
        FleetVehicle("74432", "پمپ سوار"),
        FleetVehicle("82226", "پمپ سوار"),
        FleetVehicle("24476", "پمپ سوار", "نایب سلیمانی"),
        FleetVehicle("74259", "پمپ سوار", "جواد محمدی نیا"),
        FleetVehicle("68767", "پمپ سوار"),

        // پمپ یدک
        FleetVehicle("75128", "پمپ یدک", "امیر حسنی"),
        FleetVehicle("82237", "پمپ یدک"),
        FleetVehicle("54277", "پمپ یدک", "علی قوی بدن"),

        // تدارکات / تعمیرگاه (وانت‌ها)
        FleetVehicle("97828", "نیسان - تدارکات", "کاوه بهرامی"),
        FleetVehicle("73574", "پراید - تدارکات", "قاسم انصاری"),
        FleetVehicle("82818", "پراید - تعمیرگاه", "سعید انصاری"),

        // میکسر - ایویکو
        FleetVehicle("64266", "میکسر - ایویکو", "علی اصغر دیندار"),
        FleetVehicle("99716", "میکسر - ایویکو", "ایرج رزمجو"),
        FleetVehicle("65517", "میکسر - ایویکو", "سلیم مقدوری"),
        FleetVehicle("21399", "میکسر - ایویکو", "محمد رثایی"),
        FleetVehicle("69243", "میکسر - ایویکو", "آرمان منصف"),
        FleetVehicle("21971", "میکسر - ایویکو", "حمیدرضا عبدی"),
        FleetVehicle("67788", "میکسر - ایویکو", "مرتضی دباغی"),
        FleetVehicle("74613", "میکسر - ایویکو", "پیمان اعتصامی"),
        FleetVehicle("64261", "میکسر - ایویکو", "حشمت اسدی"),
        FleetVehicle("66373", "میکسر - ایویکو", "حمید سوری"),
        FleetVehicle("24593", "میکسر - ایویکو", "رضا پروهان"),
        FleetVehicle("74428", "میکسر - ایویکو", "رضا جعفری"),
        FleetVehicle("64265", "میکسر - ایویکو", "محسن آزاد"),
        FleetVehicle("65271", "میکسر - ایویکو", "برهان تغتمش"),

        // میکسر - آمیکو
        FleetVehicle("58913", "میکسر - آمیکو", "قاسم بخشی"),
        FleetVehicle("67512", "میکسر - آمیکو", "سردار شکوری"),
        FleetVehicle("21848", "میکسر - آمیکو", "محسن قنبری"),
        FleetVehicle("58916", "میکسر - آمیکو", "حسین شهبازی"),
        FleetVehicle("47769", "میکسر - آمیکو", "هرمز محمودیان"),
        FleetVehicle("24761", "میکسر - آمیکو", "محمد قنبری"),
        FleetVehicle("47725", "میکسر - آمیکو", "محمود علیپور"),
        FleetVehicle("21855", "میکسر - آمیکو"),
        FleetVehicle("22416", "میکسر - آمیکو", "عباس عزیزی فرد"),
        FleetVehicle("16612", "میکسر - آمیکو", "داود لطفی"),
        FleetVehicle("21859", "میکسر - آمیکو", "بخشعلی شمس"),
        FleetVehicle("38827", "میکسر - آمیکو"),
        FleetVehicle("47546", "میکسر - آمیکو"),
        FleetVehicle("21411", "میکسر - آمیکو"),
        FleetVehicle("38814", "میکسر - آمیکو", "ابراهیم زرکر")
    )

    /** Builds the full list of [PersonEntity] rows to insert on first run. The plate number is
     *  the display name (what a guard actually recognizes at the gate); the usual driver, if
     *  known, goes in extraInfo as a helpful reminder — not as a hard link, since a different
     *  driver can always take the same vehicle out on a given day. */
    fun buildInitialRoster(): List<PersonEntity> = vehicles.map { v ->
        PersonEntity(
            name = "پلاک ${v.plate.toPersianDigitsInString()}",
            type = PersonType.MACHINERY.name,
            group = v.groupLabel,
            extraInfo = v.driverName
        )
    }
}
