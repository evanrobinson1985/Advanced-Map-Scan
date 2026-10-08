package com.lidarscan.watch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

class LogicTest {
    @Test fun distanceAndBearing() {
        // Russell Cave visitor centre to Cottonpatch Mound: ~0.5 km to the north-east
        val d = Geo.distanceM(34.97440, -85.81420, 34.97798, -85.80988)
        assertEquals(560.0, d, 15.0)
        val b = Geo.bearingDeg(34.97440, -85.81420, 34.97798, -85.80988)
        assertEquals(45.0, b, 5.0)
        assertEquals("NE", Geo.compassWord(b))
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(1.0, Geo.distanceM(10.0, 10.0, 10.0 + 1 / 111194.9, 10.0), 0.01)
    }

    @Test fun turns() {
        assertEquals(20.0, Geo.turn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.turn(10.0, 350.0), 1e-9)
        assertEquals(180.0, Math.abs(Geo.turn(0.0, 180.0)), 1e-9)
        assertEquals(359.0, Geo.norm360(-1.0), 1e-9)
    }

    @Test fun textForms() {
        assertEquals("328 ft", Geo.distanceText(100.0))
        assertEquals("1.00 mi", Geo.distanceText(1609.344))
        assertEquals("3.3 ft", Geo.lengthText(1.0))
    }

    @Test fun viewNorthUp() {
        val v = Geo.View(35.0, -85.0, 2.0, 0.0, 240f, 240f)
        val (x0, y0) = v.toScreen(35.0, -85.0)
        assertEquals(240f, x0, 1e-3f); assertEquals(240f, y0, 1e-3f)
        // 100 m north is 50 px up; 100 m east is 50 px right
        val (_, yn) = v.toScreen(v.local.lat(100.0), -85.0)
        assertEquals(190f, yn, 0.01f)
        val (xe, _) = v.toScreen(35.0, v.local.lon(100.0))
        assertEquals(290f, xe, 0.01f)
    }

    @Test fun viewHeadingUp() {
        // facing east: a point 100 m east is straight up the screen
        val v = Geo.View(35.0, -85.0, 1.0, 90.0, 240f, 240f)
        val (x, y) = v.toScreen(35.0, v.local.lon(100.0))
        assertEquals(240f, x, 0.01f); assertEquals(140f, y, 0.01f)
        // and north is to the left
        val (xn, yn) = v.toScreen(v.local.lat(100.0), -85.0)
        assertEquals(140f, xn, 0.01f); assertEquals(240f, yn, 0.01f)
        // screen back to the map
        for (rot in listOf(0.0, 37.0, 200.0)) {
            val w = Geo.View(35.0, -85.0, 1.5, rot, 240f, 240f)
            val (sx, sy) = w.toScreen(35.001, -85.002)
            val (la, lo) = w.toLatLon(sx, sy)
            assertEquals(35.001, la, 1e-7); assertEquals(-85.002, lo, 1e-7)
        }
    }

    private fun pack(json: JSONObject, image: ByteArray?): ByteArray {
        val j = json.toString().toByteArray(Charsets.UTF_8)
        val o = ByteArrayOutputStream()
        o.write("LWP1".toByteArray()); o.write(ByteBuffer.allocate(4).putInt(j.size).array()); o.write(j); if (image != null) o.write(image)
        return o.toByteArray()
    }

    private fun sample(): JSONObject = JSONObject()
        .put("v", 1).put("name", "Russell Cave").put("made", 123L)
        .put("img", JSONObject().put("n", 34.98).put("s", 34.97).put("e", -85.80).put("w", -85.82).put("pw", 4).put("ph", 3))
        .put("wps", JSONArray()
            .put(JSONObject().put("id", "a").put("lat", 34.97798).put("lon", -85.80988).put("kind", "Mounds (deep learning)").put("letter", "D")
                .put("color", "#9333ea").put("score", 98).put("d", 18.2).put("h", 1.4).put("conf", true)
                .put("notes", JSONArray().put("On a 10° slope")).put("ol", JSONArray().put(JSONArray().put(34.9779).put(-85.8099)).put(JSONArray().put(34.978).put(-85.8098)).put(JSONArray().put(34.978).put(-85.8099))))
            .put(JSONObject().put("lat", 34.975).put("lon", -85.81).put("score", JSONObject.NULL)))

    @Test fun decodesPackage() {
        val img = byteArrayOf(1, 2, 3, 4, 5)
        val p = WatchPackage.decode(pack(sample(), img))
        assertEquals("Russell Cave", p.name)
        assertEquals(2, p.waypoints.size)
        val a = p.waypoints[0]
        assertEquals("D", a.letter); assertEquals(98, a.score); assertEquals(18.2, a.diameterM!!, 1e-9); assertTrue(a.confirmed)
        assertEquals(0xFF9333EA.toInt(), a.color); assertEquals(3, a.outline.size); assertEquals("On a 10° slope", a.notes[0])
        val b = p.waypoints[1]
        assertNull(b.score); assertNull(b.diameterM); assertEquals("w1", b.id)
        assertNotNull(p.image); assertEquals(4, p.image!!.widthPx)
        assertTrue(img.contentEquals(p.imageBytes))
    }

    @Test fun rejectsOtherFiles() {
        try { WatchPackage.decode("hello world, not a package".toByteArray()); fail() } catch (e: IllegalArgumentException) { }
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test fun reassemblesInAnyOrder() {
        val data = ByteArray(10_000) { (it * 31 + 7).toByte() }
        val r = Reassembly()
        r.begin(data.size, crc(data))
        val pieces = (0 until data.size step 495).toMutableList()
        pieces.reverse()                       // out of order
        pieces.add(pieces[3])                  // and one sent twice
        for (off in pieces) { val len = minOf(495, data.size - off); r.data(off, data.copyOfRange(off, off + len)) }
        assertEquals(0, r.missing())
        assertTrue(data.contentEquals(r.end()))
    }

    @Test fun reportsMissingAndDamaged() {
        val data = ByteArray(2000) { it.toByte() }
        val r = Reassembly()
        r.begin(data.size, crc(data))
        r.data(0, data.copyOfRange(0, 1000))
        assertEquals(1000, r.missing())
        try { r.end(); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("never arrived")) }
        val r2 = Reassembly()
        r2.begin(data.size, crc(data) xor 1L)
        r2.data(0, data)
        try { r2.end(); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("damaged")) }
    }

    @Test fun decodesBasemapAndViewSettings() {
        val hs = byteArrayOf(9, 8, 7); val sat = byteArrayOf(1, 2, 3, 4)
        val j = sample()
        j.getJSONObject("img").put("len", hs.size)
        j.put("base", JSONObject().put("src", "satellite").put("n", 34.98).put("s", 34.97).put("e", -85.80).put("w", -85.82).put("pw", 2).put("ph", 2).put("len", sat.size))
        j.put("view", JSONObject().put("base", "satellite").put("hs", 0.7))
        val p = WatchPackage.decode(pack(j, hs + sat))
        assertTrue(hs.contentEquals(p.imageBytes)); assertTrue(sat.contentEquals(p.baseBytes))
        assertEquals("satellite", p.baseSrc); assertNotNull(p.baseImage)
        assertEquals("satellite", p.viewBase); assertEquals(0.7f, p.viewHillshadeOpacity!!, 1e-6f)
        // an older package (hillshade only, no lengths) still reads
        val old = WatchPackage.decode(pack(sample(), hs))
        assertTrue(hs.contentEquals(old.imageBytes)); assertNull(old.baseBytes); assertNull(old.viewBase)
    }

    @Test fun tileMaths() {
        assertEquals(1.0, Geo.Tiles.x(0.0, 1), 1e-12); assertEquals(1.0, Geo.Tiles.y(0.0, 1), 1e-12)
        for (z in listOf(5, 12, 17)) {
            val x = Geo.Tiles.x(-85.8099, z); val y = Geo.Tiles.y(34.9780, z)
            assertEquals(-85.8099, Geo.Tiles.lon(x, z), 1e-9); assertEquals(34.9780, Geo.Tiles.lat(y, z), 1e-9)
        }
        // the grid's fixed points: 180W / 180E at its edges, the equator in the middle,
        // the web map's +-85.0511 degree limits at the top and bottom
        val n = (1 shl 14).toDouble()
        assertEquals(0.0, Geo.Tiles.x(-180.0, 14), 1e-9); assertEquals(n, Geo.Tiles.x(180.0, 14), 1e-9)
        assertEquals(n / 2, Geo.Tiles.y(0.0, 14), 1e-9)
        assertEquals(0.0, Geo.Tiles.y(85.05112878, 14), 0.01); assertEquals(n, Geo.Tiles.y(-85.05112878, 14), 0.01)
        // a 1.2 m/px view needs tiles at least that sharp: zoom 17 at this latitude (0.98 m/px), capped by the source
        assertEquals(17, Geo.Tiles.zoomFor(1.2, 34.978, 19)); assertEquals(16, Geo.Tiles.zoomFor(1.2, 34.978, 16))
    }

    @Test fun reportsGapsToRefill() {
        val data = ByteArray(1000) { it.toByte() }
        val r = Reassembly()
        r.begin(data.size, crc(data))
        assertEquals(listOf(Pair(0, 1000)), r.missingRanges())
        r.data(0, data.copyOfRange(0, 100)); r.data(300, data.copyOfRange(300, 400)); r.data(950, data.copyOfRange(950, 1000))
        r.data(300, data.copyOfRange(300, 350))   // sent twice: not counted twice
        assertEquals(listOf(Pair(100, 200), Pair(400, 550)), r.missingRanges())
        assertEquals(750, r.missing())
        assertEquals(listOf(Pair(100, 200)), r.missingRanges(1))
        for ((o, l) in r.missingRanges()) r.data(o, data.copyOfRange(o, o + l))
        assertTrue(r.missingRanges().isEmpty())
        assertTrue(data.contentEquals(r.end()))
    }

    private fun square(lat: Double, lon: Double, halfM: Double): MapImage {
        val l = Geo.Local(lat, lon)
        return MapImage(l.lat(halfM), l.lat(-halfM), l.lon(halfM), l.lon(-halfM), 800, 800)
    }

    @Test fun mapsAsYouGo() {
        val lat = 34.978; val lon = -85.81
        // no map: the area around you
        assertEquals(Pair(lat, lon), Geo.nextArea(lat, lon, emptyList(), 600.0))
        // in the middle of a map: nothing to do
        val a = square(lat, lon, 600.0)
        assertNull(Geo.nextArea(lat, lon, listOf(a), 600.0))
        // 400 m east of its centre (within 300 m of its east edge): the next area lies east, and you are inside it
        val l = Geo.Local(lat, lon)
        val pLon = l.lon(400.0)
        val c = Geo.nextArea(lat, pLon, listOf(a), 600.0)!!
        val here = Geo.Local(lat, pLon)
        assertEquals(300.0, here.east(c.second), 1.0); assertEquals(0.0, here.north(c.first), 1.0)
        val b = square(c.first, c.second, 600.0)
        // with that one too, you're covered
        assertNull(Geo.nextArea(lat, pLon, listOf(a, b), 600.0))
        // near the north-east corner: the next one goes north-east
        val d = Geo.nextArea(l.lat(450.0), l.lon(450.0), listOf(a), 600.0)!!
        assertTrue(d.first > l.lat(450.0) && d.second > l.lon(450.0))
    }

    /** A package made by the web app (written by its test), when present. */
    @Test fun decodesWebAppPackage() {
        val f = File(System.getProperty("webPackage") ?: System.getenv("WEB_PACKAGE") ?: return)
        if (!f.exists()) return
        val p = WatchPackage.decode(f.readBytes())
        println("web package: ${p.name}, ${p.waypoints.size} waypoints, image ${p.image?.widthPx}x${p.image?.heightPx} (${p.imageBytes?.size} bytes), basemap ${p.baseSrc} ${p.baseImage?.widthPx}x${p.baseImage?.heightPx} (${p.baseBytes?.size} bytes), view ${p.viewBase} ${p.viewHillshadeOpacity}")
        assertTrue(p.waypoints.isNotEmpty())
        for (w in p.waypoints) { assertTrue(w.lat in -90.0..90.0); assertTrue(w.lon in -180.0..180.0) }
    }
}
