package com.ocam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "Pose"

/** Beyond this a fix is a place the phone used to be, not the place the picture was taken. */
private const val FIX_MAX_AGE_MS = 5 * 60_000L

/**
 * Where the camera was and which way it was pointing, as the photograph was taken.
 *
 * Six numbers: latitude, longitude and altitude from the location providers, and yaw, pitch and
 * roll from the rotation vector - the fused reading of magnetometer, accelerometer and gyroscope,
 * which is steadier than the compass alone. All three describe the lens, not the screen: yaw is
 * degrees clockwise from north, pitch is the elevation of the optical axis (positive above the
 * horizon, -90 pointing at your feet), roll is how far the frame is turned about it.
 *
 * [trueNorth] says which north the yaw is measured from. The sensors give magnetic north; with a
 * position in hand the local declination is known, and the yaw is corrected to true north, which
 * is what the EXIF tag is usually read as. Without a position it stays magnetic and says so.
 */
class Pose(
    val location: Location?,
    val yawDegrees: Float?,
    val pitchDegrees: Float?,
    val rollDegrees: Float?,
    val trueNorth: Boolean,
) {
    val hasOrientation: Boolean get() = yawDegrees != null

    companion object {
        val NONE = Pose(null, null, null, null, false)
    }
}

/**
 * Keeps the last position and the last orientation, so a press of the shutter can have both
 * without waiting for a sensor.
 *
 * Both sources are listened to only while the camera is on screen: a camera app has no business
 * tracking anything while it is in the background, and the radios are the most expensive thing
 * here by a wide margin.
 */
class PoseTracker(context: Context) {

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(SensorManager::class.java)
    private val locations = appContext.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val rotationVector: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile private var lastLocation: Location? = null
    @Volatile private var yaw: Float? = null
    @Volatile private var pitch: Float? = null
    @Volatile private var roll: Float? = null

    private var listening = false

    /** Whether the owner has granted a position at all; orientation needs no permission. */
    val locationAllowed: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun start() {
        if (listening) return
        listening = true
        rotationVector?.let {
            sensors?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        startLocation()
    }

    fun stop() {
        if (!listening) return
        listening = false
        sensors?.unregisterListener(sensorListener)
        runCatching { locations?.removeUpdates(locationListener) }
    }

    /** What to stamp into a file written now. */
    fun current(): Pose {
        val fix = lastLocation?.takeIf {
            System.currentTimeMillis() - it.time < FIX_MAX_AGE_MS
        }
        val magneticYaw = yaw ?: return Pose(fix, null, null, null, false)

        // True north differs from magnetic north by the local declination, which depends on where
        // you are standing. With a fix the correction is known; without one it is not.
        val declination = fix?.let {
            GeomagneticField(
                it.latitude.toFloat(),
                it.longitude.toFloat(),
                it.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination
        }
        val corrected = declination?.let { (magneticYaw + it + 360f) % 360f } ?: magneticYaw
        return Pose(fix, corrected, pitch, roll, trueNorth = declination != null)
    }

    private fun startLocation() {
        if (!locationAllowed) return
        val manager = locations ?: return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.getLastKnownLocation(provider)?.let { offer(it) }
                manager.requestLocationUpdates(provider, 2_000L, 2f, locationListener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "No $provider on this device", e)
            }
        }
    }

    /** Keep the better fix: a newer one, or a more accurate one from the same moment. */
    private fun offer(candidate: Location) {
        val held = lastLocation
        lastLocation = when {
            held == null -> candidate
            candidate.time > held.time -> candidate
            candidate.hasAccuracy() && held.hasAccuracy() &&
                candidate.accuracy < held.accuracy -> candidate
            else -> held
        }
    }

    private val locationListener = LocationListener { offer(it) }

    private val sensorListener = object : SensorEventListener {
        private val matrix = FloatArray(9)
        private var lastUpdate = 0L

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            // A few times a second is plenty for something only read when the shutter is pressed.
            val now = SystemClock.uptimeMillis()
            if (now - lastUpdate < 100L) return
            lastUpdate = now

            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            readPose(matrix)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /**
     * The camera's own direction, taken from the rotation matrix rather than from getOrientation.
     *
     * getOrientation describes the screen, and its azimuth is the heading of the phone's top
     * edge - which points at the sky exactly when the phone is held up to photograph something,
     * where that heading is both degenerate and jumpy. The matrix maps device axes to the world
     * (x east, y north, z up), so the direction the lens looks is just the device's -z axis
     * carried into the world, and the angles fall out of it with no special case for how the
     * phone is being held.
     */
    private fun readPose(m: FloatArray) {
        // world = m * device, so the device's -z axis (out of the back, where the lens looks) is
        // the negated third column.
        val east = -m[2]
        val north = -m[5]
        val up = -m[8]

        yaw = ((Math.toDegrees(kotlin.math.atan2(east, north).toDouble()).toFloat() + 360f) % 360f)
        // Positive is above the horizon. The axis is a unit vector, so its up component is the
        // sine of the elevation.
        pitch = Math.toDegrees(kotlin.math.asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
        // Roll is how far the frame is turned about that axis, which is the tilt of the device's
        // right edge: level in portrait, +-90 on its side.
        roll = Math.toDegrees(kotlin.math.asin(m[6].coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
