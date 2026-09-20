package com.crisismesh.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.io.ByteArrayOutputStream
import java.io.File

class Recording(val sosId: String, val data: ByteArray, val seconds: Int)

/**
 * Records the caller's own voice during a call, but only when that phone has an open SOS.
 * The audio is what the microphone hears (the other person is not recorded).
 * Called from VoiceEngine: begin() when the call becomes active, feed() for every mic frame,
 * finish() when the call ends. If begin() got no SOS id, nothing is recorded at all.
 */
object CallRecorder {
    val recording = mutableStateOf(false)
    private var out: ByteArrayOutputStream? = null
    private var sosId: String? = null

    @Synchronized
    fun begin(target: String?) {
        out = null
        sosId = null
        recording.value = false
        if (target == null) return
        out = ByteArrayOutputStream()
        sosId = target
        recording.value = true
    }

    // pcm = 16 kHz samples from the microphone. Two samples are averaged into one (8 kHz), then mu-law coded.
    @Synchronized
    fun feed(pcm: ShortArray, n: Int) {
        val o = out ?: return
        if (o.size() >= CLIP_RATE * CLIP_MAX_SECONDS) {
            recording.value = false
            return
        }
        var i = 0
        while (i + 1 < n) {
            val s = (pcm[i].toInt() + pcm[i + 1].toInt()) / 2
            o.write(mulawEncode(s).toInt())
            i += 2
        }
    }

    // Returns the recording, or null when there is nothing worth keeping (no SOS, or shorter than 1 s).
    @Synchronized
    fun finish(): Recording? {
        val o = out
        val id = sosId
        out = null
        sosId = null
        recording.value = false
        if (o == null || id == null) return null
        val data = o.toByteArray()
        val seconds = data.size / CLIP_RATE
        if (seconds < 1) return null
        return Recording(id, data, seconds)
    }
}

/** Plays a stored recording (mu-law 8 kHz) through the loudspeaker. One clip at a time. */
object ClipPlayer {
    val playing = mutableStateOf("")   // clipId that is playing right now, "" = none
    private var track: AudioTrack? = null
    private val main = Handler(Looper.getMainLooper())

    fun toggle(clipId: String, file: File) {
        if (playing.value == clipId) stop() else play(clipId, file)
    }

    private fun play(clipId: String, file: File) {
        stop()
        try {
            val data = file.readBytes()
            if (data.isEmpty()) return
            val pcm = ShortArray(data.size) { mulawDecode(data[it]) }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CLIP_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setNotificationMarkerPosition(pcm.size)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    stop()
                }

                override fun onPeriodicNotification(track: AudioTrack?) {}
            }, main)
            track = t
            playing.value = clipId
            t.play()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        val t = track
        track = null
        playing.value = ""
        if (t != null) {
            try {
                t.stop()
            } catch (e: Exception) {
            }
            try {
                t.release()
            } catch (e: Exception) {
            }
        }
    }
}
