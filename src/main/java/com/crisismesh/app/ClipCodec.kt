package com.crisismesh.app

import java.security.MessageDigest

// ---- Call recordings attached to an SOS ----
// Audio format: 8 kHz mono, mu-law (1 byte per sample) = 8 KB per second.
const val CLIP_RATE = 8000
const val CLIP_MAX_SECONDS = 30              // only the first 30 s of a call are kept
const val CLIP_CHUNK = 16_000                // bytes of audio per packet (Nearby allows 32 KB per bytes payload)
const val CLIP_HEADER = 14                   // tag, ttl, clipId(8), index(2), total(2)
const val CLIP_TTL = 6                       // hops a recording travels

class ClipPacket(
    val ttl: Int,
    val clipId: String,
    val index: Int,
    val total: Int,
    val data: ByteArray
)

// Pure functions (no Android classes) so they are easy to test.
object ClipCodec {
    fun chunkCount(size: Int): Int = (size + CLIP_CHUNK - 1) / CLIP_CHUNK

    // Packet layout: [TAG_CLIP][ttl][clipId: 8 ASCII bytes][index: u16][total: u16][audio bytes]
    fun pack(clipId: String, ttl: Int, index: Int, total: Int, data: ByteArray, from: Int, to: Int): ByteArray {
        val b = ByteArray(CLIP_HEADER + (to - from))
        b[0] = TAG_CLIP
        b[1] = ttl.toByte()
        val id = clipId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(id, 0, b, 2, 8)
        b[10] = (index shr 8).toByte()
        b[11] = index.toByte()
        b[12] = (total shr 8).toByte()
        b[13] = total.toByte()
        System.arraycopy(data, from, b, CLIP_HEADER, to - from)
        return b
    }

    fun parse(b: ByteArray): ClipPacket? {
        if (b.size <= CLIP_HEADER || b[0] != TAG_CLIP) return null
        val ttl = b[1].toInt() and 0xFF
        val clipId = String(b, 2, 8, Charsets.US_ASCII)
        val index = ((b[10].toInt() and 0xFF) shl 8) or (b[11].toInt() and 0xFF)
        val total = ((b[12].toInt() and 0xFF) shl 8) or (b[13].toInt() and 0xFF)
        return ClipPacket(ttl, clipId, index, total, b.copyOfRange(CLIP_HEADER, b.size))
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

// Counts hard jolts of the accelerometer. A "shake" = several jolts inside a short window.
class JoltCounter(
    private val thresholdG: Float = 3.0f,
    private val joltsNeeded: Int = 4,
    private val windowMs: Long = 2000L,
    private val minGapMs: Long = 120L,
    private val cooldownMs: Long = 5000L
) {
    private var count = 0
    private var firstAt = 0L
    private var lastAt = -1_000_000L
    private var lastFired = -1_000_000L

    // g = acceleration magnitude in units of gravity, now = monotonic milliseconds.
    // Returns true when a shake was recognised.
    fun onSample(g: Float, now: Long): Boolean {
        if (g < thresholdG) return false
        if (now - lastAt < minGapMs) return false
        lastAt = now
        if (count == 0 || now - firstAt > windowMs) {
            count = 0
            firstAt = now
        }
        count++
        if (count < joltsNeeded) return false
        count = 0
        if (now - lastFired < cooldownMs) return false
        lastFired = now
        return true
    }
}
