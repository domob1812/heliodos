package eu.domob.heliodos

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.widget.Toast

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

    private fun buildCameraEntries(manager: CameraManager): List<CameraEntry> {
        val backIds = manager.cameraIdList
            .filter { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

        val logicalCameras = backIds.filter { id ->
            val capabilities = manager.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        }

        val entries = mutableListOf<CameraEntry>()

        for (logicalId in logicalCameras) {
            val logicalChars = manager.getCameraCharacteristics(logicalId)
            val physicalIds = logicalChars.physicalCameraIds
            if (physicalIds.isEmpty()) {
                entries += CameraEntry(logicalId, null, logicalChars, getFocalLength(logicalChars), null)
                continue
            }

            // Physical cameras already exposed directly will be added as direct entries below.
            val hiddenPhysicals = physicalIds.filter { it !in backIds }
            if (hiddenPhysicals.isEmpty()) {
                continue
            }

            val zoomRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange == null || zoomRange.lower <= 0f) {
                entries += CameraEntry(logicalId, null, logicalChars, getFocalLength(logicalChars), null)
                continue
            }

            val allPhysicalChars = physicalIds.map { manager.getCameraCharacteristics(it) }
            val minFocal = allPhysicalChars.map { getFocalLength(it) }.filter { it != Float.MAX_VALUE }.minOrNull()
            if (minFocal == null || minFocal <= 0f) {
                entries += CameraEntry(logicalId, null, logicalChars, getFocalLength(logicalChars), null)
                continue
            }
            val focalAtOneX = minFocal / zoomRange.lower

            for (physicalId in hiddenPhysicals) {
                val physicalChars = manager.getCameraCharacteristics(physicalId)
                val focal = getFocalLength(physicalChars)
                if (focal == Float.MAX_VALUE) {
                    continue
                }
                val zoom = (focal / focalAtOneX).coerceIn(zoomRange.lower, zoomRange.upper)
                entries += CameraEntry(logicalId, physicalId, physicalChars, focal, zoom)
            }
        }

        val directEntries = backIds.filterNot { it in logicalCameras }.map { id ->
            val chars = manager.getCameraCharacteristics(id)
            CameraEntry(id, null, chars, getFocalLength(chars), null)
        }
        entries += directEntries

        return entries.sortedBy { it.focalLength }
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
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
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
                    cameraDevice?.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
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
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                        }
                    }
                )
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
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
