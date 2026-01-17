package eu.domob.heliodos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cameraFeedView: CameraFeedView? = null
    private var rotationMatrix: FloatArray? = null
    private var sunPosition: SunPosition? = null
    private var referenceTime: Long = 0

    private val paintYellow = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }


    fun setRotationMatrix(matrix: FloatArray) {
        rotationMatrix = matrix
        invalidate()
    }

    fun setPositionAndTime(latitude: Double, longitude: Double, altitude: Double, time: Long) {
        sunPosition = SunPosition(latitude, longitude, altitude)
        referenceTime = time
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

        val sunPos = sunPosition?.getSunPositionMagnetic(referenceTime) ?: return
        project(sunPos.azimuth, sunPos.altitude)?.let {
            canvas.drawCircle(it.first, it.second, 20f, paintYellow)
        }
    }
}
