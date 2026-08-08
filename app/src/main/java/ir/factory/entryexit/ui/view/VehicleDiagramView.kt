package ir.factory.entryexit.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import ir.factory.entryexit.R
import ir.factory.entryexit.data.InspectionCatalog
import ir.factory.entryexit.data.InspectionPartResult
import ir.factory.entryexit.data.LabelSide
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.data.PartStatus
import ir.factory.entryexit.data.PartZone
import ir.factory.entryexit.data.VehicleOutline
import kotlin.math.max

/**
 * The touch-based replacement for the old switch-per-row checklist: a schematic top-down
 * vehicle body (see [VehicleOutline]) with one tappable region per [PartZone]. Single tap
 * cycles OK -> WARN -> BAD -> OK; a long-press jumps straight to BAD for an obviously broken
 * part. Pinch and drag work directly on the drawing itself, no on-screen +/- buttons, matching
 * how the paper-prototype in chat was approved.
 *
 * Two modes:
 * - [Mode.INTERACTIVE]: what a guard fills out on [ir.factory.entryexit.ui.InspectionFormActivity].
 * - [Mode.HEATMAP]: read-only, colors zones by defect frequency instead of current status, for
 *   [ir.factory.entryexit.ui.FleetHeatmapActivity]. No taps do anything in this mode.
 */
class VehicleDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { INTERACTIVE, HEATMAP }

    var mode: Mode = Mode.INTERACTIVE
        private set

    private var category: MachineryCategory = MachineryCategory.LOGISTICS
    private var outline: VehicleOutline = VehicleOutline.SEDAN
    private var zones: List<PartZone> = emptyList()

    private val statuses = mutableMapOf<String, PartStatus>()
    private val notes = mutableMapOf<String, String?>()
    private val photos = mutableMapOf<String, String?>()
    private val stillOpenFromLastTime = mutableSetOf<String>()

    private var heatCounts: Map<String, Int> = emptyMap()
    private var heatMax: Int = 1

    /** Fired on every status change from a tap/long-press (not from [setup]). */
    var onStatusChanged: ((name: String, status: PartStatus) -> Unit)? = null

    /** Fired when the small note/photo badge on an already-flagged part is tapped — the
     *  Activity is expected to show a dialog and call [setNoteAndPhoto] with the result. */
    var onNoteBadgeTapped: ((name: String) -> Unit)? = null

    // ---- colors (reuse the existing brand palette, no new resources needed) ----
    private val colorOk = ContextCompat.getColor(context, R.color.status_gray_bg)
    private val colorOkBorder = ContextCompat.getColor(context, R.color.concrete_300)
    private val colorWarn = ContextCompat.getColor(context, R.color.safety_amber)
    private val colorBad = ContextCompat.getColor(context, R.color.danger_red)
    private val colorTextNormal = ContextCompat.getColor(context, R.color.concrete_700)
    private val colorTextOnAccent = ContextCompat.getColor(context, R.color.concrete_900)
    private val colorOutline = ContextCompat.getColor(context, R.color.concrete_500)
    private val colorRecurringRing = ContextCompat.getColor(context, R.color.danger_red)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = colorOutline
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = colorRecurringRing
    }
    private val badgeIcon = ContextCompat.getDrawable(context, R.drawable.ic_photo)

    // ---- transform: base "fit width" scale, then user pinch/pan on top of it ----
    private var baseScale = 1f
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private val totalMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private fun recomputeBaseScale() {
        if (width == 0) return
        baseScale = width / InspectionCatalog.DIAGRAM_CANVAS_WIDTH
        clampPan()
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val scale = baseScale * userScale
        totalMatrix.reset()
        totalMatrix.postScale(scale, scale)
        totalMatrix.postTranslate(-panX, -panY)
        totalMatrix.invert(inverseMatrix)
    }

    private fun clampPan() {
        val scale = baseScale * userScale
        val contentW = InspectionCatalog.DIAGRAM_CANVAS_WIDTH * scale
        val contentH = InspectionCatalog.DIAGRAM_CANVAS_HEIGHT * scale
        val maxPanX = max(0f, contentW - width)
        val maxPanY = max(0f, contentH - height)
        panX = panX.coerceIn(0f, maxPanX)
        panY = panY.coerceIn(0f, maxPanY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeBaseScale()
    }

    /** Loads a category's diagram for filling out this week's inspection. [initial] pre-fills
     *  status/note/photo (e.g. re-opening a draft); [stillOpen] is the set of part names that
     *  were WARN/BAD on the previous inspection and never got marked repaired — shown as a
     *  reminder ring even before the guard has tapped anything this time. */
    fun setup(
        category: MachineryCategory,
        initial: List<InspectionPartResult> = emptyList(),
        stillOpen: Set<String> = emptySet()
    ) {
        mode = Mode.INTERACTIVE
        this.category = category
        zones = InspectionCatalog.zonesFor(category)
        outline = InspectionCatalog.outlineFor(category)
        statuses.clear(); notes.clear(); photos.clear()
        for (zone in zones) statuses[zone.name] = PartStatus.OK
        for (part in initial) {
            statuses[part.name] = part.status
            notes[part.name] = part.note
            photos[part.name] = part.photoUri
        }
        stillOpenFromLastTime.clear()
        stillOpenFromLastTime.addAll(stillOpen)
        userScale = 1f; panX = 0f; panY = 0f
        recomputeBaseScale()
        invalidate()
    }

    /** Read-only fleet view: colors each zone by how often it showed up as a defect in
     *  [counts] (part name -> occurrence count) instead of by live status. */
    fun setHeatmapData(category: MachineryCategory, counts: Map<String, Int>) {
        mode = Mode.HEATMAP
        this.category = category
        zones = InspectionCatalog.zonesFor(category)
        outline = InspectionCatalog.outlineFor(category)
        heatCounts = counts
        heatMax = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
        userScale = 1f; panX = 0f; panY = 0f
        recomputeBaseScale()
        invalidate()
    }

    fun currentResults(): List<InspectionPartResult> =
        statuses.map { (name, status) ->
            InspectionPartResult(name = name, status = status, note = notes[name], photoUri = photos[name])
        }

    fun setNoteAndPhoto(name: String, note: String?, photoUri: String?) {
        notes[name] = note
        photos[name] = photoUri
        invalidate()
    }

    // ---- gestures ----

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScale = (userScale * detector.scaleFactor).coerceIn(1f, 4f)
            clampPan(); recomputeMatrix(); invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX += distanceX
            panY += distanceY
            clampPan(); recomputeMatrix(); invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode != Mode.INTERACTIVE) return false
            handleTap(e.x, e.y, forceBad = false)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.INTERACTIVE) return
            handleTap(e.x, e.y, forceBad = true)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private val badgeRect = RectF()

    private fun handleTap(screenX: Float, screenY: Float, forceBad: Boolean) {
        val pt = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(pt)
        val (x, y) = pt[0] to pt[1]

        // Topmost-drawn zone wins, so search back-to-front.
        for (zone in zones.asReversed()) {
            val rect = RectF(zone.x, zone.y, zone.x + zone.w, zone.y + zone.h)
            if (!rect.contains(x, y)) continue

            val status = statuses[zone.name] ?: PartStatus.OK
            if (status != PartStatus.OK) {
                badgeRectFor(zone, badgeRect)
                if (badgeRect.contains(x, y)) {
                    onNoteBadgeTapped?.invoke(zone.name)
                    return
                }
            }

            val newStatus = if (forceBad) PartStatus.BAD else when (status) {
                PartStatus.OK -> PartStatus.WARN
                PartStatus.WARN -> PartStatus.BAD
                PartStatus.BAD -> PartStatus.OK
            }
            statuses[zone.name] = newStatus
            if (newStatus == PartStatus.OK) {
                notes.remove(zone.name); photos.remove(zone.name)
            }
            onStatusChanged?.invoke(zone.name, newStatus)
            invalidate()
            return
        }
    }

    private fun badgeRectFor(zone: PartZone, out: RectF) {
        out.set(zone.x + zone.w - 18f, zone.y - 6f, zone.x + zone.w + 6f, zone.y + 18f)
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || zones.isEmpty()) return

        canvas.save()
        canvas.concat(totalMatrix)

        drawWheels(canvas)
        drawOutline(canvas)
        for (zone in zones) drawZone(canvas, zone)

        canvas.restore()
    }

    private fun drawWheels(canvas: Canvas) {
        fillPaint.color = ContextCompat.getColor(context, R.color.concrete_200)
        val w = InspectionCatalog.DIAGRAM_CANVAS_WIDTH
        val positions = listOf(150f to 90f, (w - 150f) to 90f, 150f to 640f, (w - 150f) to 640f)
        for ((cx, cy) in positions) {
            canvas.drawOval(cx - 16f, cy - 10f, cx + 16f, cy + 10f, fillPaint)
            canvas.drawOval(cx - 16f, cy - 10f, cx + 16f, cy + 10f, outlinePaint)
        }
    }

    private fun drawOutline(canvas: Canvas) {
        val w = InspectionCatalog.DIAGRAM_CANVAS_WIDTH
        val cabRect = RectF(120f, 14f, w - 120f, 104f)
        canvas.drawRoundRect(cabRect, 18f, 18f, outlinePaint)
        when (outline) {
            VehicleOutline.MIXER -> {
                canvas.drawOval(w / 2f - 105f, 420f - 290f, w / 2f + 105f, 420f + 290f, outlinePaint)
            }
            VehicleOutline.PUMP -> {
                canvas.drawRoundRect(130f, 120f, w - 130f, 700f, 14f, 14f, outlinePaint)
            }
            VehicleOutline.DUMP -> {
                canvas.drawRoundRect(115f, 120f, w - 115f, 700f, 4f, 4f, outlinePaint)
                canvas.drawLine(115f, 260f, w - 115f, 260f, outlinePaint)
            }
            VehicleOutline.SEDAN -> {
                val path = Path().apply {
                    moveTo(140f, 14f)
                    quadTo(120f, 14f, 120f, 50f)
                    lineTo(120f, 160f)
                    quadTo(120f, 190f, 155f, 195f)
                    lineTo(w - 155f, 195f)
                    quadTo(w - 120f, 190f, w - 120f, 160f)
                    lineTo(w - 120f, 50f)
                    quadTo(w - 120f, 14f, w - 140f, 14f)
                    close()
                }
                canvas.drawPath(path, outlinePaint)
                canvas.drawRoundRect(150f, 195f, w - 150f, 520f, 20f, 20f, outlinePaint)
            }
        }
    }

    private fun drawZone(canvas: Canvas, zone: PartZone) {
        val rect = RectF(zone.x, zone.y, zone.x + zone.w, zone.y + zone.h)

        if (mode == Mode.HEATMAP) {
            val count = heatCounts[zone.name] ?: 0
            val intensity = (count.toFloat() / heatMax).coerceIn(0f, 1f)
            fillPaint.color = blend(colorOk, colorBad, intensity)
            canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
            strokePaint.color = colorOutline
            canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
            if (zone.showLabel) {
                drawLabel(canvas, zone, rect, if (count > 0) "${zone.name} ($count)" else zone.name, colorTextNormal)
            }
            return
        }

        val status = statuses[zone.name] ?: PartStatus.OK
        val (fill, textColor) = when (status) {
            PartStatus.OK -> colorOk to colorTextNormal
            PartStatus.WARN -> colorWarn to colorTextOnAccent
            PartStatus.BAD -> colorBad to Color.WHITE
        }
        fillPaint.color = fill
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        strokePaint.color = if (status == PartStatus.OK) colorOkBorder else fill
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)

        // Reminder ring: this part was already open last time and hasn't been retouched yet
        // this session (still shows OK because setup() only pre-fills status from THIS week's
        // draft, not automatically from last week — the guard has to actually look at it).
        if (zone.name in stillOpenFromLastTime && status == PartStatus.OK) {
            canvas.drawRoundRect(
                RectF(rect.left - 3f, rect.top - 3f, rect.right + 3f, rect.bottom + 3f),
                8f, 8f, ringPaint
            )
        }

        if (zone.showLabel) drawLabel(canvas, zone, rect, zone.name, textColor)

        if (status != PartStatus.OK) {
            badgeRectFor(zone, badgeRect)
            badgeIcon?.setBounds(badgeRect.left.toInt(), badgeRect.top.toInt(), badgeRect.right.toInt(), badgeRect.bottom.toInt())
            badgeIcon?.setTint(if (notes[zone.name].isNullOrBlank() && photos[zone.name].isNullOrBlank()) colorOutline else colorTextOnAccent)
            badgeIcon?.draw(canvas)
        }
    }

    private fun drawLabel(canvas: Canvas, zone: PartZone, rect: RectF, text: String, color: Int) {
        textPaint.color = color
        when (zone.side) {
            LabelSide.CENTER -> {
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, rect.centerX(), rect.centerY() + 7f, textPaint)
            }
            LabelSide.LEFT -> {
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(text, rect.left - 8f, rect.centerY() + 7f, textPaint)
            }
            LabelSide.RIGHT -> {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(text, rect.right + 8f, rect.centerY() + 7f, textPaint)
            }
        }
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
