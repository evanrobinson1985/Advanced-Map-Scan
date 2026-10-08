package com.lidarscan.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Tells when your arm is down (the watch hanging at your side) and when you
 * raise it to look, from which way gravity pulls on the watch: with the
 * screen facing up gravity runs straight through it (z near 9.8 m/s2);
 * with the arm hanging the screen faces sideways and z is near 0.
 *
 * Arm down: the screen tipped more than about 70 degrees from facing up
 * (z under 3.3) for 1.5 s, so a quick wave doesn't dim it. Raised: tipped
 * back to within about 55 degrees of facing up (z over 5.6), at once.
 */
class WristDim(ctx: Context, private val onDown: (Boolean) -> Unit) {
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false
    private var z = 9.8f
    private var lowSince = 0L
    var isDown = false; private set

    private val cb = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // the accelerometer (when there's no gravity sensor) is smoothed to leave gravity
            z = if (e.sensor.type == Sensor.TYPE_GRAVITY) e.values[2] else z + 0.15f * (e.values[2] - z)
            val now = SystemClock.elapsedRealtime()
            if (z < 3.3f) {
                if (lowSince == 0L) lowSince = now
                if (!isDown && now - lowSince > 1500) { isDown = true; onDown(true) }
            } else {
                lowSince = 0L
                if (isDown && z > 5.6f) { isDown = false; onDown(false) }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    val available get() = gravity != null || accel != null

    fun start() {
        if (running) return
        val s = gravity ?: accel ?: return
        running = true; lowSince = 0L
        sensors.registerListener(cb, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(cb)
        if (isDown) { isDown = false; onDown(false) }
    }
}
