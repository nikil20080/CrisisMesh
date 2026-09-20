package com.crisismesh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.crisismesh.app.ui.theme.CrisisMeshTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRequired()) {
                MeshEngine.start()
            } else {
                Toast.makeText(
                    this,
                    "Allow Location, Nearby devices and Microphone to start",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun required(): List<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return p
    }

    private fun optional(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyList()

    private fun hasRequired() = required().all { granted(it) }

    private fun startMesh() {
        val missing = (required() + optional()).filter { !granted(it) }
        if (missing.isEmpty()) MeshEngine.start()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshEngine.init(application)
        handleLaunch(intent)
        enableEdgeToEdge()
        setContent {
            CrisisMeshTheme {
                App(onStart = { startMesh() })
            }
        }
    }

    // Shake and notification taps arrive here when the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(i: Intent?) {
        if (i == null) return
        val t = i.getIntExtra(EXTRA_TAB, -1)
        if (t >= 0) MeshEngine.requestedTab.value = t
        // Opened by a shake: show over the lock screen and wake the display (undone in onStop).
        if (i.getBooleanExtra(EXTRA_SHAKE, false)) showOverLockScreen(true)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
    }

    override fun onStart() {
        super.onStart()
        MeshEngine.uiVisible = true
    }

    override fun onResume() {
        super.onResume()
        ShakeWatcher.dismiss(this)
    }

    override fun onStop() {
        MeshEngine.uiVisible = false
        showOverLockScreen(false)
        super.onStop()
    }
}

@Composable
fun App(onStart: () -> Unit) {
    if (VoiceEngine.state.value != CallState.IDLE) {
        CallScreen()
    } else {
        MainScaffold(onStart)
    }
}

@Composable
fun MainScaffold(onStart: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val req = MeshEngine.requestedTab.value
    LaunchedEffect(req) {
        if (req >= 0) {
            tab = req
            MeshEngine.requestedTab.value = -1
        }
    }
    val tabs = listOf("🆘" to "SOS", "📦" to "Needs", "📋" to "Feed", "📞" to "Phones", "👤" to "Me")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, pair ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(pair.first, fontSize = 20.sp) },
                        label = { Text(pair.second) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBar(onStart)
            when (tab) {
                0 -> SosTab()
                1 -> NeedsTab()
                2 -> FeedTab()
                3 -> PhonesTab()
                else -> MeTab()
            }
        }
    }
}

@Composable
fun StatusBar(onStart: () -> Unit) {
    val e = MeshEngine
    val loc = e.myLocation.value
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CrisisMesh", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (e.running.value)
                            "🟢 ON · ${e.peerLinks.size} phones linked · you are ${e.myName.value}"
                        else "🔴 OFF · tap Start"
                    )
                    Text(
                        if (loc == null) "📍 Waiting for GPS..."
                        else "📍 GPS ±${loc.accuracy.toInt()} m",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = { if (e.running.value) e.stop() else onStart() }) {
                    Text(if (e.running.value) "Stop" else "Start")
                }
            }
            if (e.error.value.isNotEmpty()) {
                Text(e.error.value, color = MaterialTheme.colorScheme.error)
            }
            if (e.notice.value.isNotEmpty()) {
                Text(e.notice.value, color = Color(0xFF388E3C), style = MaterialTheme.typography.bodySmall)
            }
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                Text(
                    "🔕 Notifications are off: you will NOT be alerted to SOS messages. Turn them on in system settings.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (Proximity.status.value.isNotEmpty()) {
                Text(
                    Proximity.status.value,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
