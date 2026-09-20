package com.crisismesh.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.pow

data class BleReading(
    val keyId: String,
    val name: String,
    val rssi: Float,
    val dist: Float,     // meters
    val seenAt: Long
)

/**
 * Works indoors: every phone advertises a tiny Bluetooth LE beacon (id + name) and scans for
 * the others. Signal strength (RSSI) gives a rough distance in meters (about +-3 m at short range).
 * It needs no pairing, no internet and no GPS.
 */
object Proximity {
    private const val COMPANY_ID = 0xFFFF
    private const val MARKER: Byte = 0xC5.toByte()
    private const val REF_RSSI = -62.0   // typical RSSI at 1 m
    private const val PATH_LOSS = 2.3    // indoor path loss exponent

    val readings = mutableStateMapOf<String, BleReading>()
    val status = mutableStateOf("")

    private val raw = mutableMapOf<String, BleReading>()
    private val lastPublish = mutableMapOf<String, Long>()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var myKey = ""

    private fun hexToBytes(h: String): ByteArray =
        h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun rssiToDist(rssi: Float): Float {
        val d = 10.0.pow((REF_RSSI - rssi.toDouble()) / (10.0 * PATH_LOSS))
        return d.toFloat().coerceIn(0.2f, 50f)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            status.value = "Bluetooth beacon failed ($errorCode)"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val md = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
                if (md.size < 4 || md[0] != MARKER) return
                val key = bytesToHex(md.copyOfRange(1, 4))
                if (key == myKey) return
                val name = String(md, 4, md.size - 4, Charsets.UTF_8)
                val now = System.currentTimeMillis()
                val prev = raw[key]
                val rssi = if (prev != null && now - prev.seenAt < 8000L) {
                    prev.rssi * 0.7f + result.rssi * 0.3f
                } else {
                    result.rssi.toFloat()
                }
                val r = BleReading(key, name, rssi, rssiToDist(rssi), now)
                raw[key] = r
                val lp = lastPublish[key] ?: 0L
                if (now - lp >= 1000L) {
                    lastPublish[key] = now
                    readings[key] = r
                }
            } catch (e: Exception) {
            }
        }

        override fun onScanFailed(errorCode: Int) {
            status.value = "Bluetooth scan failed ($errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    fun start(app: Application, keyId: String, name: String) {
        stop()
        status.value = ""
        try {
            myKey = keyId
            val mgr = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = mgr.adapter
            if (adapter == null || !adapter.isEnabled) {
                status.value = "Turn Bluetooth on for distance to nearby phones"
                return
            }
            val nameBytes = name.toByteArray().take(14).toByteArray()
            val payload = byteArrayOf(MARKER) + hexToBytes(keyId) + nameBytes

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addManufacturerData(COMPANY_ID, payload)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
            advertiser = adapter.bluetoothLeAdvertiser
            advertiser?.startAdvertising(settings, data, advCallback)

            val filter = ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID, byteArrayOf(MARKER), byteArrayOf(0xFF.toByte()))
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(listOf(filter), scanSettings, scanCallback)
        } catch (e: Exception) {
            status.value = "Proximity: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (e: Exception) {
        }
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
        }
        advertiser = null
        scanner = null
        raw.clear()
        lastPublish.clear()
        readings.clear()
    }
}
