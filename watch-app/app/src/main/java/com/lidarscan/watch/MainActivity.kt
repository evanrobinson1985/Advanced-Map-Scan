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
    lateinit var wrist: WristDim
    private var locationAsked = false
    private var resumed = false
    private var screenOnHolds = 0   // screens that keep it on while open (guide, receive)

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) tracker.start() else model.locationDenied = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(applicationContext)
        tracker = Tracker(applicationContext)
        wrist = WristDim(applicationContext) { down -> setDimmed(down) }
        model.onAwakeChanged = { applyAwake() }
        setContent { WatchApp(this) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyAwake()
        if (hasLocation()) { tracker.start(); model.locationDenied = false }
        else if (!locationAsked) {
            locationAsked = true
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onPause() { super.onPause(); resumed = false; tracker.stop(); wrist.stop(); setDimmed(false) }
    override fun onDestroy() { super.onDestroy(); model.close() }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A screen that needs the display on while it's open (guide, receive) holds it with on = true, then lets go. */
    fun keepScreenOn(on: Boolean) { screenOnHolds = maxOf(0, screenOnHolds + if (on) 1 else -1); applyAwake() }

    /**
     * Keep awake (Settings): the screen stays on and the app open, instead of
     * going back to the watch face. With "dim when arm is down" the screen is
     * turned right down while your arm hangs at your side and comes back up
     * when you raise your wrist (or tap it).
     */
    private fun applyAwake() {
        val on = model.keepAwake || screenOnHolds > 0
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (resumed && model.keepAwake && model.dimWhenDown && wrist.available) wrist.start() else { wrist.stop(); setDimmed(false) }
    }

    fun setDimmed(dim: Boolean) {
        if (model.dimmed == dim) return
        model.dimmed = dim
        val lp = window.attributes
        lp.screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
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
    /** The downloaded hillshade areas near you that are drawn (at most 12 in memory). */
    var online by mutableStateOf<List<Pair<OnlineArea, Bitmap>>>(emptyList()); private set
    /** How many areas are kept on the watch. */
    var areaCount by mutableIntStateOf(0); private set
    /** The waypoint you chose (kept, with every setting, for next time the app opens). */
    private var selectedState by mutableStateOf(Store.selectedWaypoint(ctx))
    var selectedId: String?
        get() = selectedState
        set(v) { if (v != selectedState) { selectedState = v; Store.setSelectedWaypoint(ctx, v) } }
    /** The map's zoom (metres per screen pixel) when it was last changed. */
    var mapMpp = Store.mapZoom(ctx); private set
    fun saveZoom(v: Float) { if (v != mapMpp) { mapMpp = v; Store.setMapZoom(ctx, v) } }
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
    /** Waypoint groups (their kind) hidden from the map and the list. */
    var hiddenKinds by mutableStateOf(Store.hiddenKinds(ctx)); private set
    /** Keep the screen on and the app open; dim it while the arm is down. */
    var keepAwake by mutableStateOf(Store.keepAwake(ctx)); private set
    var dimWhenDown by mutableStateOf(Store.dimWhenDown(ctx)); private set
    var dimmed by mutableStateOf(false)
    var onAwakeChanged: () -> Unit = {}
    /** The packages received, newest first, and which one is on the map. */
    var history by mutableStateOf<List<HistoryEntry>>(emptyList()); private set
    var currentId by mutableStateOf(-1L); private set
    val tiles = TileLayer(ctx) { main.post { tileTick++ } }
    private var areas: List<OnlineArea> = emptyList()
    private var onlineBusy = false
    private var onlineFailedAt = 0L
    private var shownAt: Pair<Double, Double>? = null   // where the drawn areas were last chosen
    private var showing = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        tiles.enabled = onlineMaps
        Store.loadPackage(ctx)?.let { applyPackage(it) }
        currentId = Store.currentId(ctx)
        history = Store.history(ctx)
        areas = Store.onlineAreas(ctx); areaCount = areas.size
    }

    fun close() { scope.cancel(); tiles.close() }

    val selected: Waypoint? get() = pkg?.waypoints?.firstOrNull { it.id == selectedId }

    /** The waypoints shown: those in groups not hidden by the filter. */
    val visibleWaypoints: List<Waypoint> get() = pkg?.waypoints?.filter { it.kind !in hiddenKinds } ?: emptyList()
    /** The groups in the package on the map, with how many waypoints each. */
    val kinds: List<Pair<Waypoint, Int>> get() = (pkg?.waypoints ?: emptyList()).groupBy { it.kind }.map { (_, l) -> Pair(l[0], l.size) }.sortedByDescending { it.second }
    fun setKindShown(kind: String, shown: Boolean) { hiddenKinds = if (shown) hiddenKinds - kind else hiddenKinds + kind; Store.setHiddenKinds(ctx, hiddenKinds); if (!shown && selected?.kind == kind) selectedId = null }
    fun showAllKinds() { hiddenKinds = emptySet(); Store.setHiddenKinds(ctx, hiddenKinds) }

    fun toggleKeepAwake() { keepAwake = !keepAwake; Store.setKeepAwake(ctx, keepAwake); onAwakeChanged() }
    fun toggleDimWhenDown() { dimWhenDown = !dimWhenDown; Store.setDimWhenDown(ctx, dimWhenDown); onAwakeChanged() }

    /** Puts a package from the history back on the map. */
    fun loadFromHistory(id: Long) {
        scope.launch {
            val p = withContext(Dispatchers.IO) { Store.loadEntry(ctx, id) } ?: return@launch
            Store.setCurrentId(ctx, id); currentId = id
            applyPackage(p); showPackageArea = true
        }
    }
    fun deleteFromHistory(id: Long) {
        Store.deleteEntry(ctx, id)
        if (id == currentId) { pkg = null; pkgBitmap = null; baseBitmap = null; selectedId = null; currentId = -1L }
        history = Store.history(ctx)
    }

    private fun applyPackage(p: WatchPackage) {
        pkg = p
        pkgBitmap = p.imageBytes?.let { decodeMap(it) }
        baseBitmap = p.baseBytes?.let { decodeMap(it) }
        if (p.waypoints.none { it.id == selectedId }) selectedId = null
    }

    /** Called from the Bluetooth thread with a complete package: checked, kept, shown. */
    fun receive(bytes: ByteArray): String {
        val p = WatchPackage.decode(bytes)
        val id = Store.addToHistory(ctx, bytes, p)
        main.post {
            applyPackage(p); showPackageArea = true
            currentId = id; history = Store.history(ctx)
            // the web app's map settings come with the waypoints (you can still change them here)
            p.viewBase?.let { chooseBasemap(Basemap.of(it)) }
            p.viewHillshadeOpacity?.let { chooseHillshadeOpacity(it) }
        }
        return "${p.waypoints.size} waypoint${if (p.waypoints.size == 1) "" else "s"}" + (if (p.image != null) ", the hillshade" else "") +
            (if (p.baseBytes != null) " and the ${if (p.baseSrc == "topo") "topo map" else "satellite imagery"}" else "")
    }

    fun toggleHeadingUp() { headingUp = !headingUp; Store.setHeadingUp(ctx, headingUp) }
    fun toggleOnlineMaps() { onlineMaps = !onlineMaps; Store.setOnlineMaps(ctx, onlineMaps); tiles.enabled = onlineMaps }
    fun chooseBasemap(b: Basemap) { basemap = b; Store.setBasemap(ctx, b.key) }
    fun nextBasemap() { chooseBasemap(basemap.next()); mapNote = "Basemap: ${basemap.label}"; mapNoteUntil = System.currentTimeMillis() + 2500 }
    fun chooseHillshadeOpacity(v: Float) { hillshadeOpacity = v.coerceIn(0f, 1f); Store.setHillshadeOpacity(ctx, hillshadeOpacity) }
    fun nextHillshadeOpacity() { val o = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f, 1f); val i = o.indexOfFirst { kotlin.math.abs(it - hillshadeOpacity) < 0.03f }; chooseHillshadeOpacity(o[(i + 1) % o.size]) }
    fun nextArrive() { val opts = intArrayOf(5, 10, 20, 30); arriveM = opts[(opts.indexOf(arriveM) + 1) % opts.size]; Store.setArriveM(ctx, arriveM) }

    /**
     * Maps as you go (with "Download maps as you go" on and Wi-Fi or LTE), as
     * the web app's scan as you go: once you are within a quarter of an area's
     * width of ground no map covers (the received one or those downloaded),
     * the next 1.2 km area of USGS hillshade is downloaded, shifted ahead of
     * you, and its basemap tiles are saved too, so the ground you walk onto
     * is already mapped when the signal drops. Areas are kept (the newest 40).
     */
    fun maybeFetchOnline(f: Fix) {
        showAreasNear(f.lat, f.lon)
        if (!onlineMaps || onlineBusy || f.accM > 150f) return
        val have = areas.map { it.image } + listOfNotNull(pkg?.image)
        val c = Geo.nextArea(f.lat, f.lon, have, AREA_HALF_M)
        if (c == null) { if (onlineNote.startsWith("Downloading")) onlineNote = ""; return }
        if (System.currentTimeMillis() - onlineFailedAt < 30000) return
        onlineBusy = true
        onlineNote = if (c.first == f.lat && c.second == f.lon) "Downloading the map around you..." else "Downloading the map ahead..."
        scope.launch {
            try {
                val a = withContext(Dispatchers.IO) { Store.fetchOnline(ctx, c.first, c.second, AREA_HALF_M) }
                areas = withContext(Dispatchers.IO) { Store.onlineAreas(ctx) }; areaCount = areas.size
                tiles.prefetch(basemap.source, a.image)
                onlineNote = ""
                shownAt = null; showAreasNear(f.lat, f.lon)
            } catch (e: Exception) {
                onlineFailedAt = System.currentTimeMillis()
                onlineNote = "No map here: ${e.message ?: "offline"}. Send one from the phone."
            } finally { onlineBusy = false }
        }
    }

    /** Draws the 12 downloaded areas nearest you (chosen again each 200 m). */
    private fun showAreasNear(lat: Double, lon: Double) {
        val at = shownAt
        if (showing || (at != null && Geo.distanceM(at.first, at.second, lat, lon) < 200)) return
        shownAt = Pair(lat, lon)
        val want = areas.sortedBy { Geo.distanceM(lat, lon, (it.image.n + it.image.s) / 2, (it.image.e + it.image.w) / 2) }.take(12)
        val have = online.associateBy { it.first.id }
        if (want.map { it.id }.toSet() == have.keys) return
        showing = true
        scope.launch {
            try {
                // the new list is drawn oldest first, so a newer area lies over an older one
                online = withContext(Dispatchers.IO) {
                    want.sortedBy { it.id }.mapNotNull { a -> have[a.id] ?: try { decodeMap(a.file.readBytes())?.let { Pair(a, it) } } catch (e: Exception) { null } }
                }
            } finally { showing = false }
        }
    }

    /** Deletes the downloaded map areas (the received packages stay). */
    fun clearDownloadedAreas() {
        Store.clearOnline(ctx)
        areas = emptyList(); areaCount = 0; online = emptyList(); shownAt = null
    }

    companion object {
        /** Half the width of a downloaded area (1.2 km across). */
        const val AREA_HALF_M = 600.0
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
