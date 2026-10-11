# Loc

A private, on-device location timeline for Android. MIT licensed, sideloaded, no Google account or API keys needed.

**Status: Phase 0 spike.** This build only proves that background collection survives on a real phone. There is no timeline UI yet.

## What the spike does

- Foreground service (`location` type) with a persistent notification
- Uses only Android platform APIs, so it works without Google services
  - `LocationManager` (GPS + network providers)
  - Significant-motion sensor to switch between a slow "still" rate (5 min) and a faster "moving" rate (30 s, GPS)
  - If the phone has no significant-motion sensor, it samples at the moving rate
- Writes raw points to a local Room database (`raw_point`), never modified afterwards
- Restarts after boot and app update if tracking was on
- Debug screen: permission flow, battery-optimisation exemption, start/stop, points today, last fix, gaps over 10 minutes (flagged when moving), CSV export of the last 7 days

Nothing leaves the device. There is no network code.

## Build

```
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Requires JDK 17+ and the Android SDK (platform 34); set `sdk.dir` in `local.properties`.

Install: `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to the phone and open it.

## Running the Phase 0 test

1. Open the app. Tap **1. Grant permissions** and accept fine location, then choose **Allow all the time** for background location, then notifications.
2. Tap **2. Exempt from battery optimisation** and allow.
3. Tap **Start tracking**.
4. On Xiaomi, Samsung, Huawei or OnePlus, also enable the manufacturer's autostart / "never sleep" setting for the app (see https://dontkillmyapp.com).
5. Carry the phone normally for 3 to 5 days, including at least one commute day and one desk day.

Optional, without root: grant the permissions over `adb` so the system does not need prompts.

```
adb shell pm grant io.github.tonybenoy.loc android.permission.ACCESS_BACKGROUND_LOCATION
adb shell cmd appops set io.github.tonybenoy.loc RUN_ANY_IN_BACKGROUND allow
```

## Measuring battery

Reset stats after a full charge, then read the app's share after a day.

```
adb shell dumpsys batterystats --reset
# ... a day of normal use, unplugged ...
adb shell dumpsys batterystats --charged io.github.tonybenoy.loc
```

Look at the app's estimated power use ("Estimated power use (mAh)") against the battery capacity. For a clearer picture, use Battery Historian on a bug report (`adb bugreport`).

## Exit criteria

- No gap over 10 minutes while moving (shown on the debug screen as "while moving")
- Battery cost under about 5% a day

Record which activity mix caused any miss: commute day vs desk day.

## Planned comparison

Once the platform-API path is measured, compare it with Google Fused Location plus Activity Recognition on the same days (battery and gaps). Those need no API key but need Play Services, so they will live in a separate `gms` build flavour.

## License

MIT. See `LICENSE`.
