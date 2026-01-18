package eu.domob.heliodos

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.widget.Toast

class CameraFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var sortedCameraIds: List<String> = emptyList()
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
                    if (currentCameraIndex < sortedCameraIds.size - 1) {
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

    private fun getFocalLength(manager: CameraManager, cameraId: String): Float {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return focalLengths?.firstOrNull() ?: Float.MAX_VALUE
    }

    fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (sortedCameraIds.isEmpty()) {
                val backCameras = manager.cameraIdList
                    .filter { id ->
                        val characteristics = manager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    }
                    .sortedBy { id -> getFocalLength(manager, id) }

                // Filter out logical multi-camera devices that combine physical cameras
                // These report LOGICAL_MULTI_CAMERA capability and have physical camera IDs
                sortedCameraIds = backCameras.filter { id ->
                    val characteristics = manager.getCameraCharacteristics(id)
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) != true
                }

                // Fallback: if filtering removed all cameras, use the original list
                if (sortedCameraIds.isEmpty()) {
                    sortedCameraIds = backCameras
                }
            }

            if (sortedCameraIds.isEmpty()) {
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
        if (index < 0 || index >= sortedCameraIds.size || index == currentCameraIndex) {
            return
        }
        closeCamera()
        currentCameraIndex = index
        openCameraAtIndex(currentCameraIndex)
    }

    private fun openCameraAtIndex(index: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = sortedCameraIds[index]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            projection = CameraProjection(characteristics)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
        val cameraId = sortedCameraIds.getOrNull(currentCameraIndex) ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val previewSize = map.getOutputSizes(SurfaceTexture::class.java).maxByOrNull { it.width * it.height }!!

        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        try {
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)
            captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return

                    captureSession = session
                    try {
                        session.setRepeatingRequest(captureRequestBuilder!!.build(), null, null)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }
            }, null)
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
