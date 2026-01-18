package eu.domob.heliodos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

private const val PATH_POINTS = 20

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cameraFeedView: CameraFeedView? = null
    private var rotationMatrix: FloatArray? = null
    private var sunPosition: SunPosition? = null
    private var referenceTime: Long = 0
    private var observerLatitude: Double = 0.0

    private val paintYellow = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }

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

    private fun drawSunPath(canvas: Canvas, time: Long, thickness: Float, color: Int) {
        val sp = sunPosition ?: return
        val riseSet = sp.getSunriseSunset(time) ?: return

        val paint = Paint().apply {
            this.color = color
            this.strokeWidth = thickness
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val points = Array(PATH_POINTS) { i ->
            val t = riseSet.sunrise + (riseSet.sunset - riseSet.sunrise) * i / (PATH_POINTS - 1)
            val pos = sp.getSunPositionMagnetic(t)
            project(pos.azimuth, pos.altitude)
        }

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.first, p1.second, p2.first, p2.second, paint)
            }
        }
    }

    fun setRotationMatrix(matrix: FloatArray) {
        rotationMatrix = matrix
        invalidate()
    }

    fun setPositionAndTime(latitude: Double, longitude: Double, altitude: Double, time: Long) {
        sunPosition = SunPosition(latitude, longitude, altitude)
        referenceTime = time
        observerLatitude = latitude
        invalidate()
    }

    fun clearLocation() {
        sunPosition = null
        invalidate()
    }

    private fun project(azimuth: Double, altitude: Double): Pair<Float, Float>? {
        val r = cos(altitude)
        val worldX = (sin(azimuth) * r).toFloat()
        val worldY = (cos(azimuth) * r).toFloat()
        val worldZ = sin(altitude).toFloat()

        val camProjection = cameraFeedView?.projection ?: return null
        val R = rotationMatrix ?: return null

        // R maps device coords to world coords, so we use its transpose to go world -> device
        // Device coords: X=right, Y=up, Z=out of screen
        val deviceX = R[0] * worldX + R[3] * worldY + R[6] * worldZ
        val deviceY = R[1] * worldX + R[4] * worldY + R[7] * worldZ
        val deviceZ = R[2] * worldX + R[5] * worldY + R[8] * worldZ

        // Camera looks out the back of the phone, with X=right, Y=down, Z=forward
        val cameraX = deviceX
        val cameraY = -deviceY
        val cameraZ = -deviceZ

        camProjection.setViewSize(width, height)
        return camProjection.project(cameraX, cameraY, cameraZ)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sunPosition == null) {
            val text = "Waiting for device location..."
            val cx = width / 2f
            val cy = height / 2f
            
            val fontMetrics = paintText.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val textOffset = textHeight / 2 - fontMetrics.bottom
            
            // Draw background rectangle for better visibility
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

        val solstices = sunPosition?.getSolstices(referenceTime)
        if (solstices != null) {
            val juneColor: Int
            val decColor: Int
            if (observerLatitude >= 0) {
                juneColor = Color.BLUE
                decColor = Color.RED
            } else {
                juneColor = Color.RED
                decColor = Color.BLUE
            }
            drawSunPath(canvas, solstices.june, 10f, juneColor)
            drawSunPath(canvas, solstices.december, 10f, decColor)
        }

        sunPosition?.getMarchEquinox(referenceTime)?.let {
            drawSunPath(canvas, it, 10f, Color.GREEN)
        }

        drawSunPath(canvas, referenceTime, 3f, Color.YELLOW)

        val sunPos = sunPosition?.getSunPositionMagnetic(referenceTime) ?: return
        project(sunPos.azimuth, sunPos.altitude)?.let {
            canvas.drawCircle(it.first, it.second, 50f, paintYellow)
        }
    }
}
