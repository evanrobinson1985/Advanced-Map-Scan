package com.lidarscan.watch

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** One feature from the LiDAR scanner, with what the watch shows about it. */
data class Waypoint(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: String,          // e.g. "Mounds", "Effigies (deep learning)"
    val letter: String,        // the map letter the web app uses
    val color: Int,            // ARGB
    val score: Int?,           // confidence / match score, 0-100
    val diameterM: Double?,
    val heightM: Double?,
    val confirmed: Boolean,
    val notes: List<String>,   // the details from the result's popup
    val outline: List<Pair<Double, Double>>,
)

/** The hillshade picture and where it lies (its edges in degrees). */
data class MapImage(val n: Double, val s: Double, val e: Double, val w: Double, val widthPx: Int, val heightPx: Int)

data class WatchPackage(
    val name: String,
    val madeAt: Long,
    val image: MapImage?,
    val waypoints: List<Waypoint>,
    val imageBytes: ByteArray?,
    /** A basemap picture of the same kind of area (satellite imagery from the phone), if sent. */
    val baseImage: MapImage? = null,
    val baseBytes: ByteArray? = null,
    val baseSrc: String? = null,
    /** The web app's map settings when it was sent: its basemap and hillshade opacity. */
    val viewBase: String? = null,
    val viewHillshadeOpacity: Float? = null,
) {
    companion object {
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

        /**
         * The package the web app sends: "LWP1", the JSON's length (4 bytes,
         * big-endian), the JSON (UTF-8), then the pictures it lists, one after
         * the other: the hillshade ("img"), then the basemap ("base"), each
         * with its byte length ("len"; an older package has only the
         * hillshade, with no length, filling the rest).
         */
        fun decode(bytes: ByteArray): WatchPackage {
            require(bytes.size >= 8) { "too short" }
            for (i in 0 until 4) require(bytes[i] == MAGIC[i]) { "not a LiDAR watch package" }
            val jsonLen = ByteBuffer.wrap(bytes, 4, 4).int
            require(jsonLen in 2..(bytes.size - 8)) { "bad header" }
            val json = JSONObject(String(bytes, 8, jsonLen, Charsets.UTF_8))
            var off = 8 + jsonLen
            fun take(o: JSONObject?): ByteArray? {
                if (o == null || off >= bytes.size) return null
                val n = o.optInt("len", bytes.size - off)
                require(n >= 0 && off + n <= bytes.size) { "a picture runs past the end of the package" }
                return bytes.copyOfRange(off, off + n).also { off += n }
            }
            val imageBytes = take(json.optJSONObject("img"))
            val baseBytes = take(json.optJSONObject("base"))
            return fromJson(json, imageBytes, baseBytes)
        }

        private fun mapImage(o: JSONObject) = MapImage(o.getDouble("n"), o.getDouble("s"), o.getDouble("e"), o.getDouble("w"), o.optInt("pw"), o.optInt("ph"))

        fun fromJson(json: JSONObject, imageBytes: ByteArray?, baseBytes: ByteArray? = null): WatchPackage {
            val img = json.optJSONObject("img")
            val image = img?.let { mapImage(it) }
            val base = json.optJSONObject("base")
            val view = json.optJSONObject("view")
            val arr = json.optJSONArray("wps")
            val wps = ArrayList<Waypoint>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val notes = ArrayList<String>()
                o.optJSONArray("notes")?.let { a -> for (j in 0 until a.length()) notes.add(a.getString(j)) }
                val ol = ArrayList<Pair<Double, Double>>()
                o.optJSONArray("ol")?.let { a -> for (j in 0 until a.length()) { val p = a.getJSONArray(j); ol.add(Pair(p.getDouble(0), p.getDouble(1))) } }
                wps.add(
                    Waypoint(
                        id = o.optString("id", "w$i"),
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        kind = o.optString("kind", "Feature"),
                        letter = o.optString("letter", "?").take(2),
                        color = parseColor(o.optString("color", "#475569")),
                        score = if (o.has("score") && !o.isNull("score")) o.getDouble("score").toInt() else null,
                        diameterM = if (o.has("d") && !o.isNull("d")) o.getDouble("d") else null,
                        heightM = if (o.has("h") && !o.isNull("h")) o.getDouble("h") else null,
                        confirmed = o.optBoolean("conf", false),
                        notes = notes,
                        outline = ol,
                    )
                )
            }
            return WatchPackage(
                json.optString("name", "LiDAR scan"), json.optLong("made", 0L), image, wps, imageBytes,
                baseImage = if (base != null && baseBytes != null) mapImage(base) else null,
                baseBytes = if (base != null) baseBytes else null,
                baseSrc = base?.optString("src", "satellite"),
                viewBase = view?.optString("base")?.takeIf { it.isNotEmpty() },
                viewHillshadeOpacity = view?.let { if (it.has("hs")) it.getDouble("hs").toFloat() else null },
            )
        }

        /** "#rrggbb" (or "#aarrggbb") to ARGB; slate grey if unreadable. */
        fun parseColor(s: String): Int {
            val h = s.trim().removePrefix("#")
            return try {
                when (h.length) {
                    6 -> (0xFF000000L or h.toLong(16)).toInt()
                    8 -> h.toLong(16).toInt()
                    3 -> { val r = h[0].toString().repeat(2); val g = h[1].toString().repeat(2); val b = h[2].toString().repeat(2); (0xFF000000L or "$r$g$b".toLong(16)).toInt() }
                    else -> 0xFF475569.toInt()
                }
            } catch (e: NumberFormatException) { 0xFF475569.toInt() }
        }
    }
}

/**
 * Puts a package back together from the pieces the phone sends over
 * Bluetooth. BEGIN gives the size and CRC-32; each DATA piece carries its
 * offset (so a repeated piece does no harm); END checks it all arrived.
 */
class Reassembly {
    private var buf: ByteArray? = null
    private var got: java.util.BitSet? = null   // which bytes have arrived
    private var expectCrc = 0L
    var total = 0; private set
    var received = 0; private set

    fun begin(totalBytes: Int, crc: Long) {
        require(totalBytes in 1..(32 * 1024 * 1024)) { "size out of range" }
        buf = ByteArray(totalBytes); total = totalBytes; received = 0; expectCrc = crc
        got = java.util.BitSet(totalBytes)
    }

    fun data(offset: Int, bytes: ByteArray, from: Int = 0, len: Int = bytes.size - from) {
        val b = buf ?: throw IllegalStateException("no transfer under way")
        require(offset >= 0 && offset + len <= b.size) { "piece outside the package" }
        System.arraycopy(bytes, from, b, offset, len)
        val g = got!!
        received += len - g.get(offset, offset + len).cardinality()   // counts only bytes new to it
        g.set(offset, offset + len)
    }

    fun missing(): Int = total - received

    /**
     * The gaps still to fill, as (offset, length), the first `limit` of them:
     * after a fast send (pieces not confirmed one by one) the phone asks for
     * these and sends just them again.
     */
    fun missingRanges(limit: Int = 16): List<Pair<Int, Int>> {
        val g = got ?: return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var i = g.nextClearBit(0)
        while (i < total && out.size < limit) {
            val j = minOf(g.nextSetBit(i).let { if (it < 0) total else it }, total)
            out.add(Pair(i, j - i))
            i = g.nextClearBit(j)
        }
        return out
    }

    /** The whole package, or an exception saying what is wrong. */
    fun end(): ByteArray {
        val b = buf ?: throw IllegalStateException("no transfer under way")
        if (missing() > 0) throw IllegalStateException("${missing()} bytes of the package never arrived; send it again")
        val crc = CRC32().apply { update(b) }.value
        if (crc != expectCrc) throw IllegalStateException("the package arrived damaged (checksum mismatch); send it again")
        buf = null; got = null
        return b
    }

    companion object {
        const val OP_BEGIN: Byte = 1
        const val OP_DATA: Byte = 2
        const val OP_END: Byte = 3
        const val OP_QUERY: Byte = 4
    }
}
