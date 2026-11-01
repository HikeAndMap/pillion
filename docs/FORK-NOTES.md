# HikeAndMap fork — notes

This fork of [alexandrevega/pillion](https://github.com/alexandrevega/pillion) was made to run Pillion
on a **Yamaha NMAX Tech MAX** (Philippines, 2025/2026 model —
[model page](https://www.yamaha-motor.com.ph/motorcycles/personal-commuter/max-series/nmax-tech-max))
and to harden the Android app for long rides. Upstream's design, protocol work and license are
unchanged; everything here builds on that work. Not affiliated with Yamaha or Garmin.

## Tested on

| Bike | Phone / OS | Result |
|------|------------|--------|
| **Yamaha NMAX Tech MAX** (PH, 2025/2026) | Galaxy A52s 5G (SM-A528B) / Android 14 | ✅ streams; OsmAnd mirrored on the dash |

What was observed on that bike:

- The dash identifies as CCU part number **`006-B3952-01`**, so Pillion picks the **480×234** frame size
  automatically (the existing scooter-CCU handling works; no change was needed).
- **~13–16 fps** on simple screens; **~7–8 fps** on a busy OsmAnd map (≈15 KB/frame — this is the
  Bluetooth link's limit, not a bug). That is plenty for a moving map.
- Reconnects on its own after the bike is switched off and on again.
- The dash showed **"Bluetooth pairing failed"** (and an empty `""` name for the phone) while pairing,
  yet Pillion still connected and streamed. Pillion opens an *insecure* RFCOMM socket and the NaviLite
  handshake needs no pairing secret, so the dash's own pairing result did not matter in our tests.
  (This is an observation, not a guarantee for every dash firmware.)
- Pillion connects over **Bluetooth** (StreetCross-compatible profile). We did not use the bike's USB
  socket — on the test bike it never supplied power, so whether it carries data is untested.

## What this fork changes

Reliability (the same fixes would apply to any bike):

- **Reconnect handling.** The session shows **"Reconnecting to the bike…"** (new `Reconnecting` state)
  instead of a red error, retries on its own, and waits for the bike to come up after you tap Start.
  It gives up with a clear message after 10 failed reconnects (20 tries when first connecting).
  Setup problems (e.g. "not paired") still fail immediately.
- **Stall watchdog.** If the dash stops acknowledging frames for 10 s the link is dropped and
  reconnected, instead of hanging while looking alive.
- **Error messages survive.** `CaptureService.onDestroy` no longer overwrites the final error with Idle.
- **No Bluetooth socket leak** when a connect attempt fails.
- **Second Start is ignored** while a session runs (a second RFCOMM connection is refused by the dash
  and used to tear the working session down and flash a red error).
- **Dash-helper failure is no longer fatal**, and starting the dash-mode watchdog no longer blocks the
  main thread for 15 s.
- **Keeps the screen on (dimmed)** during plain mirroring, so the capture doesn't go black when the
  screen would time out. The wake locks now last **16 h** (was 3 h).
- **Closing the app from recents stops the session**, and the notification has a **Stop** button.
  Switching to another app (OsmAnd) or going home does *not* stop it.
- **Debug log file** (see below).

Personal preference (fork only; not meant for upstream):

- The "Experimental software" popup on every launch is disabled (`showDisclaimer` defaults to false in
  `App.kt`). The safety text in [SAFETY.md](../SAFETY.md) still applies — read it.

## Using it on an NMAX (what worked for us)

1. Pair the phone with the bike in Android's Bluetooth settings. The bike shows up as
   `YCCU_<serial>`. Confirm the passkey on both devices quickly. A "pairing failed" message on the dash
   did not stop Pillion from connecting, but if it won't connect, remove the pairing on both sides and
   pair once more.
2. Put the dash on its **navigation** screen.
3. Open Pillion → **Start mirroring** → allow screen capture ("Entire screen") and the Bluetooth
   prompt. Pillion needs the bike on; if it isn't yet, it shows "Reconnecting…" and connects when it is.
4. Open OsmAnd. For a landscape dash, set OsmAnd's **Settings → General settings → Screen
   orientation → Landscape** (this setting is **per profile**, so set it in the profile you ride with),
   or lock Android's rotation to landscape.
5. Keep the phone's screen on (Pillion holds it on, dimmed). **Screen-off casting needs "dash mode"**,
   which needs Wireless debugging (Wi-Fi) set up once; we did not use it.

Long rides: use a powerbank, keep the phone shaded and ventilated (screen capture + GPS + Bluetooth
make it warm), and lower Pillion's quality / fps if it overheats.

## Debug log

Pillion writes its own log (Pillion messages, Bluetooth, crashes, the handshake and dash part number) to:

```
/sdcard/Android/data/app.pillion/files/pillion-debug.log      (previous run: pillion-debug.log.1)
```

It is capped at ~2 MB and also records the bonded Bluetooth devices at each Start. Pull it with:

```bash
adb pull /sdcard/Android/data/app.pillion/files/pillion-debug.log
```

Useful lines: `rfcomm: connected to …`, `handshake: SEC_DATA part=…`, `session: dash size …`,
`session: N fps …`, `session dropped; reconnecting …`.

## Known limitations

- **Dash mode (screen off / locked)** needs Wireless debugging; the helper's loopback adb port is lost
  when it was enabled over USB and the cable is unplugged, and it is lost at every reboot. We left it off.
- **Portrait** is captured into a fixed wide area. Use landscape (see above). A "portrait crop" mode
  is not implemented.
- Dash-mode lock-screen foreground-app detection only looks back 60 s (`CaptureService.foregroundComponent`).
- iOS code was not built or tested in this fork (no Mac); the changes to shared code only add a state.

## Keeping up with upstream

```bash
git remote add upstream https://github.com/alexandrevega/pillion.git
git fetch upstream
git merge upstream/main
```

## Building

```bash
./gradlew :androidApp:assembleDebug           # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :composeApp:testAndroidHostTest
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Needs the Android SDK (`ANDROID_HOME`) and a JDK (21 worked here; upstream says 17).
