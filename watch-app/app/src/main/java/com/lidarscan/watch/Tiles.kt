package com.lidarscan.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A basemap the watch can show. */
enum class Basemap(val key: String, val label: String, val url: String?, val maxZ: Int) {
    SATELLITE("satellite", "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19),
    TOPO("topo", "Topo", "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}", 16),
    STREET("street", "Street", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19),
    NONE("none", "None", null, 0);

    fun next(): Basemap = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): Basemap = values().firstOrNull { it.key == key } ?: SATELLITE
    }
}

/**
 * Basemap tiles for the map screen, fetched when the watch has Wi-Fi or LTE
 * and kept (in memory, and on the watch so they work offline next time).
 * draw() puts the tiles that are ready under the view, each one turned and
 * placed by its corners; missing ones are asked for and onLoaded is called
 * as each arrives, so the map redraws.
 */
class TileLayer(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val mem = LruCache<String, Bitmap>(72)
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    var enabled = true

    fun close() = scope.cancel()

    private fun key(b: Basemap, z: Int, x: Int, y: Int) = "${b.key}/$z/$x/$y"
    private fun file(k: String) = File(ctx.cacheDir, "tiles/$k.img")

    private fun get(b: Basemap, z: Int, x: Int, y: Int): Bitmap? {
        val k = key(b, z, x, y)
        mem.get(k)?.let { return it }
        synchronized(pending) {
            if (k in pending) return null
            val f = failed[k]; if (f != null && System.currentTimeMillis() - f < 60000) return null
            pending.add(k)
        }
        scope.launch {
            gate.withPermit {
                var bmp: Bitmap? = null
                try {
                    val fl = file(k)
                    if (fl.exists()) bmp = BitmapFactory.decodeFile(fl.absolutePath)
                    if (bmp == null && enabled) {
                        val bytes = download(b, z, x, y)
                        if (bytes != null) {
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) { fl.parentFile?.mkdirs(); fl.writeBytes(bytes) }
                        }
                    }
                } catch (e: Exception) { /* offline: try again later */ }
                synchronized(pending) { pending.remove(k); if (bmp == null) failed[k] = System.currentTimeMillis() }
                if (bmp != null) { mem.put(k, bmp); onLoaded() }
            }
        }
        return null
    }

    private fun download(b: Basemap, z: Int, x: Int, y: Int): ByteArray? {
        val u = b.url!!.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "LiDARGuide-WearOS/1.0")
        try { return if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    }

    /**
     * Saves the basemap tiles of an area on the watch (not in memory), at the
     * zooms the map uses, so the area works offline later. At most 160 tiles.
     */
    fun prefetch(b: Basemap, a: MapImage) {
        if (b.url == null || !enabled) return
        val lat = (a.n + a.s) / 2
        var budget = 160
        for (mpp in doubleArrayOf(1.2, 4.8)) {   // the map's close and far zooms
            val z = Geo.Tiles.zoomFor(mpp, lat, b.maxZ)
            val x0 = floor(Geo.Tiles.x(a.w, z)).toInt(); val x1 = floor(Geo.Tiles.x(a.e, z)).toInt()
            val y0 = floor(Geo.Tiles.y(a.n, z)).toInt(); val y1 = floor(Geo.Tiles.y(a.s, z)).toInt()
            for (ty in y0..y1) for (tx in x0..x1) {
                if (budget-- <= 0) return
                val fl = file(key(b, z, tx, ty))
                if (fl.exists()) continue
                scope.launch {
                    gate.withPermit {
                        try {
                            if (!fl.exists() && enabled) download(b, z, tx, ty)?.let { fl.parentFile?.mkdirs(); fl.writeBytes(it) }
                        } catch (e: Exception) { /* offline: it loads when the map needs it */ }
                    }
                }
            }
        }
    }

    fun draw(nc: Canvas, view: Geo.View, b: Basemap, paint: Paint) {
        if (b.url == null) return
        val w = view.cx * 2; val h = view.cy * 2
        // the ground under the screen (all four corners, so a turned map is covered)
        val corners = listOf(view.toLatLon(0f, 0f), view.toLatLon(w, 0f), view.toLatLon(0f, h), view.toLatLon(w, h))
        val z = Geo.Tiles.zoomFor(view.mPerPx, view.cLat, b.maxZ)
        val x0 = floor(corners.minOf { Geo.Tiles.x(it.second, z) }).toInt(); val x1 = floor(corners.maxOf { Geo.Tiles.x(it.second, z) }).toInt()
        val y0 = floor(corners.minOf { Geo.Tiles.y(it.first, z) }).toInt(); val y1 = floor(corners.maxOf { Geo.Tiles.y(it.first, z) }).toInt()
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) return   // zoomed far out: too many to fetch
        val n = 1 shl z
        val src = FloatArray(6); val dst = FloatArray(6); val m = Matrix()
        for (ty in max(0, y0)..min(n - 1, y1)) for (tx0 in x0..x1) {
            val tx = ((tx0 % n) + n) % n
            val bmp = get(b, z, tx, ty) ?: continue
            // three corners of the tile on the screen place (and turn) it
            val (ax, ay) = view.toScreen(Geo.Tiles.lat(ty.toDouble(), z), Geo.Tiles.lon(tx0.toDouble(), z))
            val (bx, by) = view.toScreen(Geo.Tiles.lat(ty.toDouble(), z), Geo.Tiles.lon(tx0 + 1.0, z))
            val (cx, cy) = view.toScreen(Geo.Tiles.lat(ty + 1.0, z), Geo.Tiles.lon(tx0.toDouble(), z))
            src[0] = 0f; src[1] = 0f; src[2] = bmp.width.toFloat(); src[3] = 0f; src[4] = 0f; src[5] = bmp.height.toFloat()
            dst[0] = ax; dst[1] = ay; dst[2] = bx; dst[3] = by; dst[4] = cx; dst[5] = cy
            m.setPolyToPoly(src, 0, dst, 0, 3)
            nc.drawBitmap(bmp, m, paint)
        }
    }
}
