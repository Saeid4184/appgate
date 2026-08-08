package ir.factory.entryexit.data

/**
 * The fixed part checklist for the weekly "وضعیت ظاهری" (visual/exterior) machinery inspection,
 * copied verbatim (same items, same order) from the security team's original Excel workbook
 * (سه شیت: میکسرها / پمپ‌ها / کمپرسی-تریلی-تدارکات) so the digital checklist and the exported
 * report stay identical to what the team already knows and reports up the chain with.
 *
 * [MachineryCategory.MIXER] gets its own list (adds شوت/قیف میکسر + بغل‌نویسی‌ها + چراغ سقفی).
 * [MachineryCategory.CONCRETE_PUMP] uses the "پمپ‌ها" list.
 * [MachineryCategory.DUMP_TRUCK] and [MachineryCategory.LOGISTICS] share one list, exactly as
 * they share a single sheet ("کمپرسی-تریلی-تدارکات") in the original workbook.
 */
object InspectionCatalog {

    private val commonCore = listOf(
        "داخلی", "کارواش", "چراغ های عقب", "چراغ های جلو", "آینه چپ", "آینه راست",
        "رکاب چپ", "رکاب راست", "لپی چپ", "لپی راست", "سپر جلو", "سپر عقب"
    )

    private val plateAndGlass = listOf(
        "درب باک", "پلاک عقب", "پلاک سوم", "پلاک جلو", "وضعیت شبرنگ ها",
        "شیشه جلو", "شیشه راست", "شیشه چپ", "کاپوت جلو", "آرم کاپوت جلو"
    )

    private val handlesAndFenders = listOf(
        "سوئیچ", "دستگیره راست", "دستگیره چپ", "گلگیر عقب", "گلگیر جلو", "پروژکتور عقب"
    )

    /** میکسرها (33 قطعه) */
    val mixerParts: List<String> =
        commonCore + listOf("قاب باطری") + plateAndGlass +
            listOf("شوت میکسر", "قیف میکسر", "بغل نویسی ها") +
            handlesAndFenders + listOf("چراغ سقفی")

    /** پمپ ها (30 قطعه) — بدون قاب باطری، شوت/قیف میکسر و چراغ سقفی */
    val pumpParts: List<String> =
        commonCore + plateAndGlass + listOf("بغل نویسی ها") + handlesAndFenders

    /** کمپرسی-تریلی-تدارکات (29 قطعه) — بدون بغل‌نویسی‌ها و چراغ سقفی */
    val compressorTrailerLogisticsParts: List<String> =
        commonCore + listOf("قاب باطری") + plateAndGlass + handlesAndFenders

    fun partsFor(category: MachineryCategory): List<String> = when (category) {
        MachineryCategory.MIXER -> mixerParts
        MachineryCategory.CONCRETE_PUMP -> pumpParts
        MachineryCategory.DUMP_TRUCK, MachineryCategory.LOGISTICS -> compressorTrailerLogisticsParts
    }

    /** Matches the original sheet names, used as export sheet titles. */
    fun sheetNameFor(category: MachineryCategory): String = when (category) {
        MachineryCategory.MIXER -> "میکسرها"
        MachineryCategory.CONCRETE_PUMP -> "پمپ ها"
        MachineryCategory.DUMP_TRUCK, MachineryCategory.LOGISTICS -> "کمپرسی-تریلی-تدارکات"
    }

    /** Three items have no sensible spot on the body silhouette (they're not something you
     *  point at), so [VehicleDiagramView] shows them as a small checklist next to the diagram
     *  instead of a tappable zone. Every other name in [partsFor] appears in [zonesFor]. */
    val nonSpatialParts = listOf("داخلی", "کارواش", "سوئیچ")

    /** Logical coordinate space every [PartZone] below is expressed in; [VehicleDiagramView]
     *  fits this to whatever the device screen actually is, then lets the user pinch/pan
     *  further from there. Kept as plain floats (not dp) since it's a fixed drawing, not a
     *  layout. */
    const val DIAGRAM_CANVAS_WIDTH = 460f
    const val DIAGRAM_CANVAS_HEIGHT = 900f

    /** Shared across all four categories — same body positions regardless of vehicle type.
     *  A few names (headlights, fenders) legitimately live on both sides of the vehicle but
     *  are a single checklist line item, so they appear twice here with the second occurrence
     *  marked [PartZone.showLabel] = false: two tap targets, one underlying part name. */
    private val commonZones = listOf(
        PartZone("سپر جلو", 140f, 10f, 180f, 16f, LabelSide.CENTER),
        PartZone("چراغ های جلو", 122f, 32f, 60f, 34f, LabelSide.LEFT),
        PartZone("چراغ های جلو", 278f, 32f, 60f, 34f, LabelSide.RIGHT, showLabel = false),
        PartZone("کاپوت جلو", 190f, 32f, 80f, 34f, LabelSide.CENTER),
        PartZone("آرم کاپوت جلو", 205f, 72f, 50f, 20f, LabelSide.CENTER),
        PartZone("شیشه جلو", 125f, 100f, 210f, 40f, LabelSide.CENTER),
        PartZone("آینه چپ", 55f, 104f, 55f, 32f, LabelSide.LEFT),
        PartZone("آینه راست", 350f, 104f, 55f, 32f, LabelSide.RIGHT),
        PartZone("شیشه چپ", 115f, 150f, 55f, 34f, LabelSide.LEFT),
        PartZone("شیشه راست", 290f, 150f, 55f, 34f, LabelSide.RIGHT),
        PartZone("دستگیره چپ", 115f, 196f, 55f, 30f, LabelSide.LEFT),
        PartZone("دستگیره راست", 290f, 196f, 55f, 30f, LabelSide.RIGHT),
        PartZone("گلگیر جلو", 115f, 238f, 55f, 30f, LabelSide.LEFT),
        PartZone("گلگیر جلو", 290f, 238f, 55f, 30f, LabelSide.RIGHT, showLabel = false),
        PartZone("رکاب چپ", 115f, 280f, 55f, 100f, LabelSide.LEFT),
        PartZone("رکاب راست", 290f, 280f, 55f, 100f, LabelSide.RIGHT),
        PartZone("درب باک", 290f, 392f, 55f, 30f, LabelSide.RIGHT),
        PartZone("لپی چپ", 115f, 392f, 55f, 120f, LabelSide.LEFT),
        PartZone("لپی راست", 290f, 432f, 55f, 120f, LabelSide.RIGHT),
        PartZone("وضعیت شبرنگ ها", 150f, 570f, 160f, 26f, LabelSide.CENTER),
        PartZone("پلاک سوم", 165f, 606f, 130f, 26f, LabelSide.CENTER),
        PartZone("گلگیر عقب", 115f, 644f, 55f, 30f, LabelSide.LEFT),
        PartZone("گلگیر عقب", 290f, 644f, 55f, 30f, LabelSide.RIGHT, showLabel = false),
        PartZone("پروژکتور عقب", 165f, 684f, 130f, 22f, LabelSide.CENTER),
        PartZone("پلاک عقب", 165f, 716f, 130f, 26f, LabelSide.CENTER),
        PartZone("چراغ های عقب", 122f, 752f, 60f, 26f, LabelSide.LEFT),
        PartZone("چراغ های عقب", 278f, 752f, 60f, 26f, LabelSide.RIGHT, showLabel = false),
        PartZone("سپر عقب", 140f, 788f, 180f, 16f, LabelSide.CENTER)
    )

    private val mixerExtraZones = listOf(
        PartZone("قاب باطری", 55f, 196f, 45f, 26f, LabelSide.LEFT),
        PartZone("شوت میکسر", 195f, 320f, 30f, 180f, LabelSide.CENTER),
        PartZone("قیف میکسر", 235f, 320f, 30f, 180f, LabelSide.CENTER),
        PartZone("بغل نویسی ها", 170f, 510f, 120f, 26f, LabelSide.CENTER),
        PartZone("چراغ سقفی", 210f, 2f, 40f, 8f, LabelSide.CENTER)
    )

    private val pumpExtraZones = listOf(
        PartZone("بغل نویسی ها", 170f, 300f, 120f, 26f, LabelSide.CENTER)
    )

    private val batteryFrameZone = listOf(
        PartZone("قاب باطری", 55f, 196f, 45f, 26f, LabelSide.LEFT)
    )

    /** Body outline drawn behind the zones; the shape is purely cosmetic (helps a guard
     *  recognize "yes, this is my truck") and never affects hit-testing. [MachineryCategory
     *  .LOGISTICS] and [MachineryCategory.DUMP_TRUCK] share the exact same part list, so they
     *  only differ here, by which silhouette [VehicleDiagramView] draws. */
    fun outlineFor(category: MachineryCategory): VehicleOutline = when (category) {
        MachineryCategory.MIXER -> VehicleOutline.MIXER
        MachineryCategory.CONCRETE_PUMP -> VehicleOutline.PUMP
        MachineryCategory.DUMP_TRUCK -> VehicleOutline.DUMP
        MachineryCategory.LOGISTICS -> VehicleOutline.SEDAN
    }

    /** Every tappable body zone for a category. Names here, plus [nonSpatialParts], always
     *  cover exactly the set in [partsFor]. */
    fun zonesFor(category: MachineryCategory): List<PartZone> = when (category) {
        MachineryCategory.MIXER -> commonZones + mixerExtraZones
        MachineryCategory.CONCRETE_PUMP -> commonZones + pumpExtraZones
        MachineryCategory.DUMP_TRUCK -> commonZones + batteryFrameZone
        MachineryCategory.LOGISTICS -> commonZones + batteryFrameZone
    }
}

/** Where a zone's name label is drawn relative to its tap rectangle: outside the vehicle body
 *  to the left/right (narrow parts, e.g. a mirror), or centered inside the rectangle itself
 *  (wide parts, e.g. a bumper, that comfortably fit the text). */
enum class LabelSide { LEFT, RIGHT, CENTER }

/** Which cosmetic silhouette [VehicleDiagramView] draws behind the tap zones. */
enum class VehicleOutline { MIXER, PUMP, DUMP, SEDAN }

/** One tappable region on the vehicle diagram, in [InspectionCatalog.DIAGRAM_CANVAS_WIDTH] x
 *  [InspectionCatalog.DIAGRAM_CANVAS_HEIGHT] logical units. [name] must match a value from
 *  [InspectionCatalog.partsFor] — several zones may legitimately share one [name] (e.g. both
 *  headlights are one checklist line), in which case only the first should [showLabel]. */
data class PartZone(
    val name: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val side: LabelSide,
    val showLabel: Boolean = true
)

/** A part's condition as tapped on [VehicleDiagramView]: cycles OK -> WARN -> BAD -> OK on
 *  single tap, or jumps straight to BAD on long-press for an obviously broken part. */
enum class PartStatus { OK, WARN, BAD }

/** One part's result on the form. [status] is the source of truth; [ok] is kept as a computed
 *  property (not a stored field) so every existing call site that reads [InspectionEntity
 *  .partsJson]'s legacy "ok" boolean (e.g. ReportActivity's Excel export) keeps working
 *  unchanged — WARN and BAD both serialize to "ok": false, only OK serializes to true.
 *  [note]/[photoUri] are optional documentation for a WARN/BAD part (photos are local-only, a
 *  content:// URI, same reasoning as PersonEntity.imageUri — see Repository
 *  .updatePersonImage). [recurringSinceTimestamp] is copied forward at submission time,
 *  never user-editable, set when the same part was already non-OK on this vehicle's previous
 *  inspection, so the diagram can flag "still broken from last week" instead of it looking
 *  like a fresh defect. [repairedAt] is set later, from the open-defects list, once someone
 *  closes the loop. */
data class InspectionPartResult(
    val name: String,
    val status: PartStatus = PartStatus.OK,
    val note: String? = null,
    val photoUri: String? = null,
    val recurringSinceTimestamp: Long? = null,
    val repairedAt: Long? = null
) {
    val ok: Boolean get() = status == PartStatus.OK
}
