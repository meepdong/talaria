# Talaria Ring (hardware track, draft v0.1)

> A tap on your finger opens your agent. A buzz tells you it heard you.

**Status:** exploration. This is a separate hardware track and does not block the software roadmap.

## 1. Concept

The ring is a **tiny, low-power trigger plus a haptic motor**. It has **no microphone**. Listening happens on the phone or earbuds, and thinking happens in your agent. Keeping the ring this simple is what makes it small, cheap and long-lasting.

```
 tap ring ──BLE──▶ phone (Talaria) ──▶ assistant opens ──▶ listens via earbuds mic (or phone mic)
    ▲                                                          │
    └────── haptics: "listening" · "done" · "Asha messaged" ◀──┘
```

## 2. Base design: Open Ring

[stawiski/open-ring](https://github.com/stawiski/open-ring) (MIT, open hardware and firmware) already solves the form factor:

| Part | Open Ring choice |
|---|---|
| BLE SoC | Dialog **DA14531** (ultra-low-power BLE 5.1) |
| Capacitive touch | Atmel **ATSAML10E16A** MCU |
| Power | TI **BQ25125** PMIC; small LiPo |
| Feedback | Vibration motor |
| Storage | Macronix MX25R2035 flash |
| Charging | **Wireless induction**, separate charger (STM32G030F6) |
| Board | **Flex PCB**, component-free at the battery section, which doubles as the touch sensor; cut to length for ring sizes US6–US13 |
| Thickness | **< 3 mm** (wedding-band profile) |

Plan: build Open Ring as-is first, then change firmware, then fork the hardware if needed (shell, battery, or a Nordic SoC such as nRF54L for its Zephyr tooling).

## 3. Interaction

### Gestures (capacitive sensor)
| Gesture | Default action |
|---|---|
| Tap | Open the Talaria assistant and listen |
| Double tap | "Reply to that": answer the most recent message by voice |
| Long press | Stop / cancel speaking |
| Swipe along the band | Volume or next/previous |
| Custom | Mapped to Talaria rules (e.g. triple tap → "Heading home" to family) |

### Haptics (output)
| Pattern | Meaning |
|---|---|
| Short buzz | Listening |
| Double buzz | Done / sent |
| Long buzz | Error |
| Custom per contact or agent | Know who messaged without looking |

### Audio source (no ring mic)
| Situation | Mic used | Quality |
|---|---|---|
| Earbuds connected | **Earbud mic**, replies spoken in your ears | Best |
| Phone in hand or on desk | Phone mic | Good |
| Phone in pocket, no earbuds | Phone mic | Poor. Use earbuds, or raise the phone. |

## 4. Connecting to Talaria

### 4.1 Mode A: Bluetooth remote (prototype, zero app code)
The ring presents itself as a standard **Bluetooth HID** input device (HID over GATT) and sends the consumer-control **voice assistant** key. If Talaria holds Android's default-assistant role (roadmap M2c), Android opens it with no Talaria code at all.
Caveat: the key mapping can differ between Android builds. Verify on OxygenOS first.

### 4.2 Mode B: companion device (full version)
- Paired through Android's **CompanionDeviceManager**. Companion apps can start background work when their device connects, which keeps the ring reliable under aggressive OEM battery management.
- A custom GATT service carries gesture events, haptic commands, battery level and firmware updates (DFU).
- In Talaria's protocol, the ring is a **relayed device** behind the phone ([PROTOCOL.md §11](../PROTOCOL.md#11-relayed-devices-watch-glasses)):

```json
"relayed": [{"path": "ring", "name": "Talaria Ring", "platform": "ring",
             "capabilities": [{"name": "haptic.pulse", "v": 1, "tier": 0}],
             "events": ["gesture.tap", "gesture.double_tap", "gesture.long_press",
                        "gesture.swipe", "battery"]}]
```

Rules and the agent can then use it, e.g. *"buzz the ring twice when my build finishes"*, or *"triple tap → start my focus playlist"*.

## 5. Using one ring with several Talaria devices

A BLE peripheral usually holds **one active connection at a time**, so "multiple devices" needs a strategy:

| Strategy | How | Pros | Cons |
|---|---|---|---|
| **1. Phone as hub** ⭐ (v1) | The ring talks only to the phone. Talaria relays gestures through the bridge to whichever device should act (laptop, tablet). | One radio link, best battery, simplest firmware; works anywhere the bridge is reachable | Needs the phone nearby; network adds about 100–300 ms |
| **2. Multi-host switching** (v2) | The ring stores bonds for up to 3 hosts and connects to one at a time, like a multi-device keyboard; a gesture switches host | No phone needed for the laptop | Manual switching, reconnect delay |
| **3. Encrypted broadcast** (advanced) | No connection: on each gesture the ring sends a short burst of **encrypted BLE advertisements**. Every nearby paired Talaria device can hear it; the bridge decides which one acts. | Works with many devices at once, very power-efficient (radio only on gestures) | Needs a rolling counter and shared key against replay; Android background scanning can be throttled by OEMs |

**Who responds?** The bridge arbitrates by event sequence number, so exactly one device acts:
1. An explicit mapping wins (e.g. "double tap = laptop").
2. Otherwise, the device with the most recent user activity (unlocked, screen on, recent input).
3. Otherwise, the phone.

## 6. Security
- **Bonding with LE Secure Connections**, so others cannot pair or inject taps. Broadcast mode encrypts each event with a per-ring key plus a rolling counter (replay protection).
- A stray tap only **opens listening**. Lock-screen limits and send confirmations still apply ([SECURITY.md](../SECURITY.md)).
- Firmware updates are signed; the ring rejects unsigned images.

## 7. Hard parts

| Challenge | Why | Mitigation |
|---|---|---|
| Battery sourcing | Curved 10–20 mAh LiPo cells are hard to buy in small quantities | Use Open Ring's cell; ask its community for suppliers |
| Antenna | The finger detunes it; **metal shells block BLE** | Resin or ceramic shell, or a non-metal window; follow Open Ring's antenna layout |
| Waterproofing | Hands get wet | Epoxy/resin potting; induction charging means no open ports |
| Assembly | Fine-pitch parts on flex | Flex + stiffener assembly services (JLCPCB, PCBWay); expect yield losses on early batches |
| Comfort and sizing | Must feel like a ring | Cut-to-length board; 3D-printed sizers first |
| Battery life | Touch sensing and BLE are always on | A trigger-only design is efficient. Days to weeks per charge is plausible; **measure, don't assume**. |

## 8. Plan

| Step | What | Exit |
|---|---|---|
| R0 | Desk prototype: any BLE dev board (ESP32-C3 or nRF52840 dongle) as a HID remote sending "voice assistant" | OxygenOS opens the assistant from a BLE trigger |
| R1 | Build Open Ring unchanged; add HID voice-assist firmware (Mode A) | Tap on finger opens Talaria |
| R2 | Companion integration (Mode B): pairing, gestures, haptics, battery, audio routing to earbuds | A week of daily use; no missed taps |
| R3 | Multi-device: phone-as-hub relay with bridge arbitration | Tap goes to the laptop while working on it, to the phone otherwise |
| R4 | Own hardware revision: shell, battery, optional SoC change | Waterproof, comfortable, multi-day battery |
| R5 | Multi-host bonds and/or encrypted broadcast mode | Works without the phone nearby |

Depends on: roadmap **M2c** (default assistant) for Mode A; **M6/M11** (background, relayed devices) for Mode B.
