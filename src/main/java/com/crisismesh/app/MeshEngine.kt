package com.crisismesh.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object MeshEngine {
    private const val SERVICE_ID = "com.crisismesh.app.MESH"
    private const val TTL_START = 8
    // New channel id: Android never changes sound/vibration of an existing channel.
    private const val CH_ALERT = "crisismesh_sos_v2"
    private const val CH_ALERT_OLD = "crisismesh_alerts"
    private const val MAX_CLIPS_PER_SOS = 3
    private const val NOTIFY_MAX_AGE_MS = 30L * 60L * 1000L   // do not ring for old messages synced from a neighbour
    const val CH_SERVICE = "crisismesh_service"
    const val CH_CALL = "crisismesh_calls"

    private lateinit var app: Application
    private lateinit var client: ConnectionsClient
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())

    // ---- state shown in the UI ----
    val running = mutableStateOf(false)
    val error = mutableStateOf("")
    val myName = mutableStateOf("")
    val role = mutableStateOf("CITIZEN")
    val myLocation = mutableStateOf<Location?>(null)
    val peerLinks = mutableStateMapOf<String, String>()   // endpointId -> "name#keyId" (directly linked phones)
    val nearby = mutableStateMapOf<String, PeerInfo>()    // keyId -> last GPS beacon
    val messages = mutableStateListOf<MeshMsg>()
    val ackedBy = mutableStateMapOf<String, String>()
    val resolved = mutableStateMapOf<String, Boolean>()
    val nodes = mutableStateMapOf<String, Long>()
    val relayed = mutableStateOf(0)
    val rejected = mutableStateOf(0)
    val notice = mutableStateOf("")                          // short green info line in the status card
    val shakeEnabled = mutableStateOf(true)
    val recordCalls = mutableStateOf(true)
    val requestedTab = mutableStateOf(-1)                    // set by shake / notification taps
    @Volatile var uiVisible = false                          // true while MainActivity is on screen
    val clips = mutableStateMapOf<String, List<ClipMeta>>()  // SOS id -> recordings attached to it
    val clipReady = mutableStateMapOf<String, Boolean>()     // clip id -> audio file is on this phone
    val clipProgress = mutableStateMapOf<String, Int>()      // clip id -> % received so far
    var myPubKey = ""
        private set
    var myKeyId = ""
        private set

    // ---- internal state ----
    private val seen = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()
    private val recent = mutableMapOf<String, MutableList<Long>>()
    private val clipById = mutableMapOf<String, ClipMeta>()
    private val clipParts = mutableMapOf<String, Array<ByteArray?>>()
    private var lastAlarmAt = -1_000_000L

    fun init(application: Application) {
        if (ready) return
        app = application
        client = Nearby.getConnectionsClient(app)
        fused = LocationServices.getFusedLocationProviderClient(app)
        prefs = app.getSharedPreferences("crisismesh", Context.MODE_PRIVATE)
        var n = prefs.getString("name", null)
        if (n == null) {
            n = "User-" + UUID.randomUUID().toString().take(4)
            prefs.edit().putString("name", n).apply()
        }
        myName.value = n
        role.value = prefs.getString("role", "CITIZEN") ?: "CITIZEN"
        shakeEnabled.value = prefs.getBoolean("shake", true)
        recordCalls.value = prefs.getBoolean("rec_calls", true)
        myPubKey = Crypto.publicKeyB64()
        myKeyId = Crypto.shortId(myPubKey)
        VoiceEngine.init(app)
        createChannels()
        load()
        ready = true
    }

    fun setName(n: String) {
        val v = n.trim().take(20)
        if (v.isNotEmpty()) {
            myName.value = v
            prefs.edit().putString("name", v).apply()
        }
    }

    fun setRole(r: String) {
        role.value = r
        prefs.edit().putString("role", r).apply()
    }

    fun setShake(on: Boolean) {
        shakeEnabled.value = on
        prefs.edit().putBoolean("shake", on).apply()
        if (running.value) {
            if (on) ShakeWatcher.start(app) else ShakeWatcher.stop()
        }
    }

    fun setRecordCalls(on: Boolean) {
        recordCalls.value = on
        prefs.edit().putBoolean("rec_calls", on).apply()
    }

    private fun label() = myName.value.replace("#", "") + "#" + myKeyId

    // ---- Nearby Connections ----
    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            client.acceptConnection(id, payloadCallback)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            pending.remove(id)
            if (result.status.isSuccess) {
                peerLinks[id] = names[id] ?: id
                sendSync(id)
                sendPresence()
            }
        }

        override fun onDisconnected(id: String) {
            peerLinks.remove(id)
            pending.remove(id)
            VoiceEngine.onPeerGone(id)
            // Search again so the phone is picked up when it comes back.
            handler.postDelayed({ restartDiscovery() }, 1500L)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (peerLinks.containsKey(id) || pending.contains(id)) return
            // Only the phone with the smaller label starts the connection (avoids double connects).
            if (label() >= info.endpointName) return
            pending.add(id)
            client.requestConnection(label(), id, connectionCallback)
                .addOnFailureListener { pending.remove(id) }
        }

        override fun onEndpointLost(id: String) {
            pending.remove(id)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(from: String, payload: Payload) {
            val b = payload.asBytes() ?: return
            if (b.size < 2) return
            when (b[0]) {
                TAG_MESH -> {
                    val m = try {
                        MeshMsg.fromJson(JSONObject(String(b, 1, b.size - 1, Charsets.UTF_8)))
                    } catch (e: Exception) {
                        return
                    }
                    handleIncoming(m, from)
                }
                TAG_CALL -> VoiceEngine.onControl(from, String(b, 1, b.size - 1, Charsets.UTF_8))
                TAG_AUDIO -> VoiceEngine.onAudio(from, b)
                TAG_PRESENCE -> handlePresence(from, String(b, 1, b.size - 1, Charsets.UTF_8))
                TAG_CLIP -> handleClip(from, b)
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun restartDiscovery() {
        if (!running.value) return
        try {
            client.stopDiscovery()
            val disc = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            client.startDiscovery(SERVICE_ID, discoveryCallback, disc)
        } catch (e: Exception) {
        }
    }

    // ---- raw sending ----
    fun sendRaw(endpointId: String, data: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(data))
    }

    private fun broadcastRaw(data: ByteArray, except: String?) {
        val targets = peerLinks.keys.filter { it != except }
        if (targets.isEmpty()) return
        client.sendPayload(targets, Payload.fromBytes(data))
    }

    // ---- presence: name + GPS of every phone, shared every few seconds ----
    private val presenceTask = object : Runnable {
        override fun run() {
            if (!running.value) return
            sendPresence()
            cleanupNearby()
            handler.postDelayed(this, 4000L)
        }
    }

    private fun sendPresence() {
        if (peerLinks.isEmpty()) return
        val loc = myLocation.value
        val o = JSONObject()
        o.put("k", myKeyId)
        o.put("n", myName.value)
        o.put("lat", loc?.latitude ?: 0.0)
        o.put("lon", loc?.longitude ?: 0.0)
        o.put("acc", (loc?.accuracy ?: 0f).toDouble())
        o.put("ts", System.currentTimeMillis())
        o.put("ttl", 2)
        o.put("h", 0)
        broadcastRaw(byteArrayOf(TAG_PRESENCE) + o.toString().toByteArray(), null)
    }

    private fun handlePresence(from: String, s: String) {
        try {
            val o = JSONObject(s)
            val k = o.getString("k")
            if (k == myKeyId) return
            val ts = o.getLong("ts")
            val prev = nearby[k]
            if (prev != null && ts <= prev.ts) return
            val h = o.getInt("h")
            val ttl = o.getInt("ttl")
            nearby[k] = PeerInfo(
                keyId = k,
                name = o.getString("n"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                acc = o.getDouble("acc").toFloat(),
                ts = ts,
                seenAt = System.currentTimeMillis(),
                hops = h + 1
            )
            if (ttl > 0) {
                o.put("ttl", ttl - 1)
                o.put("h", h + 1)
                broadcastRaw(byteArrayOf(TAG_PRESENCE) + o.toString().toByteArray(), from)
            }
        } catch (e: Exception) {
        }
    }

    private fun cleanupNearby() {
        val now = System.currentTimeMillis()
        val old = nearby.filter { now - it.value.seenAt > 300_000L }.keys.toList()
        old.forEach { nearby.remove(it) }
    }

    // Everything known about phones around me (linked, beaconing, or only heard on Bluetooth).
    fun rows(): List<PeerRow> {
        val now = System.currentTimeMillis()
        val map = LinkedHashMap<String, PeerRow>()
        for ((k, b) in Proximity.readings) {
            if (now - b.seenAt > 12_000L) continue
            map[k] = PeerRow(k, b.name, null, 0, 0.0, 0.0, 0f, 0L, b.dist, b.seenAt)
        }
        for ((k, p) in nearby) {
            if (now - p.seenAt > 120_000L) continue
            val old = map[k]
            map[k] = PeerRow(
                k, p.name, null, p.hops, p.lat, p.lon, p.acc, p.seenAt,
                old?.bleDist ?: -1f, old?.bleAt ?: 0L
            )
        }
        for ((ep, label) in peerLinks) {
            val k = label.substringAfter('#', "")
            val nm = label.substringBefore('#')
            val old = map[k]
            map[k] = PeerRow(
                k, nm, ep, 1,
                old?.lat ?: 0.0, old?.lon ?: 0.0, old?.acc ?: 0f, old?.gpsAt ?: 0L,
                old?.bleDist ?: -1f, old?.bleAt ?: 0L
            )
        }
        return map.values.toList()
    }

    // ---- receive, verify, store, relay emergency messages ----
    private fun allowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val l = recent.getOrPut(key) { mutableListOf() }
        l.removeAll { now - it > 60_000L }
        if (l.size >= 40) return false
        l.add(now)
        return true
    }

    private fun handleIncoming(m: MeshMsg, from: String) {
        if (seen.contains(m.id)) return
        if (m.type == Kind.ALERT && m.role != "RESPONDER") return
        if (!Crypto.verify(m.signable(), m.sig, m.pubKey)) {
            rejected.value = rejected.value + 1
            return
        }
        if (!allowed(m.keyId)) return
        seen.add(m.id)
        nodes[m.keyId] = System.currentTimeMillis()
        val r = m.copy(hops = m.hops + 1, ttl = minOf(m.ttl, TTL_START))
        addMessage(r)
        applyStatus(r)
        notifyIncoming(r)
        if (r.ttl > 0) {
            forward(r.copy(ttl = r.ttl - 1), from)
            relayed.value = relayed.value + 1
        }
    }

    private fun forward(m: MeshMsg, except: String?) {
        broadcastRaw(byteArrayOf(TAG_MESH) + m.toJson().toString().toByteArray(), except)
    }

    // Give a newly linked phone everything we know (store-and-forward).
    private fun sendSync(endpointId: String) {
        val list = messages
            .filter { it.ttl > 0 && resolved[it.id] != true }
            .filter { !(it.type == Kind.AUDIO && resolved[it.ref] == true) }
            .sortedBy { rank(it.type) }
            .take(80)
        for (m in list) {
            val out = m.copy(ttl = m.ttl - 1)
            sendRaw(endpointId, byteArrayOf(TAG_MESH) + out.toJson().toString().toByteArray())
        }
        // Recordings of SOS messages that are still open (newest 3), sent after their signed descriptions.
        val open = messages.filter { it.type == Kind.SOS && resolved[it.id] != true }.map { it.id }.toSet()
        clipById.values.toList().reversed()
            .filter { it.sosId in open && clipReady[it.clipId] == true }
            .take(3)
            .forEach { c ->
                try {
                    val f = clipFile(c.clipId)
                    if (f.exists()) sendClip(c.clipId, f.readBytes(), 2, endpointId)
                } catch (e: Exception) {
                }
            }
    }

    private fun addMessage(m: MeshMsg) {
        messages.add(m)
        while (messages.size > 300) messages.removeAt(0)
        scheduleSave()
    }

    private fun applyStatus(m: MeshMsg) {
        if (m.type == Kind.AUDIO) {
            val c = parseClip(m) ?: return
            if (clipById.containsKey(c.clipId)) return
            val list = clips[c.sosId] ?: emptyList()
            if (list.size >= MAX_CLIPS_PER_SOS) return
            clipById[c.clipId] = c
            clips[c.sosId] = list + c
            if (clipFile(c.clipId).exists()) clipReady[c.clipId] = true
            return
        }
        if (m.type == Kind.ACK && m.role == "RESPONDER") {
            ackedBy[m.ref] = m.sender
        }
        if (m.type == Kind.RESOLVE) {
            val target = messages.firstOrNull { it.id == m.ref }
            if (m.role == "RESPONDER" || (target != null && target.pubKey == m.pubKey)) {
                resolved[m.ref] = true
            }
        }
    }

    // ---- sending emergency messages ----
    private fun build(type: String, category: String, text: String, people: Int, ref: String): MeshMsg {
        val loc = myLocation.value
        val base = MeshMsg(
            id = UUID.randomUUID().toString(),
            type = type,
            category = category,
            sender = myName.value,
            role = role.value,
            text = text,
            lat = loc?.latitude ?: 0.0,
            lon = loc?.longitude ?: 0.0,
            people = people,
            time = System.currentTimeMillis(),
            ref = ref,
            pubKey = myPubKey,
            sig = "",
            ttl = TTL_START,
            hops = 0
        )
        return base.copy(sig = Crypto.sign(base.signable()))
    }

    fun send(type: String, category: String, text: String, people: Int, ref: String = "") {
        val m = build(type, category, text, people, ref)
        seen.add(m.id)
        addMessage(m)
        applyStatus(m)
        forward(m, null)
    }

    fun ack(target: MeshMsg) = send(Kind.ACK, target.category, "", 0, target.id)

    fun resolve(target: MeshMsg) = send(Kind.RESOLVE, target.category, "", 0, target.id)

    // Security demo: a message changed after signing. Other phones must drop it.
    fun sendForged() {
        val m = build(Kind.SOS, "Other", "original text", 1, "")
        forward(m.copy(text = "TAMPERED MESSAGE"), null)
    }

    fun clearAll() {
        messages.clear()
        seen.clear()
        ackedBy.clear()
        resolved.clear()
        nodes.clear()
        clips.clear()
        clipById.clear()
        clipParts.clear()
        clipReady.clear()
        clipProgress.clear()
        ClipPlayer.stop()
        try {
            File(app.filesDir, "clips").deleteRecursively()
        } catch (e: Exception) {
        }
        relayed.value = 0
        rejected.value = 0
        scheduleSave()
    }

    // ---- start / stop ----
    fun start() {
        if (running.value) return
        error.value = ""
        val adv = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val disc = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(label(), SERVICE_ID, connectionCallback, adv)
            .addOnFailureListener { error.value = "Advertising failed: ${it.message}" }
        client.startDiscovery(SERVICE_ID, discoveryCallback, disc)
            .addOnFailureListener { error.value = "Discovery failed: ${it.message}" }
        startLocation()
        Proximity.start(app, myKeyId, myName.value)
        running.value = true
        handler.removeCallbacks(presenceTask)
        handler.post(presenceTask)
        try {
            ContextCompat.startForegroundService(app, Intent(app, MeshService::class.java))
        } catch (e: Exception) {
            error.value = "Background service failed: ${e.message}"
        }
    }

    fun stop() {
        if (VoiceEngine.state.value != CallState.IDLE) VoiceEngine.endCall(true)
        running.value = false
        ShakeWatcher.stop()
        handler.removeCallbacks(presenceTask)
        Proximity.stop()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
        }
        peerLinks.clear()
        pending.clear()
        app.stopService(Intent(app, MeshService::class.java))
    }

    // ---- location ----
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation
            if (l != null) myLocation.value = l
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { l ->
                if (l != null && myLocation.value == null) myLocation.value = l
            }
        } catch (e: Exception) {
            error.value = "Location failed: ${e.message}"
        }
    }

    // ---- storage ----
    private val saveTask = Runnable { persist() }

    private fun scheduleSave() {
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, 1500L)
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            messages.forEach { arr.put(it.toJson()) }
            File(app.filesDir, "mesh_store.json").writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun load() {
        try {
            val f = File(app.filesDir, "mesh_store.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val cutoff = System.currentTimeMillis() - 48L * 3600L * 1000L
            for (i in 0 until arr.length()) {
                val m = MeshMsg.fromJson(arr.getJSONObject(i))
                if (m.time < cutoff) continue
                messages.add(m)
                seen.add(m.id)
            }
            pruneClips(cutoff)
            messages.forEach { applyStatus(it) }
        } catch (e: Exception) {
        }
    }

    // ---- notifications ----
    private fun createChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_ALERT_OLD)
        val alarm: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alert = NotificationChannel(CH_ALERT, "SOS and official alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.description = "Alarm sound and vibration when an SOS or official alert reaches this phone"
        alert.setSound(alarm, attrs)
        alert.enableVibration(true)
        alert.vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 900)
        alert.enableLights(true)
        alert.lightColor = android.graphics.Color.RED
        alert.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alert)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Mesh running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    // Rings and vibrates when an SOS or an official alert arrives from another phone.
    @SuppressLint("MissingPermission")
    private fun notifyIncoming(m: MeshMsg) {
        if (m.type != Kind.SOS && m.type != Kind.ALERT) return
        // Neighbours send old stored messages when a link starts: do not ring for those.
        if (System.currentTimeMillis() - m.time > NOTIFY_MAX_AGE_MS || resolved[m.id] == true) return
        try {
            val isAlert = m.type == Kind.ALERT
            var where = ""
            val me = myLocation.value
            if (me != null && m.hasLocation) {
                val db = distBearing(me, m.lat, m.lon)
                where = " · ${fmtRound(db.first)} ${compass(db.second)} from you"
            }
            val title = if (isAlert) "OFFICIAL ALERT"
            else "SOS: ${m.category}" + (if (m.people > 0) " · ${m.people} people" else "")
            val body = m.sender + (if (m.text.isNotBlank()) " - ${m.text}" else " needs help") + where

            val open = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TAB, 2)
            val pi = PendingIntent.getActivity(
                app, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = NotificationCompat.Builder(app, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFFD32F2F.toInt())
                .setContentIntent(pi)
                .setAutoCancel(true)
            // Several SOS within 15 s: show them all, but only the first one makes noise.
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlarmAt < 15_000L) b.setSilent(true) else lastAlarmAt = now
            if (isAlert) b.setFullScreenIntent(pi, true)
            if (m.hasLocation && !isAlert) {
                val geo = Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}(${Uri.encode(m.category)})")
                val nav = PendingIntent.getActivity(
                    app, m.id.hashCode(),
                    Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE
                )
                b.addAction(android.R.drawable.ic_menu_directions, "Navigate", nav)
            }
            NotificationManagerCompat.from(app).notify(m.id.hashCode(), b.build())
        } catch (e: Exception) {
        }
    }

    // ---- call recordings attached to an SOS ----
    fun clipFile(clipId: String) = File(File(app.filesDir, "clips"), "$clipId.ulaw")

    // Recordings shown on an SOS card. Only the SOS sender's own signed recordings count.
    fun clipsFor(m: MeshMsg): List<ClipMeta> = clips[m.id]?.filter { it.ownerKey == m.pubKey } ?: emptyList()

    // The open SOS of this phone that a call recording should be attached to (null = record nothing).
    fun sosToRecord(): MeshMsg? {
        if (!recordCalls.value) return null
        val now = System.currentTimeMillis()
        return messages
            .filter {
                it.type == Kind.SOS && it.pubKey == myPubKey && resolved[it.id] != true &&
                    now - it.time < 6L * 3600L * 1000L && clipsFor(it).size < MAX_CLIPS_PER_SOS
            }
            .maxByOrNull { it.time }
    }

    private fun saveClip(clipId: String, data: ByteArray) {
        val dir = File(app.filesDir, "clips")
        dir.mkdirs()
        File(dir, "$clipId.ulaw").writeBytes(data)
        clipReady[clipId] = true
    }

    // Called by VoiceEngine when a call that was recorded ends: sign a description of the
    // recording, send it, then send the audio chunks. The SOS message itself is never modified.
    fun attachClip(sosId: String, ulaw: ByteArray, seconds: Int) {
        try {
            val clipId = UUID.randomUUID().toString().replace("-", "").take(8)
            val sha = ClipCodec.sha256Hex(ulaw)
            saveClip(clipId, ulaw)
            val text = listOf(clipId, ulaw.size, ClipCodec.chunkCount(ulaw.size), seconds, sha).joinToString("|")
            val m = build(Kind.AUDIO, "Recording", text, 0, sosId)
            seen.add(m.id)
            addMessage(m)
            applyStatus(m)
            forward(m, null)
            sendClip(clipId, ulaw, CLIP_TTL, null)
            notice.value = "🎙 Call recording ($seconds s) added to your SOS"
            handler.postDelayed({ if (notice.value.startsWith("🎙")) notice.value = "" }, 8000L)
        } catch (e: Exception) {
            error.value = "Could not attach recording: ${e.message}"
        }
    }

    private fun parseClip(m: MeshMsg): ClipMeta? {
        try {
            val p = m.text.split("|")
            if (p.size != 5) return null
            if (!p[0].matches(Regex("[0-9a-f]{8}")) || !p[4].matches(Regex("[0-9a-f]{64}"))) return null
            val bytes = p[1].toInt()
            val chunks = p[2].toInt()
            val secs = p[3].toInt()
            val max = CLIP_RATE * CLIP_MAX_SECONDS + 1000
            if (bytes <= 0 || bytes > max || chunks != ClipCodec.chunkCount(bytes)) return null
            if (m.ref.isEmpty()) return null
            return ClipMeta(p[0], m.ref, bytes, chunks, secs, p[4], m.pubKey)
        } catch (e: Exception) {
            return null
        }
    }

    // endpoint == null: to every linked phone.
    private fun sendClip(clipId: String, data: ByteArray, ttl: Int, endpoint: String?) {
        val total = ClipCodec.chunkCount(data.size)
        for (i in 0 until total) {
            val from = i * CLIP_CHUNK
            val to = minOf(data.size, from + CLIP_CHUNK)
            val b = ClipCodec.pack(clipId, ttl, i, total, data, from, to)
            if (endpoint == null) broadcastRaw(b, null) else sendRaw(endpoint, b)
        }
    }

    private fun handleClip(from: String, b: ByteArray) {
        val p = ClipCodec.parse(b) ?: return
        val meta = clipById[p.clipId] ?: return          // only audio whose signed description we hold
        if (clipReady[p.clipId] == true) return          // already complete
        if (p.total != meta.chunks || p.index >= p.total) return
        val parts = clipParts.getOrPut(p.clipId) { arrayOfNulls<ByteArray>(meta.chunks) }
        if (parts[p.index] != null) return               // duplicate
        parts[p.index] = p.data
        clipProgress[p.clipId] = parts.count { it != null } * 100 / meta.chunks
        if (p.ttl > 0) {
            val f = b.copyOf()
            f[1] = (p.ttl - 1).toByte()
            broadcastRaw(f, from)
        }
        if (parts.all { it != null }) {
            clipParts.remove(p.clipId)
            clipProgress.remove(p.clipId)
            val all = java.io.ByteArrayOutputStream()
            parts.forEach { all.write(it!!) }
            val data = all.toByteArray()
            if (data.size == meta.bytes && ClipCodec.sha256Hex(data) == meta.sha) {
                try {
                    saveClip(p.clipId, data)
                } catch (e: Exception) {
                }
            } else {
                rejected.value = rejected.value + 1      // audio does not match the signed description
            }
        }
    }

    private fun pruneClips(cutoff: Long) {
        try {
            File(app.filesDir, "clips").listFiles()?.forEach {
                if (it.lastModified() < cutoff) it.delete()
            }
        } catch (e: Exception) {
        }
    }
}

class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MeshEngine.init(application)
        ShakeWatcher.start(applicationContext)
        val n = NotificationCompat.Builder(this, MeshEngine.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CrisisMesh is running")
            .setContentText("Relaying messages and ready for calls")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    1, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    1, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            try {
                // Retry without the microphone type (e.g. microphone permission not granted).
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                        1, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                }
            } catch (e2: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ShakeWatcher.stop()
        super.onDestroy()
    }
}
