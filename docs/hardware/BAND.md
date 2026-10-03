# Talaria Band (hardware track, draft v0.1)

> Pinch to talk, release to send. Swipe your thumb to scroll. Your wrist becomes the controller for your agent, and later for your glasses.

**Status:** exploration. A separate hardware track that does not block the software roadmap. It complements the [Talaria Ring](RING.md).

## 1. Concept

A wristband that recognises **hand and wrist gestures** and sends them to Talaria as events. Like the ring, it has **no microphone**: listening happens on the phone or earbuds. It gives **haptic feedback** through a vibration motor.

```
 pinch & hold ──BLE──▶ phone (Talaria) ──▶ assistant listens (earbuds/phone mic)
 release      ──BLE──▶ send
     ▲                                              │
     └──────────── haptics: listening · sent · error ◀┘
```

## 2. Inspiration: Meta Neural Band

Meta's band reads **surface electromyography (sEMG)**, the electrical activity of forearm muscles, sometimes before a finger visibly moves. Reported design: **16 dry gold-plated electrode pods** sampling at **2 kHz**; recognises pinch, tap and swipe in the air, wrist-roll cursor control, and handwriting traced on a surface (~21 words per minute median in research). Meta has published large open sEMG datasets: **emg2qwerty** (346 h, 108 participants) and **emg2pose** (370 h, 193 participants).

Talaria does not try to match that. It aims for the useful subset that a hobbyist can build.

## 3. Sensing levels

| Level | Sensors | Detects | Feasibility |
|---|---|---|---|
| **1. Motion band** ⭐ | 6-axis IMU (accelerometer + gyroscope), sampled fast enough to catch micro-vibrations | Wrist flick, rotation, raise-to-talk; **pinches and finger taps** as vibration signatures classified by a small model. (Apple Watch Double Tap is a commercial example of motion-based pinch detection, combined with its optical sensor.) | ✅ High |
| **2. Motion + light sEMG** | Level 1 + **2–4 sEMG channels** (biopotential front-end, dry electrodes) | Finger discrimination, subtle low-motion pinches | ⚠️ Medium–hard |
| **3. Research-grade sEMG** | 16+ channels, custom electrodes, large models | Handwriting, near-invisible gestures | ❌ Out of scope |

Hard parts of sEMG (levels 2–3): electrode–skin contact, motion artifacts, sweat, per-user calibration, and power (kHz sampling costs far more than an IMU).

## 4. Gesture vocabulary

| Gesture | Default action |
|---|---|
| **Pinch and hold** | **Push-to-talk:** listen while held, **release to send** |
| Raise wrist + pinch | Talk (the two-part activation cuts false triggers) |
| Double pinch | "Reply to that": answer the most recent message by voice |
| Thumb swipe along the index finger | Scroll / volume / next |
| Wrist rotate | Move a cursor or highlight (smart-glasses UI, later) |
| Flick away | Dismiss / cancel |
| Custom | Mapped to Talaria rules |

**False triggers are the main usability risk.** Mitigations: two-part activation gestures, confidence thresholds, haptic confirmation before acting, one-gesture undo, and an "off-wrist / sleeping" detector that disables gestures.

## 5. System design

### 5.1 On-band inference
- A small model (TinyML, e.g. trained with Edge Impulse) runs **on the band** and emits **gesture events only**. That keeps power low, latency short and raw data private.
- The firmware keeps a short ring buffer of sensor data, so a gesture can be confirmed after the fact (e.g. a pinch recognised 150 ms after it started).

### 5.2 Training mode
- Streams raw IMU (and sEMG, if present) to the phone or laptop to **record personal gesture samples**.
- Models are trained per user on the laptop (a GPU helps, but small models train fine on a CPU), then flashed to the band over the air (signed DFU).

### 5.3 Talaria integration
The band is a **relayed device** behind the phone ([PROTOCOL.md §11](../PROTOCOL.md#11-relayed-devices-watch-glasses)), exactly like the ring:

```json
"relayed": [{"path": "band", "name": "Talaria Band", "platform": "band",
             "capabilities": [{"name": "haptic.pulse", "v": 1, "tier": 0}],
             "events": ["gesture.pinch_start", "gesture.pinch_end", "gesture.double_pinch",
                        "gesture.swipe", "gesture.rotate", "gesture.flick",
                        "wear.on_wrist", "battery"]}]
```

- Paired via Android **CompanionDeviceManager**, so it can wake Talaria from the background reliably.
- **Multi-device use** follows the ring's strategies and bridge arbitration ([RING.md §5](RING.md#5-using-one-ring-with-several-talaria-devices)): phone as hub first, multi-host and encrypted broadcast later.
- A continuous `gesture.rotate` stream (for cursor control) is sent only while a "pointer mode" is active, to save power.

### 5.4 Ring + band together
| | Ring | Band |
|---|---|---|
| Input | A definite capacitive tap (almost no false positives) | Rich gestures (pinch, swipe, rotate) |
| Room | Tiny battery, minimal parts | Larger battery, IMU, haptics, optional PPG (heart rate) |
| Best for | "Talk now" | Push-to-talk, navigation, glasses control |

Combined example: tap the ring to open the assistant, then use the band to scroll and select results on glasses.

## 6. Hardware

### 6.1 Prototype platforms (off the shelf)
| Platform | Why |
|---|---|
| **Bangle.js 2** | Open-source hackable smartwatch (IMU, heart-rate sensor, BLE, JavaScript); Edge Impulse has a gesture-recognition tutorial for it |
| **Seeed XIAO nRF52840 Sense** | Tiny board with a built-in 6-axis IMU; used in 3D-printed gesture wristbands |
| PineTime | Open-source watch with community firmware |

### 6.2 Custom band (draft parts list)
| Part | Candidate |
|---|---|
| SoC | Nordic nRF52840 or nRF54L series (BLE, Zephyr, enough RAM for TinyML) |
| IMU | Low-power 6-axis IMU with a high-rate mode and FIFO |
| Haptics | LRA or ERM motor + driver |
| Power | LiPo (100–200 mAh fits a band), charger IC, fuel gauge |
| Optional | PPG sensor (heart rate; may help pinch detection); 2–4-channel biopotential front-end + dry electrodes (level 2) |
| Enclosure | 3D-printed TPU/resin pod on a standard 20–22 mm watch strap |

## 7. Security and privacy
- **LE Secure Connections bonding**; encrypted events; signed firmware updates.
- Gestures only **trigger** listening or navigation. Lock-screen limits and send confirmations still apply ([SECURITY.md](../SECURITY.md)).
- Raw sensor data leaves the band **only in training mode**, which the user starts explicitly. Training data stays on the user's own devices.
- Biometric-adjacent data (sEMG, heart rate) is never sent to the agent or the model provider.

## 8. Plan

| Step | What | Exit |
|---|---|---|
| B0 | Bangle.js 2: train pinch, double pinch and flick with Edge Impulse; BLE events → a Talaria test app | > 90% recognition on your own gestures, < 1 false trigger per hour of normal activity |
| B1 | Custom motion band (nRF + IMU + haptics + battery); push-to-talk working end to end | A week of daily push-to-talk use; multi-day battery |
| B2 | Experiment: add 2–4 sEMG channels; compare accuracy and false triggers with motion-only | A data-backed decision on whether sEMG is worth it |
| B3 | Pointer mode (wrist rotate + pinch to select) for glasses / desktop UI | Navigate a menu without touching the phone |

Depends on: roadmap **M2c** (default assistant), **M6/M11** (background, relayed devices). It shares the companion-device and multi-device work with the [ring](RING.md).
