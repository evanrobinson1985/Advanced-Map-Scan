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

    /** A package made by the web app (written by its test), when present. */
    @Test fun decodesWebAppPackage() {
        val f = File(System.getProperty("webPackage") ?: System.getenv("WEB_PACKAGE") ?: return)
        if (!f.exists()) return
        val p = WatchPackage.decode(f.readBytes())
        println("web package: ${p.name}, ${p.waypoints.size} waypoints, image ${p.image?.widthPx}x${p.image?.heightPx} (${p.imageBytes?.size} bytes)")
        assertTrue(p.waypoints.isNotEmpty())
        for (w in p.waypoints) { assertTrue(w.lat in -90.0..90.0); assertTrue(w.lon in -180.0..180.0) }
    }
}
