package com.crisismesh.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

// First byte of every packet sent over Nearby Connections.
const val TAG_MESH: Byte = 1      // emergency message (SOS, needs, alerts) as JSON
const val TAG_CALL: Byte = 2      // call control (invite, accept, end, ping) as JSON
const val TAG_AUDIO: Byte = 3     // one 20 ms voice frame
const val TAG_PRESENCE: Byte = 4  // "I am here" beacon with name and GPS
const val TAG_CLIP: Byte = 5      // one chunk of a call recording attached to an SOS

// Extras of the intent that opens MainActivity (shake, notification taps).
const val EXTRA_TAB = "open_tab"        // 0 SOS, 1 Needs, 2 Feed, 3 Phones, 4 Me
const val EXTRA_SHAKE = "from_shake"

object Kind {
    const val SOS = "SOS"
    const val REQUEST = "REQUEST"
    const val OFFER = "OFFER"
    const val SAFE = "SAFE"
    const val ALERT = "ALERT"
    const val ACK = "ACK"
    const val RESOLVE = "RESOLVE"
    const val AUDIO = "AUDIO"   // signed pointer to a call recording (ref = SOS id)
}

const val RESPONDER_PIN = "2580" // demo only. Production: authority-signed responder certificates.

val SOS_CATEGORIES = listOf("Medical", "Trapped", "Fire", "Flood", "Other")
val NEED_CATEGORIES = listOf("Water", "Food", "Medicine", "Shelter", "Power", "Rescue")

fun rank(t: String): Int = when (t) {
    Kind.ALERT -> 0
    Kind.ACK, Kind.RESOLVE, Kind.AUDIO -> 1
    Kind.SOS -> 2
    Kind.REQUEST -> 3
    Kind.OFFER -> 4
    else -> 5
}

data class MeshMsg(
    val id: String,
    val type: String,
    val category: String,
    val sender: String,
    val role: String,      // CITIZEN or RESPONDER
    val text: String,
    val lat: Double,
    val lon: Double,
    val people: Int,
    val time: Long,
    val ref: String,       // target message id for ACK / RESOLVE
    val pubKey: String,    // sender public key (base64)
    val sig: String,       // signature over signable() (base64)
    val ttl: Int,          // hops left (not signed, changes in transit)
    val hops: Int          // hops travelled (not signed, changes in transit)
) {
    val hasLocation: Boolean get() = lat != 0.0 || lon != 0.0
    val keyId: String by lazy { Crypto.shortId(pubKey) }

    fun signable(): String =
        listOf(id, type, category, sender, role, text, lat, lon, people, time, ref).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("category", category)
        put("sender", sender)
        put("role", role)
        put("text", text)
        put("lat", lat)
        put("lon", lon)
        put("people", people)
        put("time", time)
        put("ref", ref)
        put("pubKey", pubKey)
        put("sig", sig)
        put("ttl", ttl)
        put("hops", hops)
    }

    companion object {
        fun fromJson(o: JSONObject) = MeshMsg(
            id = o.getString("id"),
            type = o.getString("type"),
            category = o.getString("category"),
            sender = o.getString("sender"),
            role = o.getString("role"),
            text = o.getString("text"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            people = o.getInt("people"),
            time = o.getLong("time"),
            ref = o.getString("ref"),
            pubKey = o.getString("pubKey"),
            sig = o.getString("sig"),
            ttl = o.getInt("ttl"),
            hops = o.getInt("hops")
        )
    }
}

// Signed description of a call recording. The audio itself travels in TAG_CLIP chunks and
// must match the SHA-256 in here, so it cannot be swapped on the way.
data class ClipMeta(
    val clipId: String,
    val sosId: String,
    val bytes: Int,
    val chunks: Int,
    val seconds: Int,
    val sha: String,
    val ownerKey: String   // public key of the phone that signed it (must equal the SOS sender)
)

// Last "I am here" beacon received from a phone (GPS based).
data class PeerInfo(
    val keyId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val ts: Long,       // sender clock, only used to drop old/duplicate beacons
    val seenAt: Long,   // my clock, when I received it
    val hops: Int
)

// One line in the "Phones" list: everything known about a nearby phone.
data class PeerRow(
    val keyId: String,
    val name: String,
    val endpointId: String?,  // not null = directly linked, calls possible
    val hops: Int,            // 0 = only seen by Bluetooth, 1 = linked or beacon from neighbour, 2+ = relayed
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val gpsAt: Long,
    val bleDist: Float,       // meters from Bluetooth signal strength, -1 = none
    val bleAt: Long
) {
    val direct: Boolean get() = endpointId != null
    val hasLoc: Boolean get() = lat != 0.0 || lon != 0.0
}

object Crypto {
    private const val ALIAS = "crisismesh_key"
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (ks.containsAlias(ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        kpg.generateKeyPair()
    }

    fun publicKeyB64(): String {
        ensureKey()
        val pub = ks.getCertificate(ALIAS).publicKey
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    fun sign(data: String): String {
        ensureKey()
        val priv = ks.getKey(ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(data.toByteArray())
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun verify(data: String, sigB64: String, pubB64: String): Boolean = try {
        val pub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(data.toByteArray())
        s.verify(Base64.decode(sigB64, Base64.NO_WRAP))
    } catch (e: Exception) {
        false
    }

    fun shortId(pubB64: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(pubB64.toByteArray())
        return d.take(3).joinToString("") { "%02x".format(it) }
    }
}
