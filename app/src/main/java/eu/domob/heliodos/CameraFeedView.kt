package eu.domob.heliodos

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlin.math.sqrt

private const val TAG = "HeliodosCamera"

/** Diagonal of a 35 mm full-frame sensor, used to convert to 35 mm-equivalent focal lengths. */
private const val FULL_FRAME_DIAGONAL_MM = 43.27f

private class CameraEntry(
    val cameraId: String,
    val physicalCameraId: String?,
    val characteristics: CameraCharacteristics,
    val focalLength: Float,
    val zoomRatio: Float?
)

class CameraFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var cameraEntries: List<CameraEntry> = emptyList()
    private var currentCameraIndex: Int = 0

    var projection: CameraProjection? = null
        private set

    private var cumulativeScale: Float = 1f
    private var switchedDuringGesture: Boolean = false

    var onSingleTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cumulativeScale = 1f
                switchedDuringGesture = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (switchedDuringGesture) {
                    return true
                }
                cumulativeScale *= detector.scaleFactor
                if (cumulativeScale < 0.9f) {
                    if (currentCameraIndex > 0) {
                        switchToCamera(currentCameraIndex - 1)
                        Toast.makeText(context, "Switched to wider camera", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Already using widest camera", Toast.LENGTH_SHORT).show()
                    }
                    switchedDuringGesture = true
                } else if (cumulativeScale > 1.1f) {
                    if (currentCameraIndex < cameraEntries.size - 1) {
                        switchToCamera(currentCameraIndex + 1)
                        Toast.makeText(context, "Switched to longer camera", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Already using longest camera", Toast.LENGTH_SHORT).show()
                    }
                    switchedDuringGesture = true
                }
                return true
            }
        })

    init {
        surfaceTextureListener = this
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    private fun getFocalLength(characteristics: CameraCharacteristics): Float {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return focalLengths?.firstOrNull() ?: Float.MAX_VALUE
    }

    private fun getCharacteristics(manager: CameraManager, cameraId: String): CameraCharacteristics? {
        return try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: IllegalArgumentException) {
            // Hidden physical camera IDs are not guaranteed to be queryable directly.
            Log.w(TAG, "No characteristics available for camera $cameraId", e)
            null
        }
    }

    private fun isLogicalCamera(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
    }

    private fun getBackFacing(manager: CameraManager): List<String> {
        return manager.cameraIdList.filter { id ->
            getCharacteristics(manager, id)?.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun equivFocalLength(characteristics: CameraCharacteristics): Float? {
        val focal = getFocalLength(characteristics)
        if (focal == Float.MAX_VALUE) return null

        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val diagonal = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        if (diagonal <= 0f) return null

        return focal * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    private fun buildCameraEntries(manager: CameraManager): List<CameraEntry> {
        val allIds = manager.cameraIdList
        val backIds = getBackFacing(manager)
        val logicalIds = backIds.filter { id ->
            getCharacteristics(manager, id)?.let { isLogicalCamera(it) } == true
        }

        Log.i(TAG, "cameraIdList: [${allIds.joinToString()}]")
        Log.i(TAG, "Back cameras: [${backIds.joinToString()}], logical: [${logicalIds.joinToString()}]")

        // Map each physical camera to the logical camera it belongs to, and collect the zoom
        // ratio range and the widest (minimum) 35 mm-equivalent focal length per logical camera.
        val physicalToLogical = mutableMapOf<String, String>()
        val zoomRanges = mutableMapOf<String, android.util.Range<Float>?>()
        val minEquivFocals = mutableMapOf<String, Float>()

        for (logicalId in logicalIds) {
            val logicalChars = getCharacteristics(manager, logicalId) ?: continue
            val physicalIds = logicalChars.physicalCameraIds
            val zoomRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

            val equivs = physicalIds.mapNotNull { physicalId ->
                getCharacteristics(manager, physicalId)?.let { equivFocalLength(it) }
            }
            val minEquiv = equivs.minOrNull()

            Log.i(
                TAG,
                "Logical $logicalId: physical=[${physicalIds.joinToString()}], zoomRange=$zoomRange, " +
                    "minEquivFocal=$minEquiv"
            )

            zoomRanges[logicalId] = zoomRange
            minEquivFocals[logicalId] = minEquiv ?: 0f

            for (physicalId in physicalIds) {
                physicalToLogical.putIfAbsent(physicalId, logicalId)
            }
        }

        val entries = mutableListOf<CameraEntry>()
        val seenIds = mutableSetOf<String>()

        fun addEntry(
            cameraId: String,
            physicalCameraId: String?,
            characteristics: CameraCharacteristics,
            focal: Float,
            zoomRatio: Float?
        ) {
            entries += CameraEntry(cameraId, physicalCameraId, characteristics, focal, zoomRatio)
            val kind = if (physicalCameraId != null) {
                "physical stream $cameraId/$physicalCameraId"
            } else {
                "camera $cameraId"
            }
            Log.i(TAG, "Camera entry: $kind, focal=$focal, zoom=$zoomRatio")
        }

        // Physical cameras that are exposed directly and are not logical cameras themselves.
        for (id in backIds.filterNot { it in logicalIds }) {
            if (!seenIds.add(id)) continue
            val characteristics = getCharacteristics(manager, id) ?: continue
            val focal = getFocalLength(characteristics)
            if (focal == Float.MAX_VALUE) continue
            addEntry(id, null, characteristics, focal, null)
        }

        // Physical cameras that are hidden, i.e. only reachable through a logical camera
        // via a physical stream.
        for ((physicalId, logicalId) in physicalToLogical) {
            if (!seenIds.add(physicalId)) continue
            val characteristics = getCharacteristics(manager, physicalId) ?: continue
            val focal = getFocalLength(characteristics)
            if (focal == Float.MAX_VALUE) continue

            val zoom = computeZoomRatio(
                zoomRanges[logicalId],
                minEquivFocals[logicalId] ?: 0f,
                characteristics
            )
            addEntry(logicalId, physicalId, characteristics, focal, zoom)
        }

        // Fallback for devices that expose a logical camera without any physical cameras at
        // all: the logical camera is then the only real sensor, so use it directly.
        for (logicalId in logicalIds) {
            val characteristics = getCharacteristics(manager, logicalId) ?: continue
            if (characteristics.physicalCameraIds.isNotEmpty()) continue
            if (!seenIds.add(logicalId)) continue
            val focal = getFocalLength(characteristics)
            if (focal == Float.MAX_VALUE) continue
            addEntry(logicalId, null, characteristics, focal, null)
        }

        return entries.sortedBy { it.focalLength }
    }

    private fun computeZoomRatio(
        zoomRange: android.util.Range<Float>?,
        minEquivFocal: Float,
        physicalCharacteristics: CameraCharacteristics
    ): Float? {
        if (zoomRange == null || zoomRange.lower <= 0f) {
            Log.w(TAG, "Logical camera has no usable zoom ratio range; leaving zoom unset")
            return null
        }
        val equivFocal = equivFocalLength(physicalCharacteristics) ?: return null
        if (minEquivFocal <= 0f) {
            Log.w(TAG, "Cannot determine 1x focal length; leaving zoom unset")
            return null
        }

        // The zoom ratio scales with 35 mm-equivalent focal length (field of view), not with
        // the actual focal length, because the physical cameras have different sensor sizes.
        val equivAtOneX = minEquivFocal / zoomRange.lower
        return (equivFocal / equivAtOneX).coerceIn(zoomRange.lower, zoomRange.upper)
    }

    fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (cameraEntries.isEmpty()) {
                cameraEntries = buildCameraEntries(manager)
            }

            if (cameraEntries.isEmpty()) {
                return
            }

            openCameraAtIndex(currentCameraIndex)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        }
    }

    private fun switchToCamera(index: Int) {
        if (index < 0 || index >= cameraEntries.size || index == currentCameraIndex) {
            return
        }
        closeCamera()
        currentCameraIndex = index
        openCameraAtIndex(currentCameraIndex)
    }

    private fun openCameraAtIndex(index: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val entry = cameraEntries[index]
            Log.i(
                TAG,
                "Opening entry ${index + 1}/${cameraEntries.size}: camera=${entry.cameraId}, " +
                    "physical=${entry.physicalCameraId}, focal=${entry.focalLength}, zoom=${entry.zoomRatio}"
            )
            projection = CameraProjection(entry.characteristics)

            manager.openCamera(entry.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera ${entry.cameraId} error: $error")
                    cameraDevice?.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera ${cameraEntries[index].cameraId}", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        }
    }

    private fun createCameraPreview() {
        val entry = cameraEntries.getOrNull(currentCameraIndex) ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Use the opened camera's supported sizes; the logical camera guarantees physical streams
        // of the same size.
        val map = manager.getCameraCharacteristics(entry.cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .maxByOrNull { it.width * it.height } ?: return
        Log.d(TAG, "Preview size for camera ${entry.cameraId}: ${previewSize.width}x${previewSize.height}")

        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        try {
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            entry.zoomRatio?.let { captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
            // Distortion correction stays at the default (ON): the pin-hole projection model assumes a
            // rectilinear image.  Switch to DISTORTION_CORRECTION_MODE_OFF and use the physical camera's
            // LENS_DISTORTION / LENS_INTRINSIC_CALIBRATION if a distortion-aware model is added later.

            val outputConfig = OutputConfiguration(surface)
            entry.physicalCameraId?.let { outputConfig.setPhysicalCameraId(it) }

            cameraDevice?.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    context.getMainExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return

                            captureSession = session
                            try {
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, "Failed to start camera preview", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera session")
                        }
                    }
                )
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create camera preview", e)
        }
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        projection = null
    }
}
