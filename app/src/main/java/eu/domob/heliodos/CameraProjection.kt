package eu.domob.heliodos

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF

class CameraProjection(characteristics: CameraCharacteristics) {

    private val focalLengthMm: Float
    private val sensorSizeMm: SizeF
    private val sensorSizePx: android.util.Size
    val sensorOrientation: Int

    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    init {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        focalLengthMm = focalLengths?.firstOrNull() ?: 4.0f

        sensorSizeMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(4.0f, 3.0f)

        sensorSizePx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: android.util.Size(4000, 3000)

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /**
     * Projects a 3D point in device coordinates to 2D view coordinates.
     *
     * Device coordinate system:
     * - X: right
     * - Y: down
     * - Z: forward (into the scene)
     *
     * Returns null if the point is behind the camera (Z <= 0).
     */
    fun project(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        if (z <= 0) return null

        // Account for sensor orientation: when sensorOrientation is 90 or 270,
        // the sensor's native width/height are swapped relative to the displayed image.
        val isRotated = sensorOrientation == 90 || sensorOrientation == 270
        val effectiveSensorWidth = if (isRotated) sensorSizePx.height else sensorSizePx.width
        val effectiveSensorHeight = if (isRotated) sensorSizePx.width else sensorSizePx.height
        val effectiveSensorMmWidth = if (isRotated) sensorSizeMm.height else sensorSizeMm.width
        val effectiveSensorMmHeight = if (isRotated) sensorSizeMm.width else sensorSizeMm.height

        // Focal length in pixels (on sensor, in the orientation matching the display)
        val fxSensor = focalLengthMm / effectiveSensorMmWidth * effectiveSensorWidth
        val fySensor = focalLengthMm / effectiveSensorMmHeight * effectiveSensorHeight

        // Project to sensor coordinates (pinhole model)
        val xSensor = fxSensor * (x / z) + effectiveSensorWidth / 2.0f
        val ySensor = fySensor * (y / z) + effectiveSensorHeight / 2.0f

        // Map sensor coordinates to view coordinates
        // The preview is typically scaled to fill the view (center-crop)
        val sensorAspect = effectiveSensorWidth.toFloat() / effectiveSensorHeight
        val viewAspect = viewWidth.toFloat() / viewHeight

        val xView: Float
        val yView: Float

        if (viewAspect > sensorAspect) {
            // View is wider than sensor: sensor height is cropped
            val scale = viewWidth.toFloat() / effectiveSensorWidth
            val visibleSensorHeight = viewHeight / scale
            val cropY = (effectiveSensorHeight - visibleSensorHeight) / 2.0f
            xView = xSensor * scale
            yView = (ySensor - cropY) * scale
        } else {
            // View is taller than sensor: sensor width is cropped
            val scale = viewHeight.toFloat() / effectiveSensorHeight
            val visibleSensorWidth = viewWidth / scale
            val cropX = (effectiveSensorWidth - visibleSensorWidth) / 2.0f
            xView = (xSensor - cropX) * scale
            yView = ySensor * scale
        }

        return Pair(xView, yView)
    }
}
