# Improving a Peloton Tread

Notes, process, and working code from adding features to a Peloton Tread by
sideloading a custom Android app onto its built-in tablet. The result is a
small always-on-top overlay that shows live **speed, incline, and a
stopwatch/countdown timer** over any app running on the tablet (YouTube,
Netflix, whatever), with no subscription and no modification to Peloton's
software.

![What you get](docs/overlay.png)

**The short version:** the Tread's tablet is a normal Android 10 device. The
services that read the treadmill's sensors are ordinary Android services, and
the ones you need are exported with no permission gate. You can bind to them
from your own sideloaded app and read live speed/incline with about a hundred
lines of code.

> **Disclaimers:** This is not affiliated with or endorsed by Peloton. Doing
> this may void your warranty. Don't do anything that could affect the safety
> systems of a machine you run on. Everything here is read-only access to
> sensor data; stick to that. Do not redistribute Peloton's APKs or any
> decompiled source derived from them.

## Hardware

- Peloton Tread (model TTR01), the built-in tablet runs Android 10.
- No root required. No bootloader unlocking. Plain `adb` sideloading.

## Process

### 1. Get adb access

Enable Developer Options on the tablet (Settings → About → tap Build Number
seven times), turn on USB debugging, and connect from a computer. Wireless
debugging also works once enabled: `adb tcpip 5555`, then
`adb connect <tablet-ip>:5555`.

### 2. Recon: find out what's running

List the Peloton packages and their services:

```bash
adb shell pm list packages | grep -i peloton
adb shell dumpsys package com.onepeloton.affernetservice | less
```

Interesting packages on the Tread:

| Package | Role |
|---|---|
| `com.onepeloton.affernetservice` | Hardware bridge; talks to the tread's motor control board over serial |
| `com.onepeloton.workoutservices.app` | Metrics aggregation (`MetricsService`) |
| `com.onepeloton.systempluginui` | Draws the stock speed/incline status bar |
| `com.onepeloton.odyssey` | The main Peloton UI app |

Leave `com.onepeloton.sensorstateindicator` alone; it is involved in the
tread's lock/safety controls.

### 3. Study the interfaces

Pull the APKs and decompile them locally with [jadx](https://github.com/skylot/jadx)
to understand the service interfaces (interoperability study; keep the
decompiled output private):

```bash
adb shell pm path com.onepeloton.affernetservice
adb pull /path/from/previous/command affernet.apk
jadx -d affernet-src affernet.apk
```

Things to look for in the decompiled source and merged `AndroidManifest.xml`:

- Which services are `exported="true"`.
- Whether an exported service has an `android:permission` attribute, and if
  so, the `protectionLevel` of that permission. A permission declared with
  default (`normal`) protection is granted automatically to any sideloaded
  app that requests it in its manifest.
- The AIDL interface descriptor strings and transaction codes. Even without
  the original `.aidl` files you can call a binder interface with raw
  `Binder.transact()` calls; the Stub/Proxy classes in the decompile tell you
  the transaction code numbers and parcel layouts.

The [grupetto](https://github.com/doudar/grupetto) project (an overlay for
the Peloton Bike) documents the equivalent interfaces on the Bike and was a
very useful reference for the general approach.

### 4. The data path that works

`AffernetService` in `com.onepeloton.affernetservice` is exported, has no
permission gate, and exposes simple synchronous getters:

- Bind with an `Intent` whose action is
  `com.onepeloton.affernetservice.ITreadInterface` and package
  `com.onepeloton.affernetservice`.
- Transaction code **17** = `getCurrentSpeed()` → int
- Transaction code **18** = `getCurrentIncline()` → int
- Raw values are tenths: display value = `abs(raw) / 10.0`. A raw reading of
  `(65, 20)` means 6.5 mph at 2.0% incline.

Poll every 500 ms and you exactly track the stock status bar. These getters
are read-only and work even while the tread console is locked.

A second path exists through `MetricsService` in
`com.onepeloton.workoutservices.app` (register a callback binder, receive
metric bundles containing a map of speed/incline/power/heart-rate stats).
It works, but the metric stream only flows while the tread is unlocked, and
the service has actions with side effects (its `START`/`END` actions control
the shared sensor stream; sending `END` would stop metrics for the stock UI
too). The client code for this path is included in the app for reference but
unused. The polling path is simpler and safer.

### 5. Build the overlay app

See [`overlay-app/`](overlay-app/) for the complete, buildable source and
usage instructions. Summary of the approach:

- A foreground `Service` adds a `TYPE_APPLICATION_OVERLAY` window (the
  `SYSTEM_ALERT_WINDOW` permission), so the pill floats above everything,
  including the lock screen.
- The window uses `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so it never
  steals input from the app underneath; only touches on the pill itself are
  consumed.
- `AffernetClient` binds the hardware service and polls speed/incline.
- Timer with two modes: stopwatch, and a countdown with quick-add buttons, a
  draining progress bar, and a beep-and-flash alert at zero that rolls into
  counting overtime.

### 6. Deploy

```bash
adb install -r app-debug.apk
adb shell appops set net.whyne.treadoverlay SYSTEM_ALERT_WINDOW allow
adb shell am start -n net.whyne.treadoverlay/.MainActivity
```

## Loading streaming apps (YouTube, Netflix, etc.)

The tablet is a normal Android 10 device underneath, so it can run normal
Android apps. It ships without Google Play, so you sideload a launcher and
app stores over adb. The overlay floats above all of it.

### 1. Install a real launcher

The stock UI is a kiosk. Install a launcher so the home button gives you a
normal Android desktop; the Peloton app stays installed and can still be
launched like any other app.

```bash
adb install lawnchair.apk   # https://lawnchair.app (or F-Droid)
adb shell cmd package set-home-activity app.lawnchair/app.lawnchair.LawnchairLauncher
```

### 2. Install app stores

- **[F-Droid](https://f-droid.org)** — open-source app catalog. `adb install fdroid.apk`.
- **[Aurora Store](https://auroraoss.com)** — a client for the Google Play
  catalog that works with anonymous sessions, so the tablet needs no Google
  account and no Google services. `adb install aurorastore.apk`.

From there you can install apps on the tablet itself. **Netflix** installs
straight from Aurora Store and works.

Note: apps that hard-require Google Play Services won't run (there is none
on this tablet). Netflix and most video apps don't.

### 3. YouTube

Two options, both without Google services:

- **[NewPipe](https://newpipe.net)** (via F-Droid) — lightweight YouTube
  client, no account, no ads, works out of the box. The easy path.
- **ReVanced YouTube + microG** — the full official YouTube app experience.
  Use the [ReVanced CLI](https://github.com/ReVanced/revanced-cli) on a
  computer to patch an official YouTube APK, making sure the **GmsCore
  support** patch is included (it retargets the app from Google Play
  Services to microG and renames the package). Then install both the
  patched YouTube and [ReVanced GmsCore](https://github.com/ReVanced/GmsCore)
  (microG):

  ```bash
  adb install revanced-gmscore.apk
  adb install youtube-revanced.apk
  ```

  Check the patch compatibility list for which YouTube version to patch;
  it must also run on Android 10.

### 4. Keep OTA updates from undoing your work (optional)

Peloton's updaters will happily pull a new image over your changes. They can
be disabled per-user with adb (reversible with `pm enable`):

```bash
adb shell pm disable-user --user 0 com.peloton.updater
adb shell pm disable-user --user 0 com.onepeloton.OTAService
adb shell pm disable-user --user 0 com.onepeloton.bgupdater
adb shell pm disable-user --user 0 com.onepeloton.fwupdateservice
```

Trade-off: you're also freezing security and firmware fixes; re-enable
temporarily if you ever want to take an update on your own schedule.

### ⚠️ Do not disable `com.onepeloton.sensorstateindicator`

It looks like it just draws the tread-lock screen, but it is also the
unlock mechanism that **arms the belt controller**. With it disabled, the
physical speed/incline controls stop working (they flash red). If you make
this mistake: `adb shell pm enable com.onepeloton.sensorstateindicator` and
reboot the tablet. The lock passcode is inseparable from working controls;
leave this package alone.

### Misc

- No sound in your video apps? Check the media volume stream — it ships at
  zero: `adb shell media volume --stream 3 --set 10`.
- After a tablet reboot, everything sideloaded is still there; only the
  overlay needs a manual start (see above).

## Lessons learned

1. **Read-only beats clever.** The synchronous getters (poll speed/incline)
   were more reliable than the fancier callback stream, and carry zero risk
   of disturbing the stock software. When a vendor service exposes both,
   take the boring path.

2. **Check `protectionLevel` before assuming a permission is a wall.**
   `com.onepeloton.permission.METRICS_SERVICE` looks protective, but it is
   declared with default protection, so any sideloaded app that requests it
   gets it. The manifest tells you; verify with
   `adb shell dumpsys package <permission-holder>`.

3. **You don't need the `.aidl` files.** Raw `binder.transact(code, data,
   reply, 0)` with the right interface token reproduces any AIDL call. The
   decompiled Stub classes give you the codes. Write the interface token
   first (`data.writeInterfaceToken(...)`) or the far side rejects the call.

4. **Foreign Parcelables can crash you.** Some callbacks deliver bundles
   containing the vendor's own Parcelable classes. Unparceling those in your
   process needs a local stub class with a matching wire format, and a
   `catch (Throwable)` around the whole thing for the types you didn't stub.

5. **Scaling conventions hide in utility classes.** The raw ints are tenths,
   and incline can be negative on the wire while displayed as positive. The
   vendor's own formatting helpers (found in the decompile) settle arguments
   about what the numbers mean.

6. **`SystemClock.elapsedRealtime()` for timers, never wall clock.** Wall
   time jumps with NTP syncs; elapsedRealtime doesn't. Accumulate elapsed
   milliseconds on pause rather than storing start/stop timestamps.

7. **Ceil the countdown display.** A countdown set to 10:00 should show
   10:00 at start, not 09:59. Add 999 ms before formatting the remaining
   time.

8. **These tablets are nearly out of storage.** A 4 GB data partition at 96%
   full made `adb install` fail with "not enough space." Safe fix:
   `adb shell pm trim-caches 2G` reclaims app cache without touching user
   data.

9. **Anchor overlay windows to the bottom edge** (`Gravity.BOTTOM`) if you
   ever show UI above them; the panel then expands upward without moving the
   pill. Remember that with bottom gravity the y offset grows upward, so
   invert drag deltas.

10. **Verify on-device with screenshots.** `adb shell screencap -p
    /sdcard/x.png && adb pull ...` after each change catches layout issues a
    build log never will. Clean up the screenshots afterward (see lesson 8).

11. **Keep decompiled code out of your repo.** Decompiling for
    interoperability is one thing; publishing the vendor's code is another.
    Publish only your own clean-room client code and notes like these.

## Repo layout

```
overlay-app/   Complete Android project for the overlay (build + deploy docs inside)
docs/          Screenshots
```
