@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.crisismesh.app

import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- helpers ----------

fun typeColor(t: String): Color = when (t) {
    Kind.SOS -> Color(0xFFD32F2F)
    Kind.REQUEST -> Color(0xFFF57C00)
    Kind.OFFER -> Color(0xFF388E3C)
    Kind.SAFE -> Color(0xFF00897B)
    Kind.ALERT -> Color(0xFF1565C0)
    else -> Color.Gray
}

fun typeLabel(t: String): String = when (t) {
    Kind.SOS -> "🆘 SOS"
    Kind.REQUEST -> "📦 NEED"
    Kind.OFFER -> "🤝 OFFER"
    Kind.SAFE -> "✅ SAFE"
    Kind.ALERT -> "📢 ALERT"
    else -> t
}

fun distBearing(from: Location, lat: Double, lon: Double): Pair<Float, Float> {
    val r = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, lat, lon, r)
    var b = r[1]
    if (b < 0f) b += 360f
    return Pair(r[0], b)
}

fun compass(b: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((b + 22.5f) / 45f).toInt() % 8]
}

fun fmtDist(m: Float): String =
    if (m < 1000f) "${m.toInt()} m" else "%.1f km".format(m / 1000f)

// Rounded to 5 m steps: GPS is never more exact than that.
fun fmtRound(m: Float): String =
    if (m < 1000f) "${(m / 5f).roundToInt() * 5} m" else "%.1f km".format(m / 1000f)

fun fmtBle(m: Float): String =
    if (m < 1f) "under 1 m"
    else if (m < 10f) "${m.roundToInt()} m"
    else "${(m / 5f).roundToInt() * 5} m"

fun ago(t: Long): String {
    val s = maxOf(0L, (System.currentTimeMillis() - t) / 1000L)
    return when {
        s < 60L -> "${s}s ago"
        s < 3600L -> "${s / 60}m ago"
        s < 86400L -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

fun bleFresh(r: PeerRow, now: Long): Boolean = r.bleDist >= 0f && now - r.bleAt < 12000L

fun gpsDist(me: Location?, r: PeerRow): Pair<Float, Float>? =
    if (me != null && r.hasLoc) distBearing(me, r.lat, r.lon) else null

fun sortDist(me: Location?, r: PeerRow, now: Long): Float =
    if (bleFresh(r, now)) r.bleDist else (gpsDist(me, r)?.first ?: Float.MAX_VALUE)

// ---------- SOS ----------

@Composable
fun SosTab() {
    var cat by remember { mutableStateOf(SOS_CATEGORIES[0]) }
    var people by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    val on = MeshEngine.running.value

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("What is the emergency?", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SOS_CATEGORIES.forEach { c ->
                FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("People affected: $people", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (people > 1) people = people - 1 }) { Text("-") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (people < 99) people = people + 1 }) { Text("+") }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Details (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                MeshEngine.send(Kind.SOS, cat, note.trim(), people)
                note = ""
            },
            enabled = on,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.fillMaxWidth().height(80.dp)
        ) {
            Text("SEND SOS", fontSize = 26.sp, color = Color.White)
        }
        if (!on) Text("Start the mesh first (Start button, top right).")
        OutlinedButton(
            onClick = {
                MeshEngine.send(Kind.SAFE, "Safe", note.trim(), people)
                note = ""
            },
            enabled = on,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("✅ I'm safe")
        }
    }
}

// ---------- Needs ----------

@Composable
fun NeedsTab() {
    var cat by remember { mutableStateOf(NEED_CATEGORIES[0]) }
    var qty by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    val on = MeshEngine.running.value

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Resources", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NEED_CATEGORIES.forEach { c ->
                FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("People / units: $qty", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (qty > 1) qty = qty - 1 }) { Text("-") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (qty < 999) qty = qty + 1 }) { Text("+") }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Details (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                MeshEngine.send(Kind.REQUEST, cat, note.trim(), qty)
                note = ""
            },
            enabled = on,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00)),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("📦 Request $cat", color = Color.White)
        }
        Button(
            onClick = {
                MeshEngine.send(Kind.OFFER, cat, note.trim(), qty)
                note = ""
            },
            enabled = on,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("🤝 Offer $cat", color = Color.White)
        }
        if (!on) Text("Start the mesh first (Start button, top right).")
    }
}

// ---------- Feed ----------

@Composable
fun FeedTab() {
    var filter by remember { mutableStateOf("ALL") }
    val me = MeshEngine.myLocation.value
    val filters = listOf("ALL", Kind.SOS, Kind.REQUEST, Kind.OFFER, Kind.SAFE, Kind.ALERT)

    val feed = MeshEngine.messages
        .filter { it.type != Kind.ACK && it.type != Kind.RESOLVE && it.type != Kind.AUDIO }
        .filter { filter == "ALL" || it.type == filter }
        .sortedWith(
            compareBy<MeshMsg> { MeshEngine.resolved[it.id] == true }
                .thenBy { rank(it.type) }
                .thenByDescending { it.time }
        )

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(if (f == "ALL") "All" else typeLabel(f)) }
                    )
                }
            }
        }
        if (feed.isEmpty()) {
            item { Text("No messages yet.") }
        }
        items(feed, key = { it.id }) { m ->
            MessageCard(m, me)
        }
    }
}

@Composable
fun MessageCard(m: MeshMsg, me: Location?) {
    val ctx = LocalContext.current
    val isResolved = MeshEngine.resolved[m.id] == true
    val acked = MeshEngine.ackedBy[m.id]
    val mine = m.pubKey == MeshEngine.myPubKey
    val isResponder = MeshEngine.role.value == "RESPONDER"
    val color = typeColor(m.type)

    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (isResolved) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(
                    typeLabel(m.type),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(ago(m.time), style = MaterialTheme.typography.bodySmall)
            }
            val unit = if (m.type == Kind.SOS || m.type == Kind.SAFE) "people" else "units"
            Text(
                m.category + (if (m.people > 0) " · ${m.people} $unit" else ""),
                style = MaterialTheme.typography.titleMedium
            )
            if (m.text.isNotBlank()) Text(m.text)
            Text(
                "From ${m.sender}" +
                    (if (m.role == "RESPONDER") " 🛡️ Responder" else "") +
                    " · 🔒 ${m.keyId} · ${m.hops} hops" +
                    (if (mine) " · you" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            if (m.hasLocation) {
                val prefix = if (me != null) {
                    val db = distBearing(me, m.lat, m.lon)
                    "${fmtRound(db.first)} ${compass(db.second)} · "
                } else ""
                Text(
                    "📍 $prefix${"%.5f".format(m.lat)}, ${"%.5f".format(m.lon)}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("📍 No location", style = MaterialTheme.typography.bodySmall)
            }
            if (isResolved) {
                Text("✔ Resolved", color = Color(0xFF388E3C), fontWeight = FontWeight.Bold)
            } else if (acked != null) {
                Text(
                    "🛡️ Help acknowledged by $acked",
                    color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Bold
                )
            }
            if (m.type == Kind.SOS) {
                val recs = MeshEngine.clipsFor(m)
                recs.forEachIndexed { i, c -> ClipRow(c, mine, i + 1, recs.size) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.hasLocation && (m.type == Kind.SOS || m.type == Kind.REQUEST)) {
                    OutlinedButton(onClick = {
                        try {
                            val uri = Uri.parse(
                                "geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}(${Uri.encode(m.category)})"
                            )
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "No maps app found", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Navigate") }
                }
                if (!isResolved && (m.type == Kind.SOS || m.type == Kind.REQUEST)) {
                    if (isResponder && acked == null && !mine) {
                        Button(onClick = { MeshEngine.ack(m) }) { Text("Acknowledge") }
                    }
                    if (isResponder || mine) {
                        Button(onClick = { MeshEngine.resolve(m) }) { Text("Resolve") }
                    }
                }
            }
        }
    }
}

// One call recording attached to an SOS: play button, or download progress.
@Composable
fun ClipRow(c: ClipMeta, mine: Boolean, index: Int, total: Int) {
    val ready = MeshEngine.clipReady[c.clipId] == true
    val progress = MeshEngine.clipProgress[c.clipId]
    val playing = ClipPlayer.playing.value == c.clipId
    val who = if (mine) "Your call recording" else "Call recording from the victim"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "🎙 $who" + (if (total > 1) " #$index" else "") + " · ${c.seconds} s",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (ready) {
            Button(onClick = { ClipPlayer.toggle(c.clipId, MeshEngine.clipFile(c.clipId)) }) {
                Text(if (playing) "■ Stop" else "▶ Play")
            }
        } else {
            Text(
                if (progress != null) "Receiving $progress%" else "Waiting for audio...",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ---------- Phones: nearby phones, distance in meters, call ----------

@Composable
fun PhonesTab() {
    val ranges = listOf(20f, 50f, 100f, 250f, 1000f)
    var idx by remember { mutableStateOf(1) }
    val range = ranges[idx]
    val me = MeshEngine.myLocation.value
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val on = MeshEngine.running.value
    val canCall = on && VoiceEngine.state.value == CallState.IDLE
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val paint = remember {
        android.graphics.Paint().apply {
            textSize = 30f
            isAntiAlias = true
        }
    }

    val rows = MeshEngine.rows()
        .sortedWith(compareBy<PeerRow> { !it.direct }.thenBy { sortDist(me, it, now) })
    val pts = MeshEngine.messages.filter {
        it.hasLocation && it.type != Kind.ACK && it.type != Kind.RESOLVE && it.type != Kind.AUDIO &&
            MeshEngine.resolved[it.id] != true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!on) Text("Start the mesh first (Start button, top right).")
        if (VoiceEngine.message.value.isNotEmpty()) {
            Text("Last call: ${VoiceEngine.message.value}", fontWeight = FontWeight.Bold)
        }

        Text("Nearby phones (${rows.size})", style = MaterialTheme.typography.titleMedium)
        if (rows.isEmpty()) {
            Text("No phones found yet. Start the app on the other phones. Keep Bluetooth, Location and Wi-Fi on.")
        }
        rows.forEach { r ->
            PhoneRowCard(r, me, now, canCall)
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Radar (GPS)  range ${fmtDist(range)}", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (idx > 0) idx = idx - 1 }) { Text("In") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (idx < ranges.size - 1) idx = idx + 1 }) { Text("Out") }
        }
        if (me == null) Text("Waiting for GPS...")

        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = minOf(size.width, size.height) / 2f
            val grid = Color.Gray.copy(alpha = 0.45f)
            for (i in 1..4) {
                drawCircle(grid, radius = r * i / 4f, center = c, style = Stroke(width = 2f))
            }
            drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2f)
            drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f)
            drawCircle(Color(0xFF2196F3), radius = 12f, center = c)
            if (me != null) {
                pts.forEach { m ->
                    val db = distBearing(me, m.lat, m.lon)
                    if (db.first <= range) {
                        val rad = Math.toRadians(db.second.toDouble())
                        val rr = r * (db.first / range)
                        val x = c.x + (rr * sin(rad)).toFloat()
                        val y = c.y - (rr * cos(rad)).toFloat()
                        drawCircle(typeColor(m.type), radius = 12f, center = Offset(x, y))
                    }
                }
                rows.forEach { p ->
                    val g = gpsDist(me, p)
                    if (g != null && g.first <= range) {
                        val rad = Math.toRadians(g.second.toDouble())
                        val rr = r * (g.first / range)
                        val x = c.x + (rr * sin(rad)).toFloat()
                        val y = c.y - (rr * cos(rad)).toFloat()
                        drawCircle(Color(0xFF00BCD4), radius = 14f, center = Offset(x, y))
                        drawIntoCanvas { canvas ->
                            paint.color = textColor
                            canvas.nativeCanvas.drawText(p.name, x + 18f, y + 8f, paint)
                        }
                    }
                }
            }
        }
        Text(
            "North is up · blue = you · cyan = phones · red/orange/green = SOS, needs, offers · rings every ${fmtDist(range / 4f)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Distance: the Bluetooth signal works indoors (about ±3 m). GPS works outdoors and can be off by 10-50 m inside buildings.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun PhoneRowCard(r: PeerRow, me: Location?, now: Long, canCall: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("📱 ${r.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (r.direct) "🔗 Linked, call available"
                    else if (r.hops >= 1) "↪ Reached through the mesh (${r.hops} hops), no direct link"
                    else "👀 In range, not linked yet",
                    style = MaterialTheme.typography.bodySmall
                )
                if (bleFresh(r, now)) {
                    Text("📶 ≈ ${fmtBle(r.bleDist)} away (Bluetooth signal)")
                }
                val g = gpsDist(me, r)
                if (g != null) {
                    val unc = ((me?.accuracy ?: 0f) + r.acc).roundToInt()
                    Text(
                        "📍 GPS: ≈ ${fmtRound(g.first)} ${compass(g.second)} (±$unc m)",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (r.gpsAt > 0L && !r.hasLoc) {
                    Text("📍 This phone has no GPS fix yet", style = MaterialTheme.typography.bodySmall)
                }
                if (r.gpsAt > 0L) {
                    Text(
                        "updated ${maxOf(0L, (now - r.gpsAt) / 1000L)}s ago",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (r.direct) {
                Button(
                    onClick = { VoiceEngine.startCall(r.endpointId ?: "", r.name) },
                    enabled = canCall && r.endpointId != null
                ) { Text("📞 Call") }
            }
        }
    }
}

// ---------- Me ----------

@Composable
fun MeTab() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(MeshEngine.myName.value) }
    var pin by remember { mutableStateOf("") }
    var alertText by remember { mutableStateOf("") }
    val isResp = MeshEngine.role.value == "RESPONDER"
    val on = MeshEngine.running.value

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("My identity", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name (other phones see this)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            MeshEngine.setName(name)
            Toast.makeText(ctx, "Saved. Stop and Start the mesh to apply the new name.", Toast.LENGTH_LONG).show()
        }) { Text("Save name") }
        Text(
            "Key ID: ${MeshEngine.myKeyId} (secure-storage key, signs every message)",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()
        Text(
            "Role: " + (if (isResp) "🛡️ Responder" else "Citizen"),
            style = MaterialTheme.typography.titleMedium
        )
        if (!isResp) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text("Responder PIN (demo)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                if (pin == RESPONDER_PIN) {
                    MeshEngine.setRole("RESPONDER")
                    pin = ""
                } else {
                    Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Enter responder mode") }
        } else {
            OutlinedTextField(
                value = alertText,
                onValueChange = { alertText = it },
                label = { Text("Official alert to broadcast") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    MeshEngine.send(Kind.ALERT, "Official", alertText.trim(), 0)
                    alertText = ""
                },
                enabled = on && alertText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("📢 Broadcast alert", color = Color.White) }
            OutlinedButton(onClick = { MeshEngine.setRole("CITIZEN") }) {
                Text("Leave responder mode")
            }
        }

        HorizontalDivider()
        Text("Quick access and recording", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shake the phone to open CrisisMesh")
                Text(
                    "Works while the mesh is running, even with the screen off. Uses a little extra battery.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = MeshEngine.shakeEnabled.value, onCheckedChange = { MeshEngine.setShake(it) })
        }
        if (MeshEngine.shakeEnabled.value) {
            if (!Settings.canDrawOverlays(ctx)) {
                Text(
                    "Android 10+ only lets an app open itself from the background if you allow \"Display over other apps\". Without it you get a tap-to-open banner instead.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Open Settings > Apps > CrisisMesh > Display over other apps", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Allow display over other apps") }
            }
            if (Build.VERSION.SDK_INT >= 34 &&
                !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            ) {
                Text(
                    "To wake a locked screen, allow full-screen notifications for CrisisMesh.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Open Settings > Apps > CrisisMesh > Notifications", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Allow full-screen alerts") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Add my call recording to my SOS")
                Text(
                    "If you have an open SOS and you make or take a call, your voice (first 30 s) is recorded and added to that SOS for responders to play. Only your side is recorded. No call = the SOS stays as it is.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = MeshEngine.recordCalls.value, onCheckedChange = { MeshEngine.setRecordCalls(it) })
        }

        HorizontalDivider()
        Text("Mesh stats", style = MaterialTheme.typography.titleMedium)
        Text("Linked phones: ${MeshEngine.peerLinks.size}")
        Text("Nodes seen: ${MeshEngine.nodes.size}")
        Text("Stored messages: ${MeshEngine.messages.size}")
        Text("Relayed for others: ${MeshEngine.relayed.value}")
        Text("Forged / invalid dropped: ${MeshEngine.rejected.value}")

        HorizontalDivider()
        Text("Security demo", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { MeshEngine.sendForged() }, enabled = on) {
            Text("Send a forged message")
        }
        Text(
            "Other phones drop it and their 'Forged / invalid dropped' counter goes up.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { MeshEngine.clearAll() }) { Text("Clear all messages") }
    }
}
