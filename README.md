# WatchBridge ⌚🔔

[![Android Phone SDK](https://img.shields.io/badge/Phone-Android%208.0%2B%20(API%2026%2B)-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![Wear OS SDK](https://img.shields.io/badge/Wear%20OS-3.0%2B%20(API%2030%2B)-4285F4.svg?style=flat&logo=wearos)](https://developer.android.com/wear)
[![Java 17](https://img.shields.io/badge/Java-17-ED8B00.svg?style=flat&logo=openjdk)](https://www.oracle.com/java/)
[![Room DB](https://img.shields.io/badge/Storage-Room%20SQLite-4285F4.svg?style=flat)](https://developer.android.com/training/data-storage/room)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**WatchBridge** is a dual-module **Android Phone (`:app`) + Wear OS (`:wear`)** notification proxy, persistent repeat-alert engine, and wrist control center built in **Java 17** for **Samsung Galaxy Watch** and Wear OS smartwatches.

Designed for **Microsoft Teams**, **WhatsApp**, and custom Android apps, it solves single-buzz missed alerts when away from your desk or on standby, eliminates notification burst spam with per-chat cooling windows, preserves real notification actions, and enables two-way control directly from the watch.

---

## ✨ Key Features

### 🔔 Smart Proxy & Persistent Repeat Alerts (`:app` + `:wear`)
- **Direct Native Watch Delivery**: Forwards notifications directly to the Wear OS companion app over Play Services Wearable Data Layer (`MessageClient` / `DataClient` / `ChannelClient`) so there are **no duplicate WatchBridge notifications** cluttering the phone shade (legacy Galaxy Wearable mirroring remains optional in Settings).
- **Repeat Alert Mode with Auto-Off Timer**: Cycles fresh high-priority alerts every configurable number of seconds (`10s`, `15s`, `20s`, etc.) until dismissed, snoozed, or replied to from the watch or phone. Optional auto-off timers (`Manual`, `30m`, `45m`, `60m`, `90m`).
- **Progressive Escalation**: Gentle wrist vibration on attempts 1–2, escalating at attempt 3+ to maximum watch haptics, watch chime, full-screen wake intent, optional TTS voice readout, and optional phone alarm.
- **Chat vs. Event Fidelity**: Distinguishes true `MessagingStyle` / reply-capable chats from calendar/system event notifications (`KIND_EVENT`)—hiding reply, quick replies, mark-as-read, and voice-note controls when the original notification does not support them.
- **Source-Muted Chat Respect**: Detects notifications muted in their source app (`NotificationChannel` importance/sound/vibration via `RankingMap`) and forwards them silently without vibrating the watch or triggering repeats.

### ⚙️ Hierarchical Rules & Burst Cooling Time
- **Per-App Rules (`1-on-1` vs. `Group` Split)**: Independent enable, repeat, repeat interval (seconds), burst cooling time (seconds), phone audio mode, and watch haptic waveform settings for Direct Chats and Group Chats.
- **Per-Sender / Per-Chat Rules & VIPs**: Override any person or group with custom intervals, opt-in **Burst Cooling Time** (coalesces rapid message bursts into a single thread update without buzzing repeatedly), or **⭐ VIP** status to bypass mutes and schedules.
- **4 Quick Profiles + Auto Schedule**: `Normal Proxy`, `Repeat Mode`, `Work Focus`, and `After-Hours` (with optional automatic weekday schedule, e.g., `18:00–09:00`, plus urgent keyword overrides).
- **1x1 & 3x1 Home Screen Widgets**: Toggle Repeat Mode, cycle profiles, or stop active alerts in one tap from the phone launcher.

### ⌚ Native Galaxy Watch / Wear OS Features (`:wear`)
- **Ghost Read Chat Thread**: View rolling message history, embedded images/stickers, and dismiss individual messages or entire threads without triggering read receipts until you tap **Mark Read** or **Reply**.
- **Direct Reply & Spanish/English Quick Replies**: Dictate or pick 1-tap quick replies (`Teams` and `WhatsApp` presets, customizable in Spanish or English) injected directly into the original app's `RemoteInput`.
- **Watch Speaker Voice Note Player (`1.0x / 1.5x / 2.0x`) & TTS Readout**: Transfers received WhatsApp voice notes via `ChannelClient` (skipping `Sent/` outgoing folders) to play through the watch's built-in speaker, plus on-demand Text-to-Speech readout.
- **Wrist App Toggles & Differentiated Haptics**: Enable/disable phone apps, group chats, or cycle repeat/cooling timers straight from your wrist, with custom vibration waveforms per app.
- **Anti-Delete Vault**: Captures messages before senders delete them (`🗑️ Deleted`), with single-item and bulk delete controls.
- **Phone Remote Control**: View phone battery %, switch phone ringer (`Sound`, `Vibrate`, `Silent` with DND policy support), and trigger a full-screen **Ring My Phone / Stop Ring** alert.
- **Wear OS Tile & Watch Face Complication**: 1-tap quick controls from the carousel Tile and live active profile indicator on your watch face.
- **Physical Rotating Bezel & Full Back-Stack Navigation**: Smooth rotary bezel scrolling, swipe-right back across sub-screens, and physical Back button history stack.

---

## 🏗️ Project Architecture

```text
watch-bridge/
├── app/                         # Android Phone module (API 26+)
│   ├── data/                    # Room SQLite DB (AppRule, SenderRule, ChatMessage) & PrefsManager
│   ├── engine/                  # NotificationParser, RuleEvaluator, CooldownTracker, ActionCache
│   ├── service/                 # BridgeNotificationListenerService, PersistentRepeatForegroundService, ProfileController
│   ├── wear/                    # WearDataLayerManager, PhoneWearListenerService, WearProtocol
│   ├── widget/                  # 1x1 & 3x1 Home Screen AppWidgets
│   └── ui/                      # MainActivity (4-tab Material UI) & FindPhoneActivity
├── wear/                        # Wear OS Watch module (API 30+)
│   ├── tile/                    # WatchBridgeTileService (Native Wear OS Tile)
│   ├── complication/            # WatchBridgeComplicationService (SHORT_TEXT Watch Face Complication)
│   ├── service/                 # WatchWearListenerService (DataClient, MessageClient, ChannelClient)
│   ├── receiver/                # WatchNotifActionReceiver
│   ├── wear/                    # WatchNotifHelper, WatchAudioHelper, WatchHapticHelper, WatchDataClient
│   └── ui/                      # WearMainActivity (Rotary bezel + multi-screen back-stack)
└── test-ui-audit.js             # Automated XML Virtual DOM, ID & EN/ES i18n parity audit
```

---

## 🚀 Building & Running

### Prerequisites
- JDK 17+
- Android SDK 34 (`compileSdk 34`)
- Node.js (optional, for UI/i18n audit script)

### Run UI & i18n Parity Audit
```bash
node test-ui-audit.js
```

### Build APKs & Run Unit Tests
```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease :wear:assembleRelease
```

### Setup Notes
1. Install the same signed `:app` and `:wear` APKs on the phone and Wear OS watch (matching `applicationId` and signing key are required by Google Play Services Wearable Data Layer).
2. On the phone app (**Hub** tab), grant **Notification Access**, **Post Notifications**, **DND Access** (for remote `Silent` mode), **Unrestricted Battery**, and optionally pick `WhatsApp Voice Notes` / `WhatsApp Audio` directory for watch voice-note playback.
3. In **Galaxy Wearable → Notifications**, disable direct WhatsApp/Teams toggles if using **WatchBridge** as your primary watch proxy.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
