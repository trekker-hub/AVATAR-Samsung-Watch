# AVATAR — Galaxy Watch 5 Sensor Test App

A Wear OS app built for the **AVATAR research project at George Mason University (Summer 2026)**.  
It provides a live, on-device readout of all available Samsung Health sensors on the Galaxy Watch 5.

---

## What it does

The app connects to the Samsung Health Sensor SDK and presents a simple menu — one button per sensor type. Tap a sensor to start reading; tap **Stop / Back** to return to the menu. Only one sensor runs at a time to avoid hardware conflicts.

| Sensor | Mode | Data shown |
|---|---|---|
| Heart Rate | Continuous | BPM, signal quality, IBI list |
| Accelerometer | Continuous | X / Y / Z axes |
| PPG Green | Continuous | Raw PPG value + status |
| **EDA (skin conductance)** | Continuous | Skin conductance (µS) + status — **Watch 8 only** |
| PPG IR + Red | On-demand (~30 s) | IR and Red PPG values + status |
| SpO2 | On-demand (~30 s) | Blood oxygen %, heart rate |
| ECG | On-demand (~30 s) | ECG (mV), lead-off contact, sequence |
| Body Comp / BIA | On-demand (~15 s) | Body fat %, skeletal muscle, body water, BMR |
| Multi-freq BIA | On-demand (~15 s) | Impedance at 5 / 50 / 250 kHz + phase — **Watch 8** |
| Skin Temperature | Continuous | Skin °C, ambient °C |
| Skin Temperature | On-demand (~5 s) | Skin °C, ambient °C |

The menu is built dynamically from the watch's reported capability list, so **only sensors your specific device supports are shown**. On a Galaxy Watch 5 the EDA and Multi-freq BIA buttons are hidden automatically (the hardware doesn't report them); on a Galaxy Watch 8 all rows above appear.

While a sensor is reading, a live status line shows either `🟢 live · Ns` (continuous) or `⏳ measuring · Ns / ~30s` (on-demand) so you can tell it's working during the long SpO2 / ECG / BIA measurements. On-demand sensors that need finger contact (ECG, BIA) display an on-screen instruction.

---

## Hardware & SDK

- **Devices:** Samsung Galaxy Watch 5 and Galaxy Watch 8 (same APK; menu adapts per device)
- **OS:** Wear OS 3+ (One UI Watch 4 on Watch 5 · One UI 8 Watch on Watch 8)
- **SDK:** Samsung Health Sensor API 1.4.1 (bundled in `libs/`)
- **Min SDK:** 30 · **Target SDK:** 35 · **Compile SDK:** 36.1

> **EDA note:** EDA is Galaxy Watch 8-only hardware. Because `targetSdk = 35` (Android 15),
> the legacy `BODY_SENSORS` permission still covers it. If you later raise `targetSdk` past 35,
> switch to the runtime health permissions (`+ READ_ADDITIONAL_HEALTH_DATA`) per Samsung's EDA codelab.

---

## Permissions

The app requests two runtime permissions on first launch:

- `BODY_SENSORS` — required for all health sensors (HR, PPG, SpO2, skin temp)
- `ACTIVITY_RECOGNITION` — required for the accelerometer

Both dialogs appear as standard Android permission prompts on the watch. Tap **Allow** on each.

---

## Getting started

### Prerequisites
- Android Studio (Hedgehog or later)
- Android SDK with Wear OS platform (API 30+)
- A Galaxy Watch 5 in developer mode, connected via ADB or Wireless debugging

### Build & install

```bash
git clone https://github.com/trekker-hub/AVATAR-Samsung-Watch.git
cd AVATAR-Samsung-Watch
```

Open the project in Android Studio, select the **app** run configuration, and run it on your paired watch.

> **Note:** The Samsung Health SDK AAR is included in `libs/` — no extra download needed.

### Developer mode (if you see `SDK_POLICY_ERROR`)

On the watch:  
**Settings → Apps → Samsung Health Platform → tap the version number 10 times**  
This unlocks sensor access for unsigned/debug builds.

---

## Project structure

```
app/src/main/
  java/.../presentation/
    MainActivity.kt       # All app logic and UI (single file)
    theme/Theme.kt        # Wear Material3 theme wrapper
  AndroidManifest.xml
libs/
  samsung-health-sensor-api-1.4.1.aar
```

---

## Research context

AVATAR is a biometric research project investigating real-time physiological signal collection on consumer wearables. This app serves as a sensor validation and data verification tool — confirming that the Galaxy Watch 5 correctly surfaces EDA, HR, PPG, and temperature data through Samsung's SDK before integration into the main AVATAR pipeline.
