# CrisisMesh

**Communication beyond connectivity.** An Android app that lets phones send SOS alerts, share their location, request resources and make voice calls to each other with **no internet and no mobile network**.

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84)
![Language](https://img.shields.io/badge/Kotlin-2.2-7F52FF)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![Status](https://img.shields.io/badge/status-hackathon%20prototype-orange)

---

## The problem

During floods, earthquakes, cyclones and other disasters, mobile towers and internet links often fail exactly when people need them most. Victims cannot call for help, and rescuers cannot see who needs what or where.

> **Problem statement:** Develop a resilient system that enables people and emergency responders to exchange SOS alerts, location details, resource requirements, and critical information even with limited or no internet connectivity.

## The idea

Every phone running CrisisMesh becomes a small radio node. Phones link to each other directly over Bluetooth and Wi-Fi, pass messages along from phone to phone, and keep them stored until they can be delivered. The more phones there are, the bigger the network gets.

```
   Victim phone ──► Phone B ──► Phone C ──► Responder phone
     (SOS + GPS)    (relays)    (relays)     (sees SOS, acknowledges)
        ▲                                          │
        └──────────── "Help is on the way" ◄───────┘
```

---

## Features

| Area | What it does |
|---|---|
| **SOS** | Send an SOS with a category (Medical, Trapped, Fire, Flood, Other), number of people and a note. GPS location is attached automatically. "I'm safe" button. |
| **Resource needs** | Request or offer Water, Food, Medicine, Shelter, Power or Rescue, with a quantity. |
| **Multi-hop relay** | Messages hop from phone to phone (up to 8 hops). Duplicates are dropped. |
| **Store-and-forward** | Messages are saved on the phone and handed to any new phone that connects, so messages travel with people who move around. |
| **Voice calls** | One-to-one calls to a named, linked phone. Works with no internet. Shows live network delay in ms. |
| **Nearby phones and distance** | Lists every phone around you by name with distance in meters. Uses Bluetooth signal strength indoors and GPS outdoors. Includes a radar view. |
| **Responder mode** | Responders can broadcast official alerts, **Acknowledge** and **Resolve** SOS calls. The victim sees "Help is on the way". |
| **Signed messages** | Every message is signed with a key stored in Android Keystore. Tampered messages are dropped. |
| **Alert sounds** | Incoming SOS plays a loud alarm with vibration. Other messages play a soft sound. You hear a confirmation beep when you send. |
| **Shake to open** | Shake the phone 3 times to open the app on the SOS screen, even from the lock screen. |
| **Background service** | The mesh keeps running with the screen off (foreground service). |
| **Navigate** | Opens the location of an SOS in your maps app. |
| **Anti-spam** | Per-sender rate limit and message expiry (48 hours). |

### Screens

| Tab | Purpose |
|---|---|
| 🆘 **SOS** | Send SOS or "I'm safe" |
| 📦 **Needs** | Request or offer resources |
| 📋 **Feed** | All messages sorted by priority, with Acknowledge / Resolve / Navigate |
| 📞 **Phones** | Nearby phones with names, distance and a **Call** button, plus radar |
| 👤 **Me** | Display name, responder mode, sound and shake settings, mesh stats, security demo |

---

## How it works

### Architecture

```
┌──────────────────────── Compose UI ─────────────────────────┐
│  SOS · Needs · Feed · Phones · Me · Call screen              │
└───────────────┬──────────────────────────────┬──────────────┘
                │                              │
        ┌───────▼────────┐             ┌───────▼────────┐
        │  MeshEngine    │             │  VoiceEngine   │
        │  links, relay, │◄───────────►│  call signals, │
        │  sync, storage │  raw packets│  audio, buffer │
        └───┬────────┬───┘             └────────────────┘
            │        │
   ┌────────▼───┐ ┌──▼──────────┐  ┌───────────┐  ┌────────────────┐
   │ Nearby     │ │ Proximity   │  │ Alerts    │  │ ShakeDetector  │
   │ Connections│ │ (BLE beacon │  │ (sounds)  │  │ (accelerometer)│
   │ (BT/Wi-Fi) │ │  + RSSI)    │  └───────────┘  └────────────────┘
   └────────────┘ └─────────────┘
```

### Packet types

Everything is sent as small byte payloads over Google Nearby Connections. The first byte tells the receiver what it is.

| Tag | Type | Content |
|---|---|---|
| `1` | Mesh message | SOS, needs, offers, alerts, acknowledgements (JSON, signed) |
| `2` | Call control | Invite, accept, decline, end, ping (JSON) |
| `3` | Voice frame | One 20 ms audio frame |
| `4` | Presence | "I am here": name and GPS, shared every 4 seconds |

### Mesh and relay

- Phones connect automatically using the `P2P_CLUSTER` strategy. To avoid two phones connecting to each other at the same time, only the phone with the smaller ID starts the connection.
- Each message has a unique ID. A phone that has seen an ID before ignores it. This stops loops.
- Each message has a TTL (8 hops). Every relay lowers it by one.
- When a new phone links up, it receives up to 80 stored messages, most urgent first.
- Messages are saved to a local file (max 300 messages, 48 hours).

### Security

- Each phone creates an **ECDSA P-256 key pair inside Android Keystore**.
- Every message is signed. Receivers verify the signature and drop anything that fails. The **Me → Send a forged message** button shows this live: other phones' "Forged / invalid dropped" counter goes up and nothing appears in their feed.
- Rate limit of 40 messages per minute per sender.
- Official alerts are only accepted from phones in responder mode.

### Voice calls

- Audio: 16 kHz, mono, 20 ms frames, G.711-style µ-law (8 bits per sample, about 16 KB/s).
- Microphone source `VOICE_COMMUNICATION` with echo cancellation, noise suppression and gain control when the phone supports them.
- A small jitter buffer (60 ms to start, 160 ms maximum) keeps delay low. If the network is slow, the oldest frames are dropped instead of building up delay.
- A ping every 2 seconds measures round-trip time. The call screen shows one-way network delay, lost frames and buffer gaps.
- Calls work only between **directly linked** phones.

### Distance to nearby phones

| Method | Where it works | Accuracy |
|---|---|---|
| **Bluetooth LE signal strength (RSSI)** | Indoors and outdoors. Every phone advertises a tiny beacon (ID and name) and scans for others. | Rough, about ±3 m at short range. Varies by phone model. |
| **GPS** | Outdoors. Phones share their GPS position in presence beacons. Shows distance, compass direction and the error range. | 5-10 m outdoors. Can be off by 10-50 m indoors. |

---

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose, Material 3 |
| Networking | [Google Nearby Connections](https://developers.google.com/nearby/connections/overview) (`play-services-nearby`) |
| Location | Fused Location Provider (`play-services-location`) |
| Proximity | Bluetooth LE advertising and scanning |
| Audio | `AudioRecord`, `AudioTrack` (low-latency mode), `AudioManager` |
| Security | Android Keystore, ECDSA `SHA256withECDSA` |
| Build | Android Gradle Plugin 8.13, Gradle 8.13 |
| SDK | minSdk 26 (Android 8.0), compileSdk 36, targetSdk 35 |

---

## Getting started

### Requirements

- Android Studio (recent version) with JDK 21
- 2 or more Android phones, Android 8.0 or newer, with Google Play services
- Internet on your laptop for the first Gradle sync only

### Build and run

```bash
git clone https://github.com/<your-username>/CrisisMesh.git
```

1. Open the `CrisisMesh` folder in Android Studio (**File → Open**).
2. If asked for the Gradle JVM, choose **JDK 21**.
3. Wait for the Gradle sync to finish. Accept "Install missing SDK platform" if it appears.
4. Connect a phone by USB (or use Wireless debugging) and press **Run**.
5. Repeat for each phone, or copy `app/build/outputs/apk/debug/app-debug.apk` to the other phones and install it.

### First launch on each phone

1. Tap **Start** and allow every permission (Location, Nearby devices, Microphone, Notifications).
2. Keep **Bluetooth**, **Location** and **Wi-Fi** turned on. No internet is needed.
3. (Optional) In **Me → Sounds and shake**, tap **Allow opening from background** so the shake feature can open the app by itself.

### Permissions

| Permission | Used for |
|---|---|
| Bluetooth scan / advertise / connect, Nearby Wi-Fi devices | Linking phones, distance beacons |
| Location (fine and coarse) | GPS position, required by Android for Bluetooth scanning on older versions |
| Microphone | Voice calls |
| Notifications | SOS alerts, incoming calls |
| Foreground service (connected device, location, microphone) | Keep the mesh running with the screen off |
| Display over other apps (optional) | Shake to open from the background |
| Vibrate, modify audio settings | Alerts and call audio |

---

## Demo script

1. Start the mesh on 3 phones. Each shows **2 linked**.
2. **SOS:** on phone A, send a Medical SOS for 2 people. Phones B and C get an alarm, a notification, a feed card with distance, and a dot on the radar.
3. **Responder loop:** on phone B, go to **Me**, enter PIN `2580`, then open **Feed** and tap **Acknowledge**. Phone A shows "Help acknowledged".
4. **Voice call:** on phone A, open **Phones** and tap **Call** next to phone B's name. Phone B rings.
5. **Security:** on phone A, tap **Send a forged message**. B and C drop it and their counter increases.
6. **Store-and-forward:** turn off phone C, send an SOS from A, turn C back on. The SOS appears on C by itself.
7. **Multi-hop:** put A and C far apart with B in the middle. The SOS reaches C with `2 hops`.

> Testing calls with two phones in the same room? Use the earpiece or headphones, not the speaker, or you will hear an echo.

---

## Project structure

```
CrisisMesh/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/crisismesh/app/
│   │   ├── MainActivity.kt      Permissions, tabs, status bar, shake launch
│   │   ├── Screens.kt           SOS, Needs, Feed, Phones, Me screens
│   │   ├── CallScreen.kt        Incoming / active call screen
│   │   ├── MeshEngine.kt        Nearby links, relay, sync, presence, storage, service
│   │   ├── VoiceEngine.kt       Call signalling, audio capture and playback, µ-law
│   │   ├── Proximity.kt         Bluetooth LE beacons and RSSI distance
│   │   ├── Alerts.kt            Sounds, vibration, shake detector
│   │   ├── Models.kt            Message model, packet tags, signing (Keystore)
│   │   └── ui/theme/Theme.kt
│   └── res/                     Icons, theme, strings
├── gradle/libs.versions.toml    Dependency versions
└── build.gradle.kts, settings.gradle.kts
```

### Settings you can change

| Where | Setting | Default |
|---|---|---|
| `MeshEngine.kt` | `TTL_START` (max hops) | 8 |
| `MeshEngine.kt` | Presence interval | 4 s |
| `VoiceEngine.kt` | `PREBUFFER`, `MAX_DEPTH` (jitter buffer, in 20 ms frames) | 3, 8 |
| `Proximity.kt` | `REF_RSSI`, `PATH_LOSS` (distance model) | -62 dBm, 2.3 |
| `Models.kt` | `RESPONDER_PIN` (demo only) | 2580 |
| `Alerts.kt` | Shake strength, jerks needed | 2.6 g, 3 |

---

## Limitations

This is a hackathon prototype. Be aware of the following:

- **Android only.** It needs Google Play services for Nearby Connections.
- **Range.** Bluetooth links reach about 10-30 m per hop, so the mesh needs enough phones between people. Wi-Fi links can reach further.
- **Voice calls** work only between directly linked phones, not through relays. Call quality depends on the link and has not been measured formally.
- **Distance is approximate.** Bluetooth signal strength changes with phone model, body position and walls. GPS is weak indoors.
- **Messages are signed, not encrypted.** Any phone in the mesh that relays a message can read it.
- **Responder mode uses a demo PIN.** A real system needs responder certificates signed by a disaster authority.
- **Hop count and TTL are not signed.** Presence beacons (name and GPS) are not signed either.
- **Battery.** Bluetooth scanning, GPS and the microphone service use power. Long standby has not been tuned.
- **Shake to open** needs the mesh to be ON. Some phones stop sensors when the screen is off to save battery.

## Roadmap

- [ ] Responder web dashboard with a live map, and an internet gateway that uploads messages when a phone gets connectivity
- [ ] End-to-end encryption for private messages
- [ ] Responder certificates signed by an authority
- [ ] LoRa bridge nodes (ESP32 + Meshtastic) for kilometer-range links
- [ ] Offline maps
- [ ] Push-to-talk voice to a group
- [ ] Battery optimization and adaptive scanning
- [ ] iOS support

---

## Contributing

Issues and pull requests are welcome. Please open an issue first to discuss big changes.

## License

Add your license here (for example MIT) and put the `LICENSE` file in the repository root.

## Author

Built by `<your name>` for `<hackathon / event name>`.
