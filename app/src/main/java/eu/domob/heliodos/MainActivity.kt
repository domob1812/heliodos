package eu.domob.heliodos

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private lateinit var cameraFeedView: CameraFeedView
    private lateinit var overlayView: OverlayView
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var rotationSensor: Sensor? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        setContentView(R.layout.activity_main)

        cameraFeedView = findViewById(R.id.cameraFeedView)
        overlayView = findViewById(R.id.overlayView)
        overlayView.cameraFeedView = cameraFeedView

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        cameraFeedView.onSingleTap = {
            showAboutDialog()
        }
    }

    private fun showAboutDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        
        val tvVersion = view.findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
        
        val tvGitHub = view.findViewById<TextView>(R.id.tvGitHub)
        tvGitHub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_link)))
            startActivity(intent)
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .show()
    }

    private fun checkAndRequestPermissions() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useLocation = prefs.getBoolean("use_location", true)

        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (useLocation) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startApp()
        }
    }

    private fun startApp() {
        if (cameraFeedView.isAvailable) {
            cameraFeedView.openCamera()
        }
        updateLocationMode()
    }

    private fun updateLocationMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useLocation = prefs.getBoolean("use_location", true)

        if (useLocation) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            }
        } else {
            locationManager.removeUpdates(this)
            val lat = prefs.getString("manual_latitude", "51.5")?.toDoubleOrNull() ?: 51.5
            val lon = prefs.getString("manual_longitude", "0")?.toDoubleOrNull() ?: 0.0
            val alt = prefs.getString("manual_altitude", "0")?.toDoubleOrNull() ?: 0.0
            updateOverlay(lat, lon, alt)
        }
    }

    private fun startLocationUpdates() {
        overlayView.clearLocation()

        val lastKnownLocationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastKnownLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        var bestLocation = lastKnownLocationGPS
        if (bestLocation == null || (lastKnownLocationNetwork != null && lastKnownLocationNetwork.time > bestLocation.time)) {
            bestLocation = lastKnownLocationNetwork
        }

        if (bestLocation != null) {
            onLocationChanged(bestLocation)
        }

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
        }
    }

    private fun updateOverlay(latitude: Double, longitude: Double, altitude: Double) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useCurrentTime = prefs.getBoolean("use_current_time", true)
        val time = if (useCurrentTime) {
            System.currentTimeMillis()
        } else {
            prefs.getLong("manual_timestamp", System.currentTimeMillis())
        }
        overlayView.setPositionAndTime(latitude, longitude, altitude, time)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val cameraPermissionIndex = permissions.indexOf(Manifest.permission.CAMERA)
            val cameraGranted = if (cameraPermissionIndex != -1) {
                grantResults[cameraPermissionIndex] == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }

            if (!cameraGranted) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            // Check location permissions if they were requested
            val fineLocIndex = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseLocIndex = permissions.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            val locationGranted = (fineLocIndex != -1 && grantResults[fineLocIndex] == PackageManager.PERMISSION_GRANTED) ||
                                  (coarseLocIndex != -1 && grantResults[coarseLocIndex] == PackageManager.PERMISSION_GRANTED)

            if (!locationGranted && (fineLocIndex != -1 || coarseLocIndex != -1)) {
                // Location requested but denied. Switch to manual mode.
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("use_location", false).apply()
                Toast.makeText(this, "Location denied, using manual mode", Toast.LENGTH_SHORT).show()
            }

            startApp()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        cameraFeedView.closeCamera()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        checkAndRequestPermissions()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            overlayView.setRotationMatrix(rotationMatrix)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    override fun onLocationChanged(location: Location) {
        updateOverlay(location.latitude, location.longitude, location.altitude)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
