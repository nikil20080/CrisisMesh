CrisisMesh - open in Android Studio and run
============================================

1. Unzip this folder (for example to C:\Projects\CrisisMesh).
2. Android Studio > File > Open > choose the CrisisMesh folder (the one with settings.gradle.kts).
3. Wait for "Gradle sync" to finish. The first time it downloads about 500 MB,
   so connect the laptop to the phone hotspot or a good Wi-Fi.
   - If a yellow bar says "Install missing SDK platform", click it and accept.
   - If it offers to "Update Android Gradle Plugin", choose "Remind me later / Don't ask again".
4. Connect the phone (USB or wireless debugging) and press the green Run button.
5. Repeat step 4 for every phone (pick the phone in the device list at the top).
   Or copy app/build/outputs/apk/debug/app-debug.apk to the other phones and install it.

On each phone: tap Start, allow every permission (Location, Nearby devices,
Microphone, Notifications). Keep Bluetooth, Location and Wi-Fi turned on.

Tabs
  SOS     send an SOS with category, people count and GPS location; "I'm safe"
  Needs   request or offer water, food, medicine, shelter, power, rescue
  Feed    all messages, sorted by priority; Acknowledge / Resolve (responders); Navigate
  Phones  names of nearby phones, distance in meters, radar, and a Call button
  Me      your name, responder mode (demo PIN 2580), mesh stats, forged-message demo

Voice calls: Phones tab > Call on a linked phone. The other phone rings.
The call works with no internet and no mobile network. Calls need a direct link
(the phone must show "Linked, call available").
Testing with two phones in the same room: use the earpiece or headphones, not the
speaker, or you will hear an echo.


NEW IN THIS VERSION
===================

1. Shake to open (Me tab > "Shake the phone to open CrisisMesh", on by default)
   While the mesh is running, shaking the phone hard (4 quick jolts) opens CrisisMesh on the
   SOS tab. Works with the screen off (the app holds a small wake lock: extra battery use).
   Android 10+ restricts apps from opening themselves in the background, so:
     - Me tab > "Allow display over other apps"  -> the app opens directly.
     - Me tab > "Allow full-screen alerts" (Android 14+) -> wakes a locked screen.
     - Without them you still get a "Shake detected - tap to open" banner.
   Tuning: ShakeWatcher in Shake.kt (threshold 3.0 g, 4 jolts, 2 s window).

2. Alarm notification when an SOS arrives
   New notification channel "SOS and official alerts": alarm sound (plays even in silent mode),
   vibration, lock-screen visible. Shows category, people, message and "distance + direction from
   you", with a Navigate button. Official alerts also use a full-screen intent.
   Old messages that a neighbour hands over when a link starts (older than 30 min) do not ring.
   The status card shows a red warning if notifications are switched off.

3. Call recording attached to the victim's SOS
   If a phone has an open SOS (sent by that phone, not resolved, under 6 hours old) and its user
   is in a call, the user's own voice (first 30 s, 8 kHz mu-law, about 8 KB per second) is
   recorded. When the call ends a signed "AUDIO" message pointing at the SOS is sent, followed by
   the audio in 16 KB chunks. Every phone shows a "Call recording ... Play" row on that SOS card
   in the Feed. The audio is checked against the SHA-256 in the signed message.
   No call = nothing is recorded and the SOS stays exactly as it was.
   A red "Recording" line shows on the call screen. Switch it off in Me tab.
   Phones that link later also receive the recordings of open SOS messages (newest 3).
