package com.lidarscan.watch

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances, bearings and the flat local projection the map draws with. */
object Geo {
    const val M_PER_DEG_LAT = 111320.0
    private const val EARTH_R = 6371008.8
    private const val FT_PER_M = 3.28084

    fun rad(d: Double) = d * PI / 180.0
    fun deg(r: Double) = r * 180.0 / PI

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north (0-360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm360(deg(atan2(y, x)))
    }

    fun norm360(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }

    /** Signed smallest turn from `from` to `to`, -180..180 degrees. */
    fun turn(from: Double, to: Double): Double {
        var d = norm360(to - from)
        if (d > 180) d -= 360
        return d
    }

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compassWord(deg: Double): String = POINTS[((norm360(deg) + 22.5) / 45.0).toInt() % 8]

    /** Feet and miles, as the web app shows them. */
    fun distanceText(m: Double): String {
        val ft = m * FT_PER_M
        return when {
            ft < 1000 -> "${ft.roundToInt()} ft"
            ft < 5280 * 10 -> String.format("%.2f mi", ft / 5280.0)
            else -> "${(ft / 5280.0).roundToInt()} mi"
        }
    }

    fun lengthText(m: Double?): String = if (m == null || m.isNaN()) "?" else {
        val ft = m * FT_PER_M
        if (ft < 10) String.format("%.1f ft", ft) else "${ft.roundToInt()} ft"
    }

    /**
     * A flat east/north metre grid around an origin: good to well under a
     * metre over the few kilometres a watch map covers.
     */
    class Local(val lat0: Double, val lon0: Double) {
        private val mPerDegLon = M_PER_DEG_LAT * cos(rad(lat0))
        fun east(lon: Double) = (lon - lon0) * mPerDegLon
        fun north(lat: Double) = (lat - lat0) * M_PER_DEG_LAT
        fun lon(east: Double) = lon0 + east / mPerDegLon
        fun lat(north: Double) = lat0 + north / M_PER_DEG_LAT
    }

    /**
     * Where a point lands on the screen: the view is centred on (cLat, cLon)
     * at `mPerPx` metres per pixel, turned by `rotDeg` (the heading, when the
     * map is turned to face the way you look; 0 = north up).
     */
    class View(
        val cLat: Double, val cLon: Double, val mPerPx: Double, val rotDeg: Double,
        val cx: Float, val cy: Float,
    ) {
        val local = Local(cLat, cLon)
        private val cr = cos(rad(-rotDeg))
        private val sr = sin(rad(-rotDeg))

        fun toScreen(lat: Double, lon: Double): Pair<Float, Float> {
            val e = local.east(lon) / mPerPx
            val n = local.north(lat) / mPerPx
            // screen y grows downwards; rotate by -rot so the heading points up
            val x = e * cr - (-n) * sr
            val y = e * sr + (-n) * cr
            return Pair((cx + x).toFloat(), (cy + y).toFloat())
        }

        fun toLatLon(sx: Float, sy: Float): Pair<Double, Double> {
            val x = (sx - cx).toDouble()
            val y = (sy - cy).toDouble()
            // inverse rotation
            val e = x * cr + y * sr
            val sDown = -x * sr + y * cr
            return Pair(local.lat(-sDown * mPerPx), local.lon(e * mPerPx))
        }
    }

    /**
     * Maps as you go, as the web app's scan as you go: the ground a quarter of
     * an area's width around you (`halfM` is half the width) is checked in 16
     * directions. If all of it lies on the maps the watch has (`areas`), null.
     * Otherwise the centre of the next area to download: shifted towards the
     * unmapped ground, so it covers the way ahead and you stay well inside it.
     */
    fun nextArea(lat: Double, lon: Double, areas: List<MapImage>, halfM: Double): Pair<Double, Double>? {
        fun on(la: Double, lo: Double) = areas.any { la < it.n && la > it.s && lo < it.e && lo > it.w }
        if (!on(lat, lon)) return Pair(lat, lon)
        val local = Local(lat, lon)
        val r = halfM / 2
        var ve = 0.0; var vn = 0.0; var misses = 0
        for (i in 0 until 16) {
            val a = 2 * PI * i / 16
            if (!on(local.lat(r * cos(a)), local.lon(r * sin(a)))) { ve += sin(a); vn += cos(a); misses++ }
        }
        if (misses == 0) return null
        val len = sqrt(ve * ve + vn * vn)
        if (len < 1e-6) return Pair(lat, lon)
        val shift = halfM / 2
        return Pair(local.lat(vn / len * shift), local.lon(ve / len * shift))
    }

    /** Web-map tiles (the usual z/x/y scheme): which tiles cover a place, and where a tile lies. */
    object Tiles {
        private const val MPP0 = 156543.03392   // metres per pixel at zoom 0 on the equator (256 px tiles)
        fun x(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
        fun y(lat: Double, z: Int): Double {
            val r = rad(lat.coerceIn(-85.05112878, 85.05112878))
            return (1.0 - Math.log(Math.tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
        }
        fun lon(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
        fun lat(y: Double, z: Int) = deg(Math.atan(Math.sinh(PI * (1 - 2 * y / (1 shl z)))))
        /** The zoom whose tiles are at least as sharp as the view (metres per screen pixel), up to maxZ. */
        fun zoomFor(mPerPx: Double, lat: Double, maxZ: Int): Int {
            val z = Math.ceil(Math.log(MPP0 * cos(rad(lat)) / mPerPx) / Math.log(2.0)).toInt()
            return z.coerceIn(1, maxZ)
        }
    }
}
