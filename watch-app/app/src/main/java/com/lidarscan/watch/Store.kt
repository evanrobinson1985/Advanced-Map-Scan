package com.lidarscan.watch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/** The received packages, the downloaded map areas and the settings, kept on the watch. */
object Store {
    private fun pkgFile(ctx: Context) = File(ctx.filesDir, "package.lwp")   // (before the history: moved into it)

    // ---- the history of received packages ----
    // Every package received is kept (history/<id>.lwp, with a summary in
    // <id>.json), so an earlier one can be put back on the map or deleted.
    // The one on the map is remembered by its id.
    private fun histDir(ctx: Context) = File(ctx.filesDir, "history").apply { mkdirs() }
    private fun histFile(ctx: Context, id: Long) = File(histDir(ctx), "$id.lwp")
    private fun histMeta(ctx: Context, id: Long) = File(histDir(ctx), "$id.json")

    /** Keeps a received package in the history and makes it the one on the map; returns its id. */
    fun addToHistory(ctx: Context, bytes: ByteArray, p: WatchPackage, receivedAt: Long = System.currentTimeMillis()): Long {
        var id = receivedAt
        while (histFile(ctx, id).exists()) id++
        val tmp = File(histDir(ctx), "$id.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(histFile(ctx, id))
        histMeta(ctx, id).writeText(JSONObject()
            .put("id", id).put("name", p.name).put("made", p.madeAt).put("received", receivedAt)
            .put("wps", p.waypoints.size).put("hillshade", p.imageBytes != null)
            .put("base", p.baseSrc ?: JSONObject.NULL).put("hasBase", p.baseBytes != null).put("bytes", bytes.size).toString())
        setCurrentId(ctx, id)
        return id
    }

    fun history(ctx: Context): List<HistoryEntry> {
        migrateOld(ctx)
        return (histDir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                HistoryEntry(o.getLong("id"), o.optString("name", "LiDAR scan"), o.optLong("made"), o.optLong("received"), o.optInt("wps"),
                    o.optBoolean("hillshade"), o.optBoolean("hasBase"), if (o.isNull("base")) null else o.optString("base"), o.optInt("bytes"))
            } catch (e: Exception) { null }
        }.sortedByDescending { it.receivedAt }
    }

    fun loadEntry(ctx: Context, id: Long): WatchPackage? = try {
        val f = histFile(ctx, id)
        if (f.exists()) WatchPackage.decode(f.readBytes()) else null
    } catch (e: Exception) { null }

    fun deleteEntry(ctx: Context, id: Long) {
        histFile(ctx, id).delete(); histMeta(ctx, id).delete()
        if (currentId(ctx) == id) setCurrentId(ctx, -1L)
    }

    fun currentId(ctx: Context) = prefs(ctx).getLong("currentPkg", -1L)
    fun setCurrentId(ctx: Context, id: Long) = prefs(ctx).edit().putLong("currentPkg", id).apply()

    /** The package on the map (the current history entry). */
    fun loadPackage(ctx: Context): WatchPackage? { migrateOld(ctx); val id = currentId(ctx); return if (id >= 0) loadEntry(ctx, id) else null }

    // a package kept by the version before the history becomes its first entry
    private fun migrateOld(ctx: Context) {
        val old = pkgFile(ctx)
        if (!old.exists()) return
        try {
            val bytes = old.readBytes()
            val p = WatchPackage.decode(bytes)
            addToHistory(ctx, bytes, p, old.lastModified())
        } catch (e: Exception) { /* unreadable: dropped */ }
        old.delete()
    }

    // ---- settings ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun headingUp(ctx: Context) = prefs(ctx).getBoolean("headingUp", false)
    fun setHeadingUp(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("headingUp", v).apply()
    fun onlineMaps(ctx: Context) = prefs(ctx).getBoolean("onlineMaps", true)
    fun setOnlineMaps(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("onlineMaps", v).apply()
    fun basemap(ctx: Context) = prefs(ctx).getString("basemap", "satellite") ?: "satellite"
    fun setBasemap(ctx: Context, v: String) = prefs(ctx).edit().putString("basemap", v).apply()
    fun hillshadeOpacity(ctx: Context) = prefs(ctx).getFloat("hillshadeOpacity", 0.7f)
    fun setHillshadeOpacity(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("hillshadeOpacity", v).apply()
    fun hiddenKinds(ctx: Context): Set<String> = prefs(ctx).getStringSet("hiddenKinds", emptySet())?.toSet() ?: emptySet()
    fun setHiddenKinds(ctx: Context, v: Set<String>) = prefs(ctx).edit().putStringSet("hiddenKinds", HashSet(v)).apply()
    fun keepAwake(ctx: Context) = prefs(ctx).getBoolean("keepAwake", false)
    fun setKeepAwake(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("keepAwake", v).apply()
    fun dimWhenDown(ctx: Context) = prefs(ctx).getBoolean("dimWhenDown", true)
    fun setDimWhenDown(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("dimWhenDown", v).apply()
    fun selectedWaypoint(ctx: Context): String? = prefs(ctx).getString("selected", null)
    fun setSelectedWaypoint(ctx: Context, v: String?) = prefs(ctx).edit().putString("selected", v).apply()
    fun mapZoom(ctx: Context) = prefs(ctx).getFloat("mapMpp", 1.2f)
    fun setMapZoom(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("mapMpp", v).apply()
    fun arriveM(ctx: Context) = prefs(ctx).getInt("arriveM", 10)
    fun setArriveM(ctx: Context, v: Int) = prefs(ctx).edit().putInt("arriveM", v).apply()

    // ---- the online hillshade: areas downloaded as you go ----
    // Each area is online/<id>.jpg with its edges in <id>.json; the oldest
    // are deleted past MAX_AREAS.
    private const val MAX_AREAS = 40
    private fun areaDir(ctx: Context) = File(ctx.filesDir, "online").apply { mkdirs() }

    /** The downloaded areas (edges and picture file), oldest first. */
    fun onlineAreas(ctx: Context): List<OnlineArea> {
        migrateOnline(ctx)
        return (areaDir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            try {
                val m = JSONObject(f.readText())
                val img = File(areaDir(ctx), f.name.removeSuffix(".json") + ".jpg")
                if (!img.exists()) null
                else OnlineArea(m.getLong("id"), MapImage(m.getDouble("n"), m.getDouble("s"), m.getDouble("e"), m.getDouble("w"), m.getInt("pw"), m.getInt("ph")), img)
            } catch (e: Exception) { null }
        }.sortedBy { it.id }
    }

    // the single area kept by the version before this one becomes the first
    private fun migrateOnline(ctx: Context) {
        val img = File(ctx.filesDir, "online.jpg"); val meta = File(ctx.filesDir, "online.json")
        if (!meta.exists()) return
        try {
            if (img.exists()) {
                val id = img.lastModified()
                img.renameTo(File(areaDir(ctx), "$id.jpg"))
                File(areaDir(ctx), "$id.json").writeText(JSONObject(meta.readText()).put("id", id).toString())
            }
        } catch (e: Exception) { }
        img.delete(); meta.delete()
    }

    /**
     * Downloads a USGS 3DEP hillshade (the same 1 m LiDAR the web app uses,
     * shaded by the USGS server) of a square around a point, when the watch
     * has Wi-Fi or LTE, and keeps it with the other areas. Blocking: call off
     * the main thread.
     */
    fun fetchOnline(ctx: Context, lat: Double, lon: Double, halfM: Double = 600.0, px: Int = 800): OnlineArea {
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
            var id = System.currentTimeMillis()
            while (File(areaDir(ctx), "$id.json").exists()) id++
            val f = File(areaDir(ctx), "$id.jpg")
            f.writeBytes(bytes)
            File(areaDir(ctx), "$id.json").writeText(JSONObject().put("id", id).put("n", img.n).put("s", img.s).put("e", img.e).put("w", img.w).put("pw", px).put("ph", px).toString())
            val all = onlineAreas(ctx)
            for (old in all.take(maxOf(0, all.size - MAX_AREAS))) deleteArea(ctx, old)
            return OnlineArea(id, img, f)
        } finally { conn.disconnect() }
    }

    private fun deleteArea(ctx: Context, a: OnlineArea) { a.file.delete(); File(areaDir(ctx), "${a.id}.json").delete() }

    /** Deletes every downloaded area. */
    fun clearOnline(ctx: Context) { areaDir(ctx).listFiles()?.forEach { it.delete() } }
}

/** One downloaded hillshade area. */
data class OnlineArea(val id: Long, val image: MapImage, val file: File)

/** One package kept in the history. */
data class HistoryEntry(
    val id: Long, val name: String, val madeAt: Long, val receivedAt: Long, val waypoints: Int,
    val hasHillshade: Boolean, val hasBase: Boolean, val baseSrc: String?, val bytes: Int,
)
