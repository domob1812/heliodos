package eu.domob.heliodos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.PreferenceManager
import io.github.cosinekitty.astronomy.Body
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PATH_POINTS = 50
private const val HOUR_TICK_LENGTH = 30f
private const val HOUR_TICK_TANGENT_DELTA_MS = 30_000L
private const val HOUR_TICK_LABEL_GAP = 6f
private const val CROSSHAIR_INNER_GAP_DP = 4f
private const val CROSSHAIR_OUTER_RADIUS_DP = 14f
private const val CROSSHAIR_OUTLINE_STROKE_DP = 2.6f
private const val CROSSHAIR_STROKE_DP = 1.2f
private const val CROSSHAIR_LABEL_GAP_DP = 8f
private const val CROSSHAIR_LABEL_PADDING_X_DP = 5f
private const val CROSSHAIR_LABEL_PADDING_Y_DP = 4f
private const val CROSSHAIR_LABEL_LINE_GAP_DP = 2f
private const val CROSSHAIR_LABEL_TEXT_DP = 13f

/**
 * Computes the magnetic (azimuth, altitude) in radians at the view centre.
 *
 * In CameraProjection's symmetric centre-crop pinhole model, the view centre
 * (width/2, height/2) always maps to the camera optical axis (0, 0, 1), i.e.
 * device (0, 0, -1). Since project() rotates world -> device by R^T, rotating
 * back by R gives world = (-R[2], -R[5], -R[8]), matching the drawn paths.
 */
private fun viewCenterDirection(R: FloatArray): BodyPosition.AzimuthAltitude {
    val altitude = asin((-R[8]).toDouble().coerceIn(-1.0, 1.0))
    var azimuth = atan2((-R[2]).toDouble(), (-R[5]).toDouble())
    if (azimuth < 0.0) {
        azimuth += 2.0 * Math.PI
    }
    return BodyPosition.AzimuthAltitude(azimuth, altitude)
}

private fun normalizeAzimuthDegrees(degrees: Double): Double {
    val wrapped = ((degrees % 360.0) + 360.0) % 360.0
    val roundedTenths = Math.round(wrapped * 10.0) % 3600L
    return roundedTenths / 10.0
}

private fun normalizeZeroDegrees(degrees: Double): Double {
    return if (abs(degrees) < 0.05) 0.0 else degrees
}

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cameraFeedView: CameraFeedView? = null
    private var rotationMatrix: FloatArray? = null
    private var bodyPositionSun: BodyPosition? = null
    private var bodyPositionMoon: BodyPosition? = null
    private var referenceTime: Long = 0
    private var observerLatitude: Double = 0.0
    private var magneticDeclinationRad: Double? = null

    private var prefsLoaded = false
    private val pathEnabled = mutableMapOf<String, Boolean>()
    private val pathColors = mutableMapOf<String, Int>()
    private var showCrosshair = false
    private var crosshairMagnetic = false

    private val density = resources.displayMetrics.density

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val paintBackground = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    private val paintLabel = Paint().apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val paintCrosshairOutline = Paint().apply {
        color = Color.parseColor("#B3000000")
        strokeWidth = CROSSHAIR_OUTLINE_STROKE_DP * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val paintCrosshair = Paint().apply {
        color = Color.WHITE
        strokeWidth = CROSSHAIR_STROKE_DP * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val paintCrosshairText = Paint().apply {
        color = Color.WHITE
        textSize = CROSSHAIR_LABEL_TEXT_DP * density
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private fun drawBodyPath(canvas: Canvas, bp: BodyPosition, time: Long, thickness: Float, color: Int) {
        val riseSet = bp.getRiseSet(time)

        val startTime: Long
        val endTime: Long
        var isLoop = false
        if (riseSet != null) {
            startTime = riseSet.rise
            endTime = riseSet.set
        } else {
            val pos = bp.getPosition(time)
            if (pos.altitude < 0) {
                return
            }
            startTime = time - 12 * 3600 * 1000
            endTime = time + 12 * 3600 * 1000
            isLoop = true
        }

        val paint = Paint().apply {
            this.color = color
            this.strokeWidth = thickness
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val points = Array(PATH_POINTS) { i ->
            val t = startTime + (endTime - startTime) * i / (PATH_POINTS - 1)
            val pos = bp.getPositionMagnetic(t)
            project(pos.azimuth, pos.altitude)
        }

        if (isLoop) {
            points[points.lastIndex] = points[0]
        }

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.first, p1.second, p2.first, p2.second, paint)
            }
        }
    }

    private fun drawHourTicks(canvas: Canvas, bp: BodyPosition, time: Long, color: Int) {
        val ticks = bp.getFullHourTimestamps(time)
        if (ticks.isEmpty()) return

        val paintTick = Paint().apply {
            this.color = color
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        paintLabel.color = color

        val halfLen = HOUR_TICK_LENGTH / 2f
        val fontMetrics = paintLabel.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val textOffsetY = textHeight / 2 - fontMetrics.bottom
        val padding = 4f
        val boxHalfHeight = textHeight / 2 + padding
        val labelOffset = halfLen + boxHalfHeight + HOUR_TICK_LABEL_GAP

        for (tick in ticks) {
            val center = bp.getPositionMagnetic(tick.millis)
            val centerPt = project(center.azimuth, center.altitude) ?: continue

            val before = bp.getPositionMagnetic(tick.millis - HOUR_TICK_TANGENT_DELTA_MS)
            val beforePt = project(before.azimuth, before.altitude) ?: continue

            val after = bp.getPositionMagnetic(tick.millis + HOUR_TICK_TANGENT_DELTA_MS)
            val afterPt = project(after.azimuth, after.altitude) ?: continue

            val dx = afterPt.first - beforePt.first
            val dy = afterPt.second - beforePt.second
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.001f) continue

            val nx = -dy / len
            val ny = dx / len

            val x1 = centerPt.first - nx * halfLen
            val y1 = centerPt.second - ny * halfLen
            val x2 = centerPt.first + nx * halfLen
            val y2 = centerPt.second + ny * halfLen

            canvas.drawLine(x1, y1, x2, y2, paintTick)

            val labelX = centerPt.first + nx * labelOffset
            val labelY = centerPt.second + ny * labelOffset

            val labelText = "${tick.localHour}h"
            val textWidth = paintLabel.measureText(labelText)

            canvas.save()
            canvas.translate(labelX, labelY)
            canvas.rotate(-Math.toDegrees(Math.atan2(nx.toDouble(), ny.toDouble())).toFloat())

            canvas.drawRect(
                -textWidth / 2 - padding,
                -boxHalfHeight,
                textWidth / 2 + padding,
                boxHalfHeight,
                paintBackground
            )
            canvas.drawText(labelText, 0f, textOffsetY, paintLabel)

            canvas.restore()
        }
    }

    fun setRotationMatrix(matrix: FloatArray) {
        rotationMatrix = matrix
        invalidate()
    }

    fun setPositionAndTime(latitude: Double, longitude: Double, altitude: Double, time: Long) {
        val sun = BodyPosition(latitude, longitude, altitude, Body.Sun)
        bodyPositionSun = sun
        bodyPositionMoon = BodyPosition(latitude, longitude, altitude, Body.Moon)
        referenceTime = time
        observerLatitude = latitude
        magneticDeclinationRad = sun.getMagneticDeclination(time)
        invalidate()
    }

    fun clearLocation() {
        bodyPositionSun = null
        bodyPositionMoon = null
        magneticDeclinationRad = null
        invalidate()
    }

    fun reloadPreferences() {
        reloadPreferencesInternal()
        invalidate()
    }

    private fun reloadPreferencesInternal() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        pathEnabled["solstice_high"] = prefs.getBoolean("show_solstice_high", true)
        pathEnabled["solstice_low"] = prefs.getBoolean("show_solstice_low", true)
        pathEnabled["equinox"] = prefs.getBoolean("show_equinox", true)
        pathEnabled["sun_current"] = prefs.getBoolean("show_sun_current", true)
        pathEnabled["moon_current"] = prefs.getBoolean("show_moon_current", false)
        pathColors["solstice_high"] = prefs.getInt("color_solstice_high", Color.BLUE)
        pathColors["solstice_low"] = prefs.getInt("color_solstice_low", Color.RED)
        pathColors["equinox"] = prefs.getInt("color_equinox", Color.GREEN)
        pathColors["sun_current"] = prefs.getInt("color_sun_current", Color.YELLOW)
        pathColors["moon_current"] = prefs.getInt("color_moon_current", Color.WHITE)
        showCrosshair = prefs.getBoolean("show_crosshair", false)
        crosshairMagnetic = prefs.getBoolean("crosshair_magnetic", false)
    }

    private fun project(azimuth: Double, altitude: Double): Pair<Float, Float>? {
        val r = cos(altitude)
        val worldX = (sin(azimuth) * r).toFloat()
        val worldY = (cos(azimuth) * r).toFloat()
        val worldZ = sin(altitude).toFloat()

        val camProjection = cameraFeedView?.projection ?: return null
        val R = rotationMatrix ?: return null

        val deviceX = R[0] * worldX + R[3] * worldY + R[6] * worldZ
        val deviceY = R[1] * worldX + R[4] * worldY + R[7] * worldZ
        val deviceZ = R[2] * worldX + R[5] * worldY + R[8] * worldZ

        val cameraX = deviceX
        val cameraY = -deviceY
        val cameraZ = -deviceZ

        camProjection.setViewSize(width, height)
        return camProjection.project(cameraX, cameraY, cameraZ)
    }

    private fun drawCrosshairArms(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerGap: Float,
        outerRadius: Float,
        paint: Paint
    ) {
        canvas.drawLine(cx - outerRadius, cy, cx - innerGap, cy, paint)
        canvas.drawLine(cx + innerGap, cy, cx + outerRadius, cy, paint)
        canvas.drawLine(cx, cy - outerRadius, cx, cy - innerGap, paint)
        canvas.drawLine(cx, cy + innerGap, cx, cy + outerRadius, paint)
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        val innerGap = CROSSHAIR_INNER_GAP_DP * density
        val outerRadius = CROSSHAIR_OUTER_RADIUS_DP * density

        drawCrosshairArms(canvas, cx, cy, innerGap, outerRadius, paintCrosshairOutline)
        drawCrosshairArms(canvas, cx, cy, innerGap, outerRadius, paintCrosshair)

        val matrix = rotationMatrix ?: return
        val centerPos = viewCenterDirection(matrix)

        val azRad = if (crosshairMagnetic) {
            centerPos.azimuth
        } else {
            val declination = magneticDeclinationRad ?: return
            centerPos.azimuth + declination
        }

        val azDeg = normalizeAzimuthDegrees(Math.toDegrees(azRad))
        val altDeg = normalizeZeroDegrees(Math.toDegrees(centerPos.altitude))

        val azText = context.getString(
            if (crosshairMagnetic) R.string.crosshair_azimuth_magnetic else R.string.crosshair_azimuth_true,
            azDeg
        )
        val altText = context.getString(R.string.crosshair_altitude, altDeg)

        val fontMetrics = paintCrosshairText.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top
        val lineGap = CROSSHAIR_LABEL_LINE_GAP_DP * density
        val padX = CROSSHAIR_LABEL_PADDING_X_DP * density
        val padY = CROSSHAIR_LABEL_PADDING_Y_DP * density

        val textWidth = maxOf(
            paintCrosshairText.measureText(azText),
            paintCrosshairText.measureText(altText)
        )
        val boxHeight = lineHeight * 2 + lineGap + padY * 2
        val boxLeft = cx + outerRadius + CROSSHAIR_LABEL_GAP_DP * density
        val boxTop = cy - boxHeight / 2f

        canvas.drawRect(
            boxLeft,
            boxTop,
            boxLeft + textWidth + padX * 2,
            boxTop + boxHeight,
            paintBackground
        )

        val textX = boxLeft + padX
        val line1Baseline = boxTop + padY - fontMetrics.top
        val line2Baseline = line1Baseline + lineHeight + lineGap
        canvas.drawText(azText, textX, line1Baseline, paintCrosshairText)
        canvas.drawText(altText, textX, line2Baseline, paintCrosshairText)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!prefsLoaded) {
            reloadPreferencesInternal()
            prefsLoaded = true
        }

        if (bodyPositionSun == null) {
            val text = "Waiting for device location..."
            val cx = width / 2f
            val cy = height / 2f

            val fontMetrics = paintText.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val textOffset = textHeight / 2 - fontMetrics.bottom

            val textWidth = paintText.measureText(text)
            val padding = 20f
            canvas.drawRect(
                cx - textWidth / 2 - padding,
                cy - textHeight / 2 - padding,
                cx + textWidth / 2 + padding,
                cy + textHeight / 2 + padding,
                paintBackground
            )

            canvas.drawText(text, cx, cy + textOffset, paintText)
            return
        }

        val sun = bodyPositionSun!!

        val solstices = sun.getSolstices(referenceTime)
        if (observerLatitude >= 0) {
            if (pathEnabled["solstice_high"] == true) {
                drawBodyPath(canvas, sun, solstices.june, 10f,
                    pathColors["solstice_high"] ?: Color.BLUE)
            }
            if (pathEnabled["solstice_low"] == true) {
                drawBodyPath(canvas, sun, solstices.december, 10f,
                    pathColors["solstice_low"] ?: Color.RED)
            }
        } else {
            if (pathEnabled["solstice_high"] == true) {
                drawBodyPath(canvas, sun, solstices.december, 10f,
                    pathColors["solstice_high"] ?: Color.BLUE)
            }
            if (pathEnabled["solstice_low"] == true) {
                drawBodyPath(canvas, sun, solstices.june, 10f,
                    pathColors["solstice_low"] ?: Color.RED)
            }
        }

        if (pathEnabled["equinox"] == true) {
            drawBodyPath(canvas, sun, sun.getMarchEquinox(referenceTime), 10f,
                pathColors["equinox"] ?: Color.GREEN)
        }

        if (pathEnabled["sun_current"] == true) {
            val currentColor = pathColors["sun_current"] ?: Color.YELLOW
            drawBodyPath(canvas, sun, referenceTime, 3f, currentColor)
            drawHourTicks(canvas, sun, referenceTime, currentColor)
            val sunPos = sun.getPositionMagnetic(referenceTime)
            project(sunPos.azimuth, sunPos.altitude)?.let {
                val paint = Paint().apply {
                    color = currentColor
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(it.first, it.second, 50f, paint)
            }
        }

        if (pathEnabled["moon_current"] == true) {
            bodyPositionMoon?.let { moon ->
                val moonColor = pathColors["moon_current"] ?: Color.WHITE
                drawBodyPath(canvas, moon, referenceTime, 3f, moonColor)
                drawHourTicks(canvas, moon, referenceTime, moonColor)
                val moonPos = moon.getPositionMagnetic(referenceTime)
                project(moonPos.azimuth, moonPos.altitude)?.let {
                    val paint = Paint().apply {
                        color = moonColor
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawCircle(it.first, it.second, 50f, paint)
                }
            }
        }

        if (showCrosshair) {
            drawCrosshair(canvas)
        }
    }
}
