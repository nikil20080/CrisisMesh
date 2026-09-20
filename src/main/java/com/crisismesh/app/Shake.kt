package com.crisismesh.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.sqrt

/**
 * Shake-to-open. Runs while the mesh is running (MeshService is a foreground service, which is
 * what allows sensor events in the background). A hard shake opens CrisisMesh on the SOS tab.
 *
 * Android 10+ blocks apps from starting screens out of the blue, so two things are tried:
 *  1. startActivity() directly (works if the user allowed "Display over other apps").
 *  2. a full-screen-intent notification (opens by itself on a locked screen, or shows a
 *     tap-to-open banner on an unlocked one).
 */
object ShakeWatcher : SensorEventListener {
    private const val CH_SHAKE = "crisismesh_shake"
    const val NOTIF_SHAKE = 77

    // Tune here: THRESHOLD in g, jolts needed, window (ms). Higher = harder to trigger by accident.
    private val counter = JoltCounter(thresholdG = 3.0f, joltsNeeded = 4, windowMs = 2000L)

    private var app: Context? = null
    private var sm: SensorManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var active = false

    fun start(ctx: Context) {
        if (active || !MeshEngine.shakeEnabled.value) return
        val c = ctx.applicationContext
        val m = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        app = c
        createChannel(c)
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        sm = m
        try {
            // Keeps the CPU running with the screen off so the shake can still be heard.
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crisismesh:shake")
            lock.setReferenceCounted(false)
            lock.acquire(12L * 3600L * 1000L)
            wl = lock
        } catch (e: Exception) {
        }
        active = true
    }

    fun stop() {
        if (!active) return
        try {
            sm?.unregisterListener(this)
        } catch (e: Exception) {
        }
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wl = null
        sm = null
        active = false
    }

    fun dismiss(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_SHAKE)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
            SensorManager.GRAVITY_EARTH
        if (counter.onSample(g, SystemClock.elapsedRealtime())) fire()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun fire() {
        val c = app ?: return
        if (MeshEngine.uiVisible) return        // already on screen
        buzz(c)
        val intent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TAB, 0)
            .putExtra(EXTRA_SHAKE, true)
        try {
            c.startActivity(intent)
        } catch (e: Exception) {
        }
        postFullScreen(c, intent)
    }

    @SuppressLint("MissingPermission")
    private fun postFullScreen(c: Context, intent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                c, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(c, CH_SHAKE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Shake detected")
                .setContentText("Tap to open CrisisMesh and send an SOS")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(20_000L)
                .build()
            NotificationManagerCompat.from(c).notify(NOTIF_SHAKE, n)
        } catch (e: Exception) {
        }
    }

    private fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_SHAKE, "Shake to open", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Opens CrisisMesh when you shake the phone"
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    private fun buzz(c: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }
}
