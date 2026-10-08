package com.lidarscan.watch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/** The received package, the online map and the settings, kept on the watch. */
object Store {
    private fun pkgFile(ctx: Context) = File(ctx.filesDir, "package.lwp")
    private fun onlineImg(ctx: Context) = File(ctx.filesDir, "online.jpg")
    private fun onlineMeta(ctx: Context) = File(ctx.filesDir, "online.json")

    fun savePackage(ctx: Context, bytes: ByteArray) {
        val tmp = File(ctx.filesDir, "package.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(pkgFile(ctx))
    }

    fun loadPackage(ctx: Context): WatchPackage? = try {
        val f = pkgFile(ctx)
        if (f.exists()) WatchPackage.decode(f.readBytes()) else null
    } catch (e: Exception) { null }

    fun deletePackage(ctx: Context) { pkgFile(ctx).delete() }

    // ---- settings ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun headingUp(ctx: Context) = prefs(ctx).getBoolean("headingUp", false)
    fun setHeadingUp(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("headingUp", v).apply()
    fun onlineMaps(ctx: Context) = prefs(ctx).getBoolean("onlineMaps", true)
    fun setOnlineMaps(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("onlineMaps", v).apply()
    fun arriveM(ctx: Context) = prefs(ctx).getInt("arriveM", 10)
    fun setArriveM(ctx: Context, v: Int) = prefs(ctx).edit().putInt("arriveM", v).apply()

    // ---- the online hillshade ----
    fun loadOnline(ctx: Context): Pair<MapImage, ByteArray>? = try {
        val m = JSONObject(onlineMeta(ctx).readText())
        Pair(MapImage(m.getDouble("n"), m.getDouble("s"), m.getDouble("e"), m.getDouble("w"), m.getInt("pw"), m.getInt("ph")), onlineImg(ctx).readBytes())
    } catch (e: Exception) { null }

    /**
     * Downloads a USGS 3DEP hillshade (the same 1 m LiDAR the web app uses,
     * shaded by the USGS server) of a square around a point, when the watch
     * has Wi-Fi or LTE. Blocking: call off the main thread.
     */
    fun fetchOnline(ctx: Context, lat: Double, lon: Double, halfM: Double = 600.0, px: Int = 800): Pair<MapImage, ByteArray> {
        val dLat = halfM / Geo.M_PER_DEG_LAT
        val dLon = halfM / (Geo.M_PER_DEG_LAT * cos(Geo.rad(lat)))
        val img = MapImage(lat + dLat, lat - dLat, lon + dLon, lon - dLon, px, px)
        val rule = URLEncoder.encode("{\"rasterFunction\":\"Hillshade Gray\"}", "UTF-8")
        val url = URL(
            "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage" +
                "?bbox=${img.w},${img.s},${img.e},${img.n}&bboxSR=4326&imageSR=4326&size=$px,$px&format=jpg" +
                "&renderingRule=$rule&interpolation=RSP_BilinearInterpolation&adjustAspectRatio=false&f=image"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("map server returned ${conn.responseCode}")
            val type = conn.contentType ?: ""
            val bytes = conn.inputStream.use { it.readBytes() }
            if (!type.startsWith("image")) throw IllegalStateException("map server sent no picture")
            onlineImg(ctx).writeBytes(bytes)
            onlineMeta(ctx).writeText(JSONObject().put("n", img.n).put("s", img.s).put("e", img.e).put("w", img.w).put("pw", px).put("ph", px).toString())
            return Pair(img, bytes)
        } finally { conn.disconnect() }
    }
}
