package com.lidarscan.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LiDAR Guide for Wear OS: the feature waypoints from the LiDAR scanner web
 * app over a LiDAR hillshade, with your live position and heading, a guide
 * arrow to the waypoint you choose and each waypoint's details.
 */
class MainActivity : ComponentActivity() {
    lateinit var model: AppModel
    lateinit var tracker: Tracker
    private var locationAsked = false

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) tracker.start() else model.locationDenied = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(applicationContext)
        tracker = Tracker(applicationContext)
        setContent { WatchApp(this) }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocation()) { tracker.start(); model.locationDenied = false }
        else if (!locationAsked) {
            locationAsked = true
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onPause() { super.onPause(); tracker.stop() }
    override fun onDestroy() { super.onDestroy(); model.close() }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun buzz(pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator else oldVibrator()
        v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
    @Suppress("DEPRECATION")
    private fun oldVibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf()

    fun hasBluetoothPermissions() = bluetoothPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

/** What the screens show; changed on the main thread. */
class AppModel(private val ctx: Context) {
    var pkg by mutableStateOf<WatchPackage?>(null); private set
    var pkgBitmap by mutableStateOf<Bitmap?>(null); private set
    var online by mutableStateOf<Pair<MapImage, Bitmap>?>(null); private set
    var selectedId by mutableStateOf<String?>(null)
    var headingUp by mutableStateOf(Store.headingUp(ctx)); private set
    var onlineMaps by mutableStateOf(Store.onlineMaps(ctx)); private set
    var arriveM by mutableIntStateOf(Store.arriveM(ctx)); private set
    var onlineNote by mutableStateOf("")
    var locationDenied by mutableStateOf(false)
    /** Set when a package arrives: the map then opens on its area rather than on you. */
    var showPackageArea by mutableStateOf(false)
    /** The basemap under the hillshade, and the hillshade's opacity (as the web app's defaults: satellite, 70%). */
    var basemap by mutableStateOf(Basemap.of(Store.basemap(ctx))); private set
    var hillshadeOpacity by mutableStateOf(Store.hillshadeOpacity(ctx)); private set
    /** The satellite picture sent with the package (works with no signal). */
    var baseBitmap by mutableStateOf<Bitmap?>(null); private set
    /** Counts the basemap tiles arriving, so the map redraws. */
    var tileTick by mutableIntStateOf(0); private set
    /** A short note on the map (the basemap just chosen), until this time. */
    var mapNote by mutableStateOf(""); var mapNoteUntil = 0L
    val tiles = TileLayer(ctx) { main.post { tileTick++ } }
    private var onlineBusy = false
    private var onlineTriedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        tiles.enabled = onlineMaps
        Store.loadPackage(ctx)?.let { applyPackage(it) }
        Store.loadOnline(ctx)?.let { (m, b) -> decodeMap(b)?.let { online = Pair(m, it) } }
    }

    fun close() { scope.cancel(); tiles.close() }

    val selected: Waypoint? get() = pkg?.waypoints?.firstOrNull { it.id == selectedId }

    private fun applyPackage(p: WatchPackage) {
        pkg = p
        pkgBitmap = p.imageBytes?.let { decodeMap(it) }
        baseBitmap = p.baseBytes?.let { decodeMap(it) }
        if (p.waypoints.none { it.id == selectedId }) selectedId = null
    }

    /** Called from the Bluetooth thread with a complete package: checked, kept, shown. */
    fun receive(bytes: ByteArray): String {
        val p = WatchPackage.decode(bytes)
        Store.savePackage(ctx, bytes)
        main.post {
            applyPackage(p); showPackageArea = true
            // the web app's map settings come with the waypoints (you can still change them here)
            p.viewBase?.let { setBasemap(Basemap.of(it)) }
            p.viewHillshadeOpacity?.let { setHillshadeOpacity(it) }
        }
        return "${p.waypoints.size} waypoint${if (p.waypoints.size == 1) "" else "s"}" + (if (p.image != null) ", the hillshade" else "") +
            (if (p.baseBytes != null) " and the ${if (p.baseSrc == "topo") "topo map" else "satellite imagery"}" else "")
    }

    fun deletePackage() { Store.deletePackage(ctx); pkg = null; pkgBitmap = null; selectedId = null }
    fun toggleHeadingUp() { headingUp = !headingUp; Store.setHeadingUp(ctx, headingUp) }
    fun toggleOnlineMaps() { onlineMaps = !onlineMaps; Store.setOnlineMaps(ctx, onlineMaps); tiles.enabled = onlineMaps }
    fun setBasemap(b: Basemap) { basemap = b; Store.setBasemap(ctx, b.key) }
    fun nextBasemap() { setBasemap(basemap.next()); mapNote = "Basemap: ${basemap.label}"; mapNoteUntil = System.currentTimeMillis() + 2500 }
    fun setHillshadeOpacity(v: Float) { hillshadeOpacity = v.coerceIn(0f, 1f); Store.setHillshadeOpacity(ctx, hillshadeOpacity) }
    fun nextHillshadeOpacity() { val o = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f, 1f); val i = o.indexOfFirst { kotlin.math.abs(it - hillshadeOpacity) < 0.03f }; setHillshadeOpacity(o[(i + 1) % o.size]) }
    fun nextArrive() { val opts = intArrayOf(5, 10, 20, 30); arriveM = opts[(opts.indexOf(arriveM) + 1) % opts.size]; Store.setArriveM(ctx, arriveM) }

    /**
     * When you are off the received map (or have none) and online maps are
     * on, the hillshade around you is downloaded (at most every 30 s).
     */
    fun maybeFetchOnline(f: Fix) {
        if (!onlineMaps || onlineBusy) return
        if (covers(pkg?.image, f, 100.0) || covers(online?.first, f, 150.0)) return
        val now = System.currentTimeMillis()
        if (now - onlineTriedAt < 30000) return
        onlineTriedAt = now; onlineBusy = true
        onlineNote = "Downloading the map around you..."
        scope.launch {
            try {
                val (m, b) = withContext(Dispatchers.IO) { Store.fetchOnline(ctx, f.lat, f.lon) }
                val bmp = withContext(Dispatchers.Default) { decodeMap(b) }
                if (bmp != null) { online = Pair(m, bmp); onlineNote = "" } else onlineNote = "The map download could not be read."
            } catch (e: Exception) {
                onlineNote = "No map here: ${e.message ?: "offline"}. Send one from the phone."
            } finally { onlineBusy = false }
        }
    }

    private fun covers(m: MapImage?, f: Fix, marginM: Double): Boolean {
        if (m == null) return false
        val local = Geo.Local(f.lat, f.lon)
        return local.north(m.n) > marginM && local.north(m.s) < -marginM && local.east(m.e) > marginM && local.east(m.w) < -marginM
    }

    companion object {
        /** A map picture, made smaller if very large (watch memory). */
        fun decodeMap(bytes: ByteArray): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / sample > 2600 || o.outHeight / sample > 2600) sample *= 2
            val d = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)
        }
    }
}
