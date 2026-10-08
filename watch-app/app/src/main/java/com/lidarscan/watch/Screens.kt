package com.lidarscan.watch

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Accent = Color(0xFF38BDF8)

@Composable
fun WatchApp(act: MainActivity) {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme {
        SwipeDismissableNavHost(navController = nav, startDestination = "map") {
            composable("map") { MapScreen(act, onList = { nav.navigate("list") }, onWaypoint = { nav.navigate("detail/$it") }, onGuide = { nav.navigate("guide/$it") }) }
            composable("list") { ListScreen(act, onWaypoint = { nav.navigate("detail/$it") }, onReceive = { nav.navigate("receive") }, onSettings = { nav.navigate("settings") }) }
            composable("detail/{id}") { e ->
                val id = e.arguments?.getString("id") ?: ""
                DetailScreen(act, id, onGuide = { nav.navigate("guide/$id") }, onMap = { act.model.selectedId = id; nav.popBackStack("map", false) })
            }
            composable("guide/{id}") { e -> GuideScreen(act, e.arguments?.getString("id") ?: "") }
            composable("receive") { ReceiveScreen(act, onDone = { nav.popBackStack("map", false) }) }
            composable("settings") { SettingsScreen(act) }
        }
    }
}

// ---------------------------------------------------------------- the map
@Composable
fun MapScreen(act: MainActivity, onList: () -> Unit, onWaypoint: (String) -> Unit, onGuide: (String) -> Unit) {
    val m = act.model
    val fix by act.tracker.fix.collectAsState()
    val heading by act.tracker.heading.collectAsState()
    var mpp by remember { mutableFloatStateOf(1.2f) }            // metres per screen pixel
    var follow by remember { mutableStateOf(true) }
    var panLat by remember { mutableStateOf(Double.NaN) }
    var panLon by remember { mutableStateOf(Double.NaN) }
    var size by remember { mutableIntStateOf(0) }

    LaunchedEffect(fix) { fix?.let { m.maybeFetchOnline(it) } }

    val pkg = m.pkg
    // just received: open on the package's area (waypoints and map), zoomed to fit
    LaunchedEffect(m.showPackageArea, size) {
        val p = m.pkg
        if (!m.showPackageArea || p == null || size == 0) return@LaunchedEffect
        m.showPackageArea = false
        val pts = p.waypoints.map { Pair(it.lat, it.lon) } + (p.image?.let { listOf(Pair(it.n, it.w), Pair(it.s, it.e)) } ?: emptyList())
        if (pts.isEmpty()) return@LaunchedEffect
        val n = pts.maxOf { it.first }; val s = pts.minOf { it.first }; val e = pts.maxOf { it.second }; val w = pts.minOf { it.second }
        val l = Geo.Local((n + s) / 2, (e + w) / 2)
        val span = max(l.north(n) - l.north(s), l.east(e) - l.east(w)).toFloat()
        follow = false; panLat = (n + s) / 2; panLon = (e + w) / 2
        mpp = (span / (size * 0.8f)).coerceIn(0.25f, 20f)
    }
    val (cLat, cLon) = when {
        follow && fix != null -> Pair(fix!!.lat, fix!!.lon)
        !panLat.isNaN() -> Pair(panLat, panLon)
        fix != null -> Pair(fix!!.lat, fix!!.lon)
        pkg?.image != null -> Pair((pkg.image.n + pkg.image.s) / 2, (pkg.image.e + pkg.image.w) / 2)
        pkg != null && pkg.waypoints.isNotEmpty() -> Pair(pkg.waypoints[0].lat, pkg.waypoints[0].lon)
        else -> Pair(0.0, 0.0)
    }
    val rot = if (m.headingUp) heading?.deg ?: 0.0 else 0.0
    val view = Geo.View(cLat, cLon, mpp.toDouble(), rot, size / 2f, size / 2f)
    val paints = remember { MapPaints() }
    // the gesture handlers live across frames: they read the current view from here
    val latest = remember { arrayOfNulls<Any>(2) }
    latest[0] = view; latest[1] = pkg

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = {
                        follow = false
                    }) { change, drag ->
                        change.consume()
                        // the place now under the centre moves with your finger
                        val v = latest[0] as Geo.View
                        val (la, lo) = v.toLatLon(v.cx - drag.x, v.cy - drag.y)
                        panLat = la; panLon = lo
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p ->
                            val view = latest[0] as Geo.View
                            val pkg = latest[1] as WatchPackage?
                            // the waypoint under your finger (within ~30 px)
                            val hit = pkg?.waypoints?.minByOrNull { w -> val (x, y) = view.toScreen(w.lat, w.lon); hypot(x - p.x, y - p.y) }
                            if (hit != null) {
                                val (x, y) = view.toScreen(hit.lat, hit.lon)
                                if (hypot(x - p.x, y - p.y) < 30f) { m.selectedId = hit.id; onWaypoint(hit.id) }
                            }
                        },
                        onDoubleTap = { mpp = max(0.25f, mpp / 2f) },
                    )
                }
        ) {
            size = min(this.size.width, this.size.height).toInt()
            drawIntoCanvas { c -> drawMap(c.nativeCanvas, m, view, fix, heading, paints) }
        }

        // top: the list; sides: zoom; bottom: heading-up and follow-me
        CompactButton(onClick = onList, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            colors = ButtonDefaults.secondaryButtonColors()) { Text("☰", fontSize = 14.sp) }
        CompactButton(onClick = { mpp = min(20f, mpp * 2f) }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp),
            colors = ButtonDefaults.secondaryButtonColors()) { Text("−", fontSize = 16.sp) }
        CompactButton(onClick = { mpp = max(0.25f, mpp / 2f) }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            colors = ButtonDefaults.secondaryButtonColors()) { Text("+", fontSize = 16.sp) }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val sel = m.selected
            val line = when {
                sel != null && fix != null -> "${sel.letter} " + Geo.distanceText(Geo.distanceM(fix!!.lat, fix!!.lon, sel.lat, sel.lon)) + " " +
                    Geo.compassWord(Geo.bearingDeg(fix!!.lat, fix!!.lon, sel.lat, sel.lon))
                m.locationDenied -> "Location is off for this app"
                fix == null -> "Finding GPS..."
                m.onlineNote.isNotEmpty() -> m.onlineNote
                // none on screen: say where the nearest one is
                pkg != null && pkg.waypoints.isNotEmpty() && pkg.waypoints.none { w -> val (x, y) = view.toScreen(w.lat, w.lon); x in 0f..(size.toFloat()) && y in 0f..(size.toFloat()) } -> {
                    val f = fix!!
                    val near = pkg.waypoints.minBy { Geo.distanceM(f.lat, f.lon, it.lat, it.lon) }
                    "Nearest ${near.letter} " + Geo.distanceText(Geo.distanceM(f.lat, f.lon, near.lat, near.lon)) + " " + Geo.compassWord(Geo.bearingDeg(f.lat, f.lon, near.lat, near.lon))
                }
                pkg == null -> "☰ › Receive from phone"
                else -> scaleText(mpp, size)
            }
            Text(line, fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.background(Color(0xAA000000), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp).width(150.dp))
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactButton(onClick = { m.toggleHeadingUp() }, colors = ButtonDefaults.secondaryButtonColors()) {
                    Text(if (m.headingUp) "▲" else "N", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                CompactButton(onClick = { follow = true; panLat = Double.NaN; panLon = Double.NaN },
                    colors = if (follow) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors()) {
                    Text("◎", fontSize = 13.sp)
                }
                if (sel != null) CompactButton(onClick = { onGuide(sel.id) }, colors = ButtonDefaults.primaryButtonColors()) { Text("➤", fontSize = 13.sp) }
            }
        }
    }
}

private fun scaleText(mpp: Float, size: Int): String {
    val across = mpp * size
    return "Map ${Geo.distanceText(across.toDouble())} across"
}

class MapPaints {
    val image = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.WHITE }
    val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF38BDF8.toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    val meEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.WHITE }
    val acc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302563EB }
    val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt(); textAlign = Paint.Align.CENTER; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
}

/** Draws a map picture so each of its pixels lands where the view puts that place. */
private fun drawImage(nc: android.graphics.Canvas, bmp: android.graphics.Bitmap, img: MapImage, view: Geo.View, p: Paint) {
    val l = view.local
    val kx = (l.east(img.e) - l.east(img.w)) / bmp.width
    val ky = (l.north(img.n) - l.north(img.s)) / bmp.height
    val mx = Matrix()
    mx.setScale(kx.toFloat(), ky.toFloat())
    mx.postTranslate(l.east(img.w).toFloat(), (-l.north(img.n)).toFloat())   // metres east, metres down (south)
    mx.postRotate((-view.rotDeg).toFloat())
    mx.postScale((1 / view.mPerPx).toFloat(), (1 / view.mPerPx).toFloat())
    mx.postTranslate(view.cx, view.cy)
    nc.drawBitmap(bmp, mx, p)
}

private fun drawMap(nc: android.graphics.Canvas, m: AppModel, view: Geo.View, fix: Fix?, heading: Heading?, p: MapPaints) {
    // the online map first, the received LiDAR hillshade over it
    m.online?.let { (img, bmp) -> drawImage(nc, bmp, img, view, p.image) }
    val pkg = m.pkg
    val pb = m.pkgBitmap
    if (pkg?.image != null && pb != null) drawImage(nc, pb, pkg.image, view, p.image)

    val wps = pkg?.waypoints ?: emptyList()
    // outlines when zoomed in enough to see them
    if (view.mPerPx <= 3.0) for (w in wps) if (w.outline.size >= 3) {
        val path = Path()
        w.outline.forEachIndexed { i, (la, lo) -> val (x, y) = view.toScreen(la, lo); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        p.outline.color = w.color
        nc.drawPath(path, p.outline)
    }
    // the line to the waypoint you chose
    val sel = m.selected
    if (sel != null && fix != null) {
        val (ax, ay) = view.toScreen(fix.lat, fix.lon)
        val (bx, by) = view.toScreen(sel.lat, sel.lon)
        nc.drawLine(ax, ay, bx, by, p.line)
    }
    for (w in wps) {
        val (x, y) = view.toScreen(w.lat, w.lon)
        if (x < -20 || y < -20 || x > view.cx * 2 + 20 || y > view.cy * 2 + 20) continue
        p.marker.color = w.color
        val r = if (w.id == m.selectedId) 15f else 12f
        nc.drawCircle(x, y, r, p.marker)
        if (w.id == m.selectedId) nc.drawCircle(x, y, r + 3f, p.ring)
        nc.drawText(w.letter, x, y + 6f, p.letter)
    }
    // you: accuracy circle, dot and the way you face
    if (fix != null) {
        val (x, y) = view.toScreen(fix.lat, fix.lon)
        nc.drawCircle(x, y, (fix.accM / view.mPerPx).toFloat().coerceAtMost(400f), p.acc)
        if (heading != null) {
            val a = Math.toRadians(heading.deg - view.rotDeg)
            val path = Path()
            val tip = 22.0
            fun pt(ang: Double, d: Double) = Pair((x + d * Math.sin(ang)).toFloat(), (y - d * Math.cos(ang)).toFloat())
            val (tx, ty) = pt(a, tip); val (lx, ly) = pt(a + 2.5, 11.0); val (rx, ry) = pt(a - 2.5, 11.0)
            path.moveTo(tx, ty); path.lineTo(lx, ly); path.lineTo(x, y); path.lineTo(rx, ry); path.close()
            nc.drawPath(path, p.me); nc.drawPath(path, p.meEdge)
        }
        nc.drawCircle(x, y, 8f, p.me); nc.drawCircle(x, y, 8f, p.meEdge)
    }
    // north, when the map is turned
    if (view.rotDeg != 0.0) {
        val a = Math.toRadians(-view.rotDeg)
        val r = view.cx - 30
        nc.drawText("N", (view.cx + r * Math.sin(a)).toFloat(), (view.cy - r * Math.cos(a) + 6).toFloat(), p.north)
    }
}

// ---------------------------------------------------------------- the list
@Composable
fun ListScreen(act: MainActivity, onWaypoint: (String) -> Unit, onReceive: () -> Unit, onSettings: () -> Unit) {
    val m = act.model
    val fix by act.tracker.fix.collectAsState()
    val state = rememberScalingLazyListState()
    val wps = (m.pkg?.waypoints ?: emptyList()).let { list ->
        val f = fix
        if (f != null) list.sortedBy { Geo.distanceM(f.lat, f.lon, it.lat, it.lon) } else list.sortedByDescending { it.score ?: 0 }
    }
    ScalingLazyColumn(Modifier.fillMaxSize(), state = state) {
        item { ListHeader { Text(m.pkg?.name ?: "LiDAR Guide", maxLines = 1) } }
        item {
            Chip(onClick = onReceive, label = { Text("Receive from phone") }, secondaryLabel = { Text("Waypoints and map") },
                colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        if (wps.isEmpty()) item { Text("No waypoints yet. Send them from the LiDAR web app (⌚ Send to watch).", fontSize = 12.sp, textAlign = TextAlign.Center) }
        items(wps) { w ->
            val f = fix
            val where = if (f != null) Geo.distanceText(Geo.distanceM(f.lat, f.lon, w.lat, w.lon)) + " " + Geo.compassWord(Geo.bearingDeg(f.lat, f.lon, w.lat, w.lon)) else ""
            Chip(
                onClick = { m.selectedId = w.id; onWaypoint(w.id) },
                label = { Text("${w.kind}${if (w.confirmed) " ✓" else ""}", maxLines = 1) },
                secondaryLabel = { Text(listOfNotNull(where.ifEmpty { null }, w.score?.let { "$it%" }).joinToString(" · "), maxLines = 1) },
                icon = { Letter(w) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Chip(onClick = onSettings, label = { Text("Settings") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun Letter(w: Waypoint, big: Boolean = false) {
    val s = if (big) 34.dp else 24.dp
    Box(Modifier.size(s).clip(CircleShape).background(Color(w.color)), contentAlignment = Alignment.Center) {
        Text(w.letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 16.sp else 12.sp)
    }
}

// ---------------------------------------------------------------- details
@Composable
fun DetailScreen(act: MainActivity, id: String, onGuide: () -> Unit, onMap: () -> Unit) {
    val m = act.model
    val w = m.pkg?.waypoints?.firstOrNull { it.id == id } ?: run { Text("This waypoint is gone."); return }
    val fix by act.tracker.fix.collectAsState()
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Letter(w, true); Spacer(Modifier.width(6.dp)); Text(w.kind, fontWeight = FontWeight.Bold, maxLines = 2) } }
        fix?.let { f ->
            item {
                Text(Geo.distanceText(Geo.distanceM(f.lat, f.lon, w.lat, w.lon)) + " " + Geo.compassWord(Geo.bearingDeg(f.lat, f.lon, w.lat, w.lon)) +
                    " (${Geo.bearingDeg(f.lat, f.lon, w.lat, w.lon).roundToInt()}°)", color = Accent)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onGuide, colors = ButtonDefaults.primaryButtonColors()) { Text("➤", fontSize = 18.sp) }
                Button(onClick = onMap, colors = ButtonDefaults.secondaryButtonColors()) { Text("Map", fontSize = 12.sp) }
            }
        }
        val facts = ArrayList<String>()
        w.score?.let { facts.add("Confidence: $it%") }
        if (w.confirmed) facts.add("Confirmed real by you")
        if (w.diameterM != null) facts.add("Across: ${Geo.lengthText(w.diameterM)}")
        if (w.heightM != null) facts.add("Height / depth: ${Geo.lengthText(w.heightM)}")
        facts.add(String.format("%.6f, %.6f", w.lat, w.lon))
        items(facts) { Text(it, fontSize = 13.sp, textAlign = TextAlign.Center) }
        items(w.notes) { Text(it, fontSize = 12.sp, color = Color(0xFFCBD5E1), textAlign = TextAlign.Center) }
    }
}

// ---------------------------------------------------------------- guide
@Composable
fun GuideScreen(act: MainActivity, id: String) {
    val m = act.model
    val w = m.pkg?.waypoints?.firstOrNull { it.id == id } ?: run { Text("This waypoint is gone."); return }
    val fix by act.tracker.fix.collectAsState()
    val heading by act.tracker.heading.collectAsState()
    var arrived by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { act.keepScreenOn(true); m.selectedId = id; onDispose { act.keepScreenOn(false) } }

    val f = fix
    val dist = f?.let { Geo.distanceM(it.lat, it.lon, w.lat, w.lon) }
    val brg = f?.let { Geo.bearingDeg(it.lat, it.lon, w.lat, w.lon) }
    // a buzz when you get there; armed again once you are twice as far away
    LaunchedEffect(dist == null, dist?.let { it <= m.arriveM }, dist?.let { it > 2 * m.arriveM }) {
        if (dist != null && dist <= m.arriveM && !arrived) { arrived = true; act.buzz(longArrayOf(0, 300, 150, 300, 150, 600)) }
        else if (dist != null && dist > 2 * m.arriveM) arrived = false
    }
    val turn = if (brg != null && heading != null) Geo.turn(heading!!.deg, brg) else null

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val r = min(cx, cy)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF334155.toInt() }
                nc.drawCircle(cx, cy, r - 6, ring)
                if (brg != null) {
                    // the arrow: towards the waypoint relative to where you face, or
                    // relative to north (marked) when the heading isn't known
                    val ang = Math.toRadians(turn ?: brg)
                    val color = when { arrived -> 0xFF22C55E.toInt(); turn != null && abs(turn) < 15 -> 0xFF38BDF8.toInt(); else -> 0xFFF59E0B.toInt() }
                    val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                    fun pt(a: Double, d: Float) = Pair(cx + d * Math.sin(a).toFloat(), cy - d * Math.cos(a).toFloat())
                    val len = r * 0.55f
                    val (tx, ty) = pt(ang, len); val (lx, ly) = pt(ang + 2.6, len * 0.55f); val (bx, by) = pt(ang + Math.PI, len * 0.25f); val (rx, ry) = pt(ang - 2.6, len * 0.55f)
                    val path = Path(); path.moveTo(tx, ty); path.lineTo(lx, ly); path.lineTo(bx, by); path.lineTo(rx, ry); path.close()
                    nc.drawPath(path, ap)
                    if (turn == null) {
                        val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFEF4444.toInt(); textAlign = Paint.Align.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
                        nc.drawText("N", cx, cy - r + 34, np)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (dist == null) "Finding GPS..." else if (arrived) "You're there" else Geo.distanceText(dist),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (arrived) Color(0xFF22C55E) else Color.White)
            if (brg != null) Text("${Geo.compassWord(brg)} ${brg.roundToInt()}°" + when {
                turn == null -> " · face N to aim"
                abs(turn) < 15 -> " · straight ahead"
                turn > 0 -> " · turn right ${turn.roundToInt()}°"
                else -> " · turn left ${(-turn).roundToInt()}°"
            }, fontSize = 12.sp, color = Color(0xFFCBD5E1), textAlign = TextAlign.Center)
            f?.let { Text("GPS ±${Geo.lengthText(it.accM.toDouble())}", fontSize = 10.sp, color = Color(0xFF94A3B8)) }
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Letter(w); Spacer(Modifier.width(4.dp)); Text(w.kind, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- receive
@Composable
fun ReceiveScreen(act: MainActivity, onDone: () -> Unit) {
    var status by remember { mutableStateOf("Getting ready...") }
    var got by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(act.hasBluetoothPermissions()) }
    var done by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.all { it }; if (!granted) status = "Allow Nearby devices for this app to receive from the phone." }
    LaunchedEffect(Unit) { if (!granted) ask.launch(act.bluetoothPermissions()) }
    if (granted) DisposableEffect(Unit) {
        act.keepScreenOn(true)
        val rx = BleReceiver(act.applicationContext,
            onStatus = { s -> status = s; if (s.startsWith("Received")) done = true },
            onProgress = { r, t -> got = r; total = t },
            onPackage = { bytes -> act.model.receive(bytes) })
        rx.start()
        onDispose { rx.stop(); act.keepScreenOn(false) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (total > 0 && !done) CircularProgressIndicator(progress = got.toFloat() / total, modifier = Modifier.fillMaxSize().padding(4.dp), strokeWidth = 6.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(if (done) "✓" else "⌚", fontSize = 26.sp)
            Text(status, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (total > 0 && !done) Text("${got / 1024} of ${total / 1024} KB", fontSize = 11.sp, color = Color(0xFF94A3B8))
            if (done) { Spacer(Modifier.height(6.dp)); Chip(onClick = onDone, label = { Text("Open the map") }, colors = ChipDefaults.primaryChipColors()) }
        }
    }
}

// ---------------------------------------------------------------- settings
@Composable
fun SettingsScreen(act: MainActivity) {
    val m = act.model
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Settings") } }
        item {
            ToggleChip(checked = m.headingUp, onCheckedChange = { m.toggleHeadingUp() }, label = { Text("Map faces your heading") },
                toggleControl = { Switch(checked = m.headingUp) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            ToggleChip(checked = m.onlineMaps, onCheckedChange = { m.toggleOnlineMaps() }, label = { Text("Download map when needed") },
                secondaryLabel = { Text("USGS hillshade, Wi-Fi / LTE") }, toggleControl = { Switch(checked = m.onlineMaps) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = { m.nextArrive() }, label = { Text("Arrival buzz: ${Geo.lengthText(m.arriveM.toDouble())}") },
                secondaryLabel = { Text("Tap to change") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        if (m.pkg != null) item {
            Chip(onClick = { m.deletePackage() }, label = { Text("Delete waypoints") },
                secondaryLabel = { Text(m.pkg?.name ?: "") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}
