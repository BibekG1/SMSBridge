# SMSBridge

A two-app Android system that bridges SMS messages and notifications from your **main profile** to your **work profile**, enabling OTP auto-reading in work profile apps.

## Architecture

```
MAIN PROFILE                          WORK PROFILE
┌─────────────────────────┐          ┌──────────────────────────┐
│   SMS Bridge (Main)     │          │   SMS Bridge (Work)      │
│                         │          │                          │
│  ┌─────────────────┐    │          │  ┌────────────────────┐  │
│  │ SmsReceiver     │    │          │  │ ReceiverService    │  │
│  │ (BroadcastRcvr) │    │  Socket  │  │ (ServerSocket      │  │
│  └────────┬────────┘    │─────────▶│  │  port 65432)       │  │
│           │             │          │  └────────┬───────────┘  │
│  ┌────────▼────────┐    │          │           │              │
│  │Notification     │    │          │  ┌────────▼───────────┐  │
│  │ListenerService  │    │          │  │ OtpDetector        │  │
│  └────────┬────────┘    │          │  │ NotificationHelper │  │
│           │             │          │  │ Auto-clipboard     │  │
│  ┌────────▼────────┐    │          │  └────────────────────┘  │
│  │ BridgeService   │    │          │                          │
│  │ (Socket client) │    │          │  → Shows notification    │
│  └─────────────────┘    │          │  → Highlights OTP        │
└─────────────────────────┘          │  → Auto-copies to clip   │
                                     └──────────────────────────┘
```

## What It Does

SMSBridge solves a common Android Work Profile limitation: OTP/verification SMS messages arrive on the main profile, but the app that needs the OTP runs in the work profile (e.g., work email, banking app installed in work profile). Android's profile separation means the work profile can't see the main profile's SMS inbox or notifications.

SMSBridge creates a local loopback socket channel between the two profiles to relay messages in real time.

## Features

- 📨 **SMS Relay** — Intercepts incoming SMS via `BroadcastReceiver` in the main profile
- 🔔 **Notification Relay** — Listens to any app's notifications via `NotificationListenerService`
- 🔢 **OTP Auto-Detection** — Regex-based extraction of 4–8 digit / alphanumeric OTPs
- 📋 **Auto Copy to Clipboard** — Detected OTPs are auto-copied in the work profile
- 🔁 **App Filter** — Optionally restrict which apps get forwarded (whitelist)
- 💾 **Last 3 Messages in Memory** — No database, minimal footprint
- 🔒 **Local-only** — All data stays on-device via loopback (127.0.0.1:65432)

## Requirements

- Android 9+ (API 28) on both profiles
- Work Profile enabled on the device (via MDM / Android's built-in Digital Wellbeing / third-party launchers)
- Both apps must be signed with the same key for `CrossProfileApps` API (optional enhancement)

## Setup

### Step 1: Build

Open the project in Android Studio Arctic Fox or newer:

```
File → Open → C:\Users\ADMIN\Downloads\SMSBridge
```

Then build both APKs:

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

APK locations after build:
- `app-main/build/outputs/apk/debug/app-main-debug.apk`
- `app-work/build/outputs/apk/debug/app-work-debug.apk`

### Step 2: Install via ADB

```bash
# Install main app on the default (main) profile
adb install app-main/build/outputs/apk/debug/app-main-debug.apk

# Find your work profile user ID
adb shell pm list users
# Look for something like: UserInfo{10:Work profile:...}

# Install work app into the work profile (replace 10 with your profile ID)
adb install --user 10 app-work/build/outputs/apk/debug/app-work-debug.apk
```

Alternatively, copy the work APK to the device storage and open it from within the work profile file manager to sideload.

### Step 3: Configure Main Profile App

1. Open **SMS Bridge (Main)** from the main profile launcher
2. Tap **"Enable Notification Access"** → Find "SMS Bridge (Main)" in the list and enable it
3. Tap **"Set as Default SMS App"** if you want SMS interception (optional — notification relay works without this)
4. Toggle **"Enable Bridge"** to start the foreground service
5. *(Optional)* Enable **App Filter** to whitelist specific apps

### Step 4: Configure Work Profile App

1. Switch to the work profile and open **SMS Bridge (Work)**
2. Toggle **"Enable Receiver"** — the service starts listening on port 65432
3. Leave the app running (or it will be kept alive by the foreground service)

## How OTP Auto-Copy Works

1. An OTP SMS arrives → `SmsReceiver` picks it up in the main profile
2. `BridgeService` serializes it to JSON and connects to `127.0.0.1:65432`
3. `ReceiverService` in the work profile receives the JSON
4. `OtpDetector` runs regex patterns:
   - Digit OTP: `\b\d{4,8}\b`
   - Alphanumeric OTP: `\b[A-Z0-9]{6,8}\b` (only when OTP keywords present)
   - Keywords: `otp`, `code`, `verify`, `pin`, `token`, `passcode`, etc.
5. If an OTP is found:
   - It is **auto-copied** to the work profile clipboard
   - A high-priority notification appears with a **"Copy OTP: XXXXXX"** action button
   - The OTP is displayed prominently in the app's message list

## Permissions Required

### Main Profile App
| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` | Intercept incoming SMS |
| `READ_SMS` | Read SMS message content |
| `INTERNET` | Loopback socket communication |
| `FOREGROUND_SERVICE` | Keep bridge service alive |
| `POST_NOTIFICATIONS` | Show service status notification |
| `INTERACT_ACROSS_PROFILES` | CrossProfileApps API (future) |
| Notification Listener (Settings) | Read other apps' notifications |

### Work Profile App
| Permission | Purpose |
|---|---|
| `INTERNET` | Loopback socket server |
| `FOREGROUND_SERVICE` | Keep receiver service alive |
| `POST_NOTIFICATIONS` | Show forwarded message notifications |
| `INTERACT_ACROSS_PROFILES` | CrossProfileApps API (future) |

## Technical Limitations

1. **Loopback socket cross-profile**: Works because both profiles share the same Linux network namespace on Android — `127.0.0.1` is accessible from both. However, this behavior is undocumented and may change in future Android versions.

2. **No CrossProfileApps fallback yet**: The `CrossProfileApps` API requires enterprise MDM setup or the same signing key AND the device owner granting `INTERACT_ACROSS_PROFILES`. The socket approach is the reliable fallback.

3. **Background process limits**: Android 9+ aggressively kills background processes. Both services use `startForeground()` with persistent notifications to stay alive. Battery optimization must be disabled for both apps.

4. **Clipboard API restriction (Android 10+)**: Auto-clipboard copy from a background service requires the app to be in the foreground or have special privileges. The `ReceiverService` dispatches clipboard writes on the main thread immediately upon receiving data, which works while the service is in the foreground state.

5. **NotificationListenerService in work profile**: The notification listener runs in the main profile only. Work profile notifications are NOT relayed back (that would require a second listener in the work profile).

6. **SMS as Default App**: On Android 10+, reading SMS requires the app to be set as the default SMS app (via `RoleManager`). The notification relay path works without this.

## Project Structure

```
SMSBridge/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app-main/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/smsbridge/main/
│       │   ├── BridgeMessage.kt       ← Shared data model
│       │   ├── MessageStore.kt        ← In-memory ring buffer (3 msgs)
│       │   ├── FilterManager.kt       ← App whitelist + SharedPrefs
│       │   ├── SmsReceiver.kt         ← BroadcastReceiver for SMS
│       │   ├── NotificationListenerService.kt ← Reads other apps' notifs
│       │   ├── BridgeService.kt       ← Foreground service + socket client
│       │   └── MainActivity.kt        ← UI + MessageAdapter
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/item_message.xml
│           └── values/{strings,colors,themes}.xml
└── app-work/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/smsbridge/work/
        │   ├── ReceivedMessage.kt     ← Data model with OTP field
        │   ├── MessageStore.kt        ← In-memory ring buffer (3 msgs)
        │   ├── OtpDetector.kt         ← Regex OTP extraction
        │   ├── CopyReceiver.kt        ← Notification action handler
        │   ├── NotificationHelper.kt  ← Shows high-priority notifications
        │   ├── ReceiverService.kt     ← Foreground service + socket server
        │   └── MainActivity.kt        ← UI + WorkMessageAdapter
        └── res/
            ├── layout/activity_main.xml
            ├── layout/item_message.xml
            └── values/{strings,colors,themes}.xml
```

## Troubleshooting

**Messages not arriving in work profile:**
- Ensure both apps are running (check notification shade for persistent notifications)
- Disable battery optimization for both apps: Settings → Apps → SMS Bridge → Battery → Unrestricted
- Verify the work profile app shows "Receiver: ACTIVE ✓"

**OTP not auto-copied:**
- Android 10+ restricts background clipboard access. If auto-copy fails, use the "Copy OTP" button in the notification.

**Notification listener not working:**
- Go to Settings → Apps & notifications → Special app access → Notification access → Enable SMS Bridge (Main)

**"Receiver: INACTIVE" after phone restart:**
- Both services must be manually restarted after reboot (auto-start is blocked on most ROMs). Open both apps after reboot.
