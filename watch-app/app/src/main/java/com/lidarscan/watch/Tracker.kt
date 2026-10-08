package com.lidarscan.watch

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(val lat: Double, val lon: Double, val accM: Float, val speedMps: Float, val courseDeg: Float?, val atMs: Long)
data class Heading(val deg: Double, val fromCompass: Boolean)

/**
 * Live position (GPS, once a second) and which way you face: the watch's
 * compass (rotation vector, turned to true north with the local magnetic
 * declination), or your direction of travel while moving when no compass
 * reading is coming in. Runs only while the app is on screen.
 */
class Tracker(private val ctx: Context) {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private val _heading = MutableStateFlow<Heading?>(null)
    val heading: StateFlow<Heading?> = _heading

    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var running = false
    private var compass: Double? = null
    private var compassAt = 0L
    private var declination = 0f
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            _fix.value = Fix(l.latitude, l.longitude, l.accuracy, if (l.hasSpeed()) l.speed else 0f,
                if (l.hasBearing()) l.bearing else null, System.currentTimeMillis())
            declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination
            update()
        }
    }

    private val sensorCb = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            val h = Geo.norm360(Math.toDegrees(ori[0].toDouble()) + declination)
            // smoothed along the shortest way round, so the arrow doesn't jitter
            val c = compass
            compass = if (c == null) h else Geo.norm360(c + 0.25 * Geo.turn(c, h))
            compassAt = SystemClock.elapsedRealtime()
            update()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun update() {
        val c = compass
        if (c != null && SystemClock.elapsedRealtime() - compassAt < 3000) { _heading.value = Heading(c, true); return }
        val f = _fix.value
        _heading.value = if (f != null && f.courseDeg != null && f.speedMps > 0.7f) Heading(f.courseDeg.toDouble(), false) else null
    }

    @SuppressLint("MissingPermission")   // started only once location permission is granted
    fun start() {
        if (running) return
        running = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locCb, Looper.getMainLooper()) } catch (e: SecurityException) { running = false; return }
        fused.lastLocation.addOnSuccessListener { l -> if (l != null && _fix.value == null) locCb.onLocationResult(LocationResult.create(listOf(l))) }
        val rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (rv != null) sensors.registerListener(sensorCb, rv, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        fused.removeLocationUpdates(locCb)
        sensors.unregisterListener(sensorCb)
    }
}
