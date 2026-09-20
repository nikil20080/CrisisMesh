package com.crisismesh.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun CallScreen() {
    val st = VoiceEngine.state.value
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(32.dp))
                Text("📱", fontSize = 72.sp)
                Spacer(Modifier.height(12.dp))
                Text(VoiceEngine.peerName.value, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                val secs = ((now - VoiceEngine.startedAt.value) / 1000L).coerceAtLeast(0L)
                Text(
                    when (st) {
                        CallState.CALLING -> "Calling..."
                        CallState.RINGING -> "Incoming call"
                        CallState.ACTIVE -> "%02d:%02d".format(secs / 60, secs % 60)
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                if (st == CallState.ACTIVE) {
                    Spacer(Modifier.height(16.dp))
                    val rtt = VoiceEngine.rttMs.value
                    Text(
                        if (rtt >= 0) "Network delay ≈ ${rtt / 2} ms (one way)"
                        else "Measuring delay...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Lost frames: ${VoiceEngine.lost.value} · Buffer gaps: ${VoiceEngine.underruns.value}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (VoiceEngine.muted.value) {
                        Spacer(Modifier.height(8.dp))
                        Text("🔇 You are muted", color = MaterialTheme.colorScheme.error)
                    }
                    if (CallRecorder.recording.value) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "🔴 Recording your voice to attach to your SOS",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (st == CallState.ACTIVE) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Testing with two phones in one room? Keep the speaker off and use the earpiece or headphones, or you will hear an echo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            when (st) {
                CallState.RINGING -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = { VoiceEngine.decline() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.weight(1f).height(72.dp)
                        ) { Text("Decline", color = Color.White, fontSize = 18.sp) }
                        Button(
                            onClick = { VoiceEngine.accept() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                            modifier = Modifier.weight(1f).height(72.dp)
                        ) { Text("Accept", color = Color.White, fontSize = 18.sp) }
                    }
                }
                CallState.CALLING -> {
                    Button(
                        onClick = { VoiceEngine.endCall(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                    ) { Text("Cancel", color = Color.White, fontSize = 18.sp) }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { VoiceEngine.toggleMute() },
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) { Text(if (VoiceEngine.muted.value) "🔇 Unmute" else "🎙️ Mute") }
                            OutlinedButton(
                                onClick = { VoiceEngine.setSpeaker(!VoiceEngine.speaker.value) },
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) { Text(if (VoiceEngine.speaker.value) "🔊 Speaker on" else "📞 Earpiece") }
                        }
                        Button(
                            onClick = { VoiceEngine.endCall(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.fillMaxWidth().height(72.dp)
                        ) { Text("End call", color = Color.White, fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}
