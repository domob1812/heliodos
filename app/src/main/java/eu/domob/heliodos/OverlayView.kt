package eu.domob.heliodos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cameraFeedView: CameraFeedView? = null
    private var rotationMatrix: FloatArray? = null

    private val paintYellow = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintRed = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintGreen = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintBlue = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintWhite = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintBlack = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setRotationMatrix(matrix: FloatArray) {
        rotationMatrix = matrix
        invalidate()
    }

    private fun project(worldX: Float, worldY: Float, worldZ: Float): Pair<Float, Float>? {
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

        // Cardinal directions
        project(0f, 1f, 0f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintYellow) }  // North
        project(1f, 0f, 0f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintRed) }     // East
        project(0f, -1f, 0f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintGreen) }  // South
        project(-1f, 0f, 0f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintBlue) }   // West

        // Zenith and nadir
        project(0f, 0f, 1f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintWhite) }   // Zenith
        project(0f, 0f, -1f)?.let { canvas.drawCircle(it.first, it.second, 20f, paintBlack) }  // Nadir
    }
}
