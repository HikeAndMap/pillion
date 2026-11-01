# Drafts for alexandrevega/pillion

**Posted:** A is https://github.com/alexandrevega/pillion/issues/21 and B is
https://github.com/alexandrevega/pillion/issues/22 (kept here as a record).

Two separate issues are easier for the maintainer to act on than one long one:
**A** is a compatibility report in the project's own template, **B** is a bug list with evidence and fixes.
Paste the text between the lines. Post under your own account.

---

## A — "New issue" → Compatibility report

**Title:** `Compatibility report: 2025/2026 NMAX Tech MAX (Philippines) + Galaxy A52s 5G + Android 14`

**Bike make / model / year:**
Yamaha NMAX Tech MAX, Philippines market, 2025/2026 model
(https://www.yamaha-motor.com.ph/motorcycles/personal-commuter/max-series/nmax-tech-max)

**The dash / CCU part number if you know it (e.g. 006-B4160-00):**
`006-B3952-01` (read from the handshake: `handshake: SEC_DATA part=006-B3952-01`). Pillion chose
**480×234** by itself (`session: dash size 480x234`), so the scooter-CCU handling from #7 works on this bike.

**What happened: did it connect? authenticate? show an image? what fps?:**
It connected, authenticated and showed OsmAnd on the dash.

- fps: **13–16 fps** on simple screens; **7–8 fps** on a busy OsmAnd map (about 15 KB/frame, ACK
  round-trip 110–160 ms), so it looks bandwidth-limited rather than a bug.
- After switching the bike off and on, Pillion reconnected by itself a few attempts later and streamed again.

**Pairing oddities (they did not stop Pillion from working):**
- While pairing, the dash showed `Pair with ""? Passkey: 142377` (the phone's name is shown as an empty
  string), then `Waiting for request approval from ""`, then **"Bluetooth pairing failed."** — both on the
  first attempt and when re-pairing from scratch.
- Confirmation was requested on **both** the phone and the dash.
- In the phone's Bluetooth scan the bike showed up **twice** (a classic and a BLE entry); the entry that
  paired is listed in Android as `YCCU_0006030100FF7E`, type `DUAL`.
- Even so, Pillion connected over RFCOMM and the handshake succeeded
  (`rfcomm: connected to YCCU_…` → `handshake: setup burst sent, authenticated`). Pillion uses
  `createInsecureRfcommSocketToServiceRecord`, so the dash's pairing result did not seem to matter.
  Possibly worth a line in the README ("a 'pairing failed' message on some dashes can be ignored").

**Phone model + Android version:**
Samsung Galaxy A52s 5G (SM-A528B), Android 14 (One UI)

**Extra:** A small bug list with fixes from the same test is in a separate issue (see B), and a fork with
the changes is at https://github.com/HikeAndMap/pillion (release `v0.2.1-alpha`).

---

## B — "New issue" → bug report (or put the same text in a pull request)

**Title:** `Reconnect/lifecycle bugs found on an NMAX test (fixes available in a fork)`

While testing on an NMAX Tech MAX (see my compatibility report) I hit several problems, mostly around
reconnecting after the bike was switched off/on and around tapping Start again. I wrote fixes for them
in a fork (https://github.com/HikeAndMap/pillion); commit links below. Happy to open PRs, split as you prefer.

Logs come from an on-phone debug log I added (it captures the app's own logcat to a file), so the
timestamps/excerpts are from a real session.

### 1. A second "Start" while a session runs tears the working session down and shows a red error
`CaptureService.onStartCommand` starts a new engine/RFCOMM link each time. The dash accepts one link, so
the new connect fails instantly (`rfcomm: … unreachable (read failed, socket might closed or timeout, read ret: -1)`),
and that failure's `MirrorState.Error` is published into the shared static state, so the UI shows a red
"Disconnected" while the first session was still alive/reconnecting. The old engine thread also keeps
running. Fix: ignore a Start while an engine is running and drop the unused single-use capture grant.

### 2. After the bike is switched off/on, the first connect attempt of a *new* start fails and the session ends
`MirrorEngine` only retries after the first successful connection (`if (!connectedOnce) throw t`). So a
Start while the bike is still booting ends in an error and the user must tap Start again. Fix: retry
"dash unreachable" (new `DashUnreachableException`) before the first connect, but still fail at once on
setup errors such as "not paired". Added a `Reconnecting` state and a "Reconnecting to the bike…" screen.

### 3. Reconnect loop never gives up
After a drop the engine retries forever (every 1.5 s) while holding the wake lock and, with #4, leaking
sockets. Fix: give up after N consecutive failures (reset on every ACKed frame), with a clear message.

### 4. `RfcommByteChannel.open()` leaks the failed socket
In the catch block it closes the *field* `socket` (still `null` or the previous one) instead of the local
socket that just failed. Over repeated retries this leaks BluetoothSockets.

### 5. No timeout while waiting for IMAGE_ACK → silent hang
`while (running && reader.next().serviceType != IMAGE_ACK)` blocks forever if the dash stays connected but
stops acknowledging (e.g. leaves the navigation screen); the UI still looks alive. Fix: a watchdog that
closes the channel after 10 s without an ACK, so the normal reconnect path runs.

### 6. The final error message is lost
Error → `stopSelf()` → `onDestroy()` sets the shared state to `Idle`, so the "Disconnected: <reason>"
screen is overwritten and the user sees no reason. Fix: keep the error through `onDestroy` (a user Stop
still clears it).

### 7. Dash mode: a helper failure is fatal, and the watchdog start blocks the main thread
- If `DashHelper.ensureRunning` fails (no Wireless debugging), `fail()` calls `stopSelf()` and kills an
  otherwise healthy Bluetooth session two milliseconds after `session: streaming`. Fix: fall back to plain mirroring.
- `DashHelper.startWatchdog` is `@Synchronized` and was called on the main thread while `ensureRunning`
  held the same lock for the 15 s ADB connect timeout. The log showed
  `Long monitor contention … DashHelper.ensureRunning … for 15.010s`, i.e. a 15 s UI freeze on every Start.
  Fix: start the watchdog from the helper coroutine after `ensureRunning`.

### 8. The mirrored picture goes black when the phone screen turns off (plain mirroring)
Matches the behaviour in #5 ("went blank as soon as the phone screen turned off"). The capture follows
the physical display. In my fork, plain mirroring holds a dim screen wake lock for the session.

### 9. Wake lock expires after 3 h
`MAX_SESSION_MS = 3h` and nothing renews it; a longer ride silently loses the CPU wake lock. I raised it
to 16 h; renewing it while streaming would be cleaner.

### 10. Smaller things
- Closing the app from recents leaves the foreground service (and Bluetooth link) running, with no Stop
  action. Fork: `onTaskRemoved` stops the session and the notification has a **Stop** action.
- `CaptureService.foregroundComponent()` only looks at the last 60 s of usage events, so with dash mode
  it logged `UsageStats returned no launchable foreground app` / `foreground=null` when the app had been
  in front for longer than a minute, and nothing was promoted to the dash.
- Build warnings that "will become an error in a future release": `SdlService.kt:225` / `:249`
  (KTLC-14, inferred return types of `setAppTypes` / `setRPCNotificationListeners`).
- Dash-mode setup: while trying to pair Wireless debugging with a code on a PC-hotspot Wi-Fi, the code was
  refused. The log shows `adb pairing: no cached endpoint, restarting discovery for code submit`, the
  pairing service found once and lost again about 7 s later. I'm not sure if this is Pillion or my network;
  mentioning it in case others see it.

### Fixes
All in https://github.com/HikeAndMap/pillion:
- Engine, reconnect, watchdog, socket leak: https://github.com/HikeAndMap/pillion/commit/73b0640
- Debug log file: https://github.com/HikeAndMap/pillion/commit/07f3998
- `CaptureService` lifecycle fixes: https://github.com/HikeAndMap/pillion/commit/a1d9af3

Unit tests pass (`./gradlew :composeApp:testAndroidHostTest`), and I added one for the unreachable-dash
retry path. I haven't compiled the iOS target (no Mac); the shared changes only add a `Reconnecting` state.

---

## Notes for you (not part of the issues)

- Before posting, double-check the wording about the dash's pairing messages against your own photos.
- Say if you want these as a pull request instead; I'd make a clean branch with only the fixes (no
  popup removal, no update-checker or version changes).
