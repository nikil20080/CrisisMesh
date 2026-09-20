package com.crisismesh.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class CallState { IDLE, CALLING, RINGING, ACTIVE }

// ---- G.711 style mu-law: 16-bit sample <-> 8-bit byte (halves the bandwidth) ----
private const val MU_BIAS = 0x84
private const val MU_CLIP = 32635

internal fun mulawEncode(sample: Int): Byte {
    var s = sample
    val sign = if (s < 0) {
        s = -s
        0x80
    } else {
        0
    }
    if (s > MU_CLIP) s = MU_CLIP
    s += MU_BIAS
    var exponent = 7
    var mask = 0x4000
    while (exponent > 0 && (s and mask) == 0) {
        exponent--
        mask = mask shr 1
    }
    val mantissa = (s shr (exponent + 3)) and 0x0F
    return (sign or (exponent shl 4) or mantissa).inv().toByte()
}

internal fun mulawDecode(b: Byte): Short {
    val u = b.toInt().inv() and 0xFF
    val sign = u and 0x80
    val exponent = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    var t = ((mantissa shl 3) + MU_BIAS) shl exponent
    t -= MU_BIAS
    return (if (sign != 0) -t else t).toShort()
}

/**
 * One-to-one voice calls over Nearby Connections (no internet, no cell network).
 * Audio: 16 kHz mono, 20 ms frames, mu-law, small jitter buffer (60 ms).
 */
object VoiceEngine {
    private const val RATE = 16000
    private const val FRAME_SAMPLES = 320          // 20 ms at 16 kHz
    private const val PREBUFFER = 3                // frames to collect before playing (60 ms)
    private const val MAX_DEPTH = 8                // frames; older ones are dropped to keep delay low
    private const val CALL_NOTIF_ID = 4242

    private lateinit var app: Application
    private lateinit var am: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    // ---- state shown in the UI ----
    val state = mutableStateOf(CallState.IDLE)
    val peerName = mutableStateOf("")
    val muted = mutableStateOf(false)
    val speaker = mutableStateOf(false)
    val rttMs = mutableStateOf(-1)
    val startedAt = mutableStateOf(0L)
    val lost = mutableStateOf(0)
    val underruns = mutableStateOf(0)
    val message = mutableStateOf("")

    // ---- internal ----
    private var peerEndpoint: String? = null
    private var callId = ""
    private var ring: Ringtone? = null

    @Volatile
    private var audioActive = false

    @Volatile
    private var underrunCount = 0
    private var txSeq = 0
    private var rxSeq = -1
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    private val decodeTable = ShortArray(256) { mulawDecode(it.toByte()) }

    fun init(application: Application) {
        app = application
        am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ---------------- public actions ----------------

    fun startCall(endpointId: String, name: String) {
        if (state.value != CallState.IDLE) return
        callId = UUID.randomUUID().toString().take(8)
        peerEndpoint = endpointId
        peerName.value = name
        muted.value = false
        message.value = ""
        state.value = CallState.CALLING
        sendControl(endpointId, "INVITE")
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, 30000L)
    }

    fun accept() {
        if (state.value != CallState.RINGING) return
        val ep = peerEndpoint ?: return
        handler.removeCallbacks(ringTimeout)
        stopRinging()
        cancelNotification()
        sendControl(ep, "ACCEPT")
        startAudio()
    }

    fun decline() {
        val ep = peerEndpoint
        if (state.value != CallState.RINGING || ep == null) return
        sendControl(ep, "DECLINE")
        message.value = ""
        finish()
    }

    fun endCall(sendEnd: Boolean = true) {
        val ep = peerEndpoint
        if (sendEnd && ep != null && state.value != CallState.IDLE) {
            sendControl(ep, "END")
        }
        finish()
    }

    fun toggleMute() {
        muted.value = !muted.value
    }

    fun setSpeaker(on: Boolean) {
        speaker.value = on
        if (audioActive) applySpeaker(on)
    }

    // Called by MeshEngine when a link drops.
    fun onPeerGone(endpointId: String) {
        if (endpointId == peerEndpoint && state.value != CallState.IDLE) {
            message.value = "Connection lost"
            finish()
        }
    }

    // ---------------- incoming packets ----------------

    fun onControl(from: String, s: String) {
        val msg = try {
            JSONObject(s)
        } catch (e: Exception) {
            return
        }
        val c = msg.optString("c")
        val id = msg.optString("id")
        when (c) {
            "INVITE" -> {
                if (state.value != CallState.IDLE) {
                    sendControl(from, "BUSY", id)
                    return
                }
                callId = id
                peerEndpoint = from
                peerName.value = MeshEngine.peerLinks[from]?.substringBefore('#')
                    ?: msg.optString("n", "Unknown")
                muted.value = false
                message.value = ""
                state.value = CallState.RINGING
                startRinging()
                showIncomingNotification()
                handler.removeCallbacks(ringTimeout)
                handler.postDelayed(ringTimeout, 30000L)
            }
            "ACCEPT" -> {
                if (state.value == CallState.CALLING && from == peerEndpoint && id == callId) {
                    handler.removeCallbacks(ringTimeout)
                    startAudio()
                }
            }
            "DECLINE", "BUSY" -> {
                if (state.value == CallState.CALLING && from == peerEndpoint) {
                    message.value = if (c == "BUSY") "Busy" else "Declined"
                    finish()
                }
            }
            "END" -> {
                if (from == peerEndpoint && state.value != CallState.IDLE) {
                    message.value = "Call ended"
                    finish()
                }
            }
            "PING" -> {
                if (from == peerEndpoint && state.value == CallState.ACTIVE) {
                    val t = msg.optLong("t", 0L)
                    sendControl(from, "PONG") { put("t", t) }
                }
            }
            "PONG" -> {
                val t = msg.optLong("t", 0L)
                if (t > 0L) rttMs.value = (SystemClock.elapsedRealtime() - t).toInt()
            }
        }
    }

    // b = [TAG_AUDIO, seqHigh, seqLow, 320 mu-law bytes]
    fun onAudio(from: String, b: ByteArray) {
        if (!audioActive || state.value != CallState.ACTIVE || from != peerEndpoint) return
        if (b.size < 4) return
        val seq = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        if (rxSeq >= 0) {
            val gap = (seq - rxSeq - 1) and 0xFFFF
            if (gap in 1..100) lost.value = lost.value + gap
        }
        rxSeq = seq
        val n = minOf(b.size - 3, FRAME_SAMPLES)
        val pcm = ShortArray(FRAME_SAMPLES)
        for (i in 0 until n) {
            pcm[i] = decodeTable[b[3 + i].toInt() and 0xFF]
        }
        queue.add(pcm)
        while (queue.size > MAX_DEPTH + 4) queue.poll()
    }

    // ---------------- control helpers ----------------

    private fun sendControl(
        ep: String,
        c: String,
        id: String = callId,
        extra: JSONObject.() -> Unit = {}
    ) {
        val o = JSONObject()
        o.put("c", c)
        o.put("id", id)
        o.put("n", MeshEngine.myName.value)
        o.extra()
        MeshEngine.sendRaw(ep, byteArrayOf(TAG_CALL) + o.toString().toByteArray())
    }

    private val ringTimeout = Runnable {
        if (state.value == CallState.CALLING || state.value == CallState.RINGING) {
            message.value = "No answer"
            endCall(true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state.value != CallState.ACTIVE) return
            val ep = peerEndpoint
            if (ep != null) {
                sendControl(ep, "PING") { put("t", SystemClock.elapsedRealtime()) }
            }
            underruns.value = underrunCount
            handler.postDelayed(this, 2000L)
        }
    }

    private fun finish() {
        val rec = CallRecorder.finish()   // null unless this phone had an open SOS during the call
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(tick)
        stopRinging()
        cancelNotification()
        audioActive = false
        try {
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
        }
        clearSpeaker()
        peerEndpoint = null
        state.value = CallState.IDLE
        if (rec != null) MeshEngine.attachClip(rec.sosId, rec.data, rec.seconds)
    }

    // ---------------- audio ----------------

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (audioActive) return
        queue.clear()
        rxSeq = -1
        txSeq = 0
        underrunCount = 0
        lost.value = 0
        underruns.value = 0
        rttMs.value = -1

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applySpeaker(speaker.value)

            val minRec = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRec, FRAME_SAMPLES * 2 * 6)
            )
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone not available")
            }

            val minPlay = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minPlay, FRAME_SAMPLES * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            try {
                recorder?.release()
            } catch (x: Exception) {
            }
            try {
                player?.release()
            } catch (x: Exception) {
            }
            message.value = "Audio error: ${e.message}"
            endCall(true)
            return
        }

        val rec = recorder ?: return
        val trk = player ?: return
        audioActive = true
        state.value = CallState.ACTIVE
        startedAt.value = System.currentTimeMillis()
        // Only records when this phone has an open SOS and the setting is on.
        CallRecorder.begin(MeshEngine.sosToRecord()?.id)
        Thread { captureLoop(rec) }.apply {
            name = "cm-capture"
            start()
        }
        Thread { playLoop(trk) }.apply {
            name = "cm-play"
            start()
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000L)
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            val sid = r.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sid)
                aec?.setEnabled(true)
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sid)
                ns?.setEnabled(true)
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sid)
                agc?.setEnabled(true)
            }
            r.startRecording()
            val buf = ShortArray(FRAME_SAMPLES)
            var failed = false
            while (audioActive && !failed) {
                var n = 0
                while (n < FRAME_SAMPLES && audioActive) {
                    val got = r.read(buf, n, FRAME_SAMPLES - n)
                    if (got < 0) {
                        failed = true
                        break
                    }
                    n += got
                }
                if (failed || !audioActive) break
                if (muted.value) continue
                CallRecorder.feed(buf, FRAME_SAMPLES)
                val ep = peerEndpoint ?: continue
                val out = ByteArray(3 + FRAME_SAMPLES)
                out[0] = TAG_AUDIO
                out[1] = (txSeq shr 8).toByte()
                out[2] = txSeq.toByte()
                txSeq = (txSeq + 1) and 0xFFFF
                for (i in 0 until FRAME_SAMPLES) {
                    out[3 + i] = mulawEncode(buf[i].toInt())
                }
                MeshEngine.sendRaw(ep, out)
            }
            if (failed) {
                handler.post {
                    message.value = "Microphone error"
                    endCall(true)
                }
            }
        } catch (e: Exception) {
            handler.post {
                message.value = "Microphone error: ${e.message}"
                endCall(true)
            }
        } finally {
            try {
                r.stop()
            } catch (e: Exception) {
            }
            r.release()
            try {
                aec?.release()
                ns?.release()
                agc?.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun playLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val silence = ShortArray(FRAME_SAMPLES)
        var buffering = true
        try {
            t.play()
            while (audioActive) {
                if (buffering && queue.size >= PREBUFFER) buffering = false
                var f: ShortArray? = null
                if (!buffering) {
                    f = queue.poll()
                    if (f == null) {
                        buffering = true
                        underrunCount++
                    }
                }
                while (queue.size > MAX_DEPTH) queue.poll()
                t.write(f ?: silence, 0, FRAME_SAMPLES)
            }
        } catch (e: Exception) {
        } finally {
            try {
                t.stop()
            } catch (e: Exception) {
            }
            t.release()
        }
    }

    private fun applySpeaker(on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val d = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (d != null) am.setCommunicationDevice(d)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                am.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
        }
    }

    private fun clearSpeaker() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
        }
    }

    // ---------------- ringing and notification ----------------

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(app, uri)
            if (r != null) {
                if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
                ring = r
            }
        } catch (e: Exception) {
        }
        try {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        try {
            ring?.stop()
        } catch (e: Exception) {
        }
        ring = null
        try {
            (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun showIncomingNotification() {
        try {
            val open = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                app, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, MeshEngine.CH_CALL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming call")
                .setContentText(peerName.value + " is calling")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(app).notify(CALL_NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    private fun cancelNotification() {
        try {
            NotificationManagerCompat.from(app).cancel(CALL_NOTIF_ID)
        } catch (e: Exception) {
        }
    }
}
