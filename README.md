# SMSBridge

> **Author:** [BIBEK](https://github.com/bibekg1)  
> **License:** MIT  
> **Version:** 1.0.8

A comprehensive Android two-app system designed to bridge SMS messages and notifications from your **Personal Profile (User 0)** to your **Work Profile / Island (User 11)**. 

Engineered specifically to solve the strict profile isolation in Android that prevents Work Profile apps—including Indian UPI and banking apps (**PhonePe, Tata Neu, Freecharge, BHIM, Paytm**)—from receiving incoming SMS broadcasts, performing SIM binding, or auto-verifying OTPs.

---

## The Problem Solved

### 1. The Work Profile Telephony Barrier
In Android AOSP (`InboundSmsHandler`), when a physical SMS arrives at the cellular modem:
- The system dispatches `android.provider.Telephony.SMS_RECEIVED` **strictly to User 0 (Personal Profile)**.
- It **never** dispatches the broadcast to secondary users or managed work profiles.
- Google Play Services inside the work profile (`com.google.android.gms`) waits for this broadcast to power the Google SMS Retriever API. Because the OS blocks the broadcast to secondary profiles, Google Play Services times out, and the work profile app fails verification.

### 2. Device SIM Binding (Zero-Input Verification)
Indian UPI and banking apps do not provide a manual OTP input box during device registration. In compliance with NPCI regulations:
1. The app sends an outbound SMS (`UPIACT ...` or `PHONEPE-SMS-VERIFY ...`) from your physical SIM.
2. The banking server replies with a confirmation/OTP SMS containing an app-specific verification hash (e.g. `<#> ... gZBmDyq76e3`).
3. The app relies on **operating system auto-verification** to confirm the SIM card resides in the active device.
4. Without the system SMS broadcast reaching the work profile, automated SIM binding cannot complete.

### 3. SMS Loop Prevention
Writing incoming messages into `TelephonyProvider` (`content://sms`) modifies Android's shared telephony database, triggering Personal Profile SMS apps (Google Messages, Truecaller) to post notifications, which can create infinite relay loops. SMSBridge eliminates this via root broadcast dispatch and intelligent notification filtering.

---

## Dual-Channel Architecture

SMSBridge uses a **dual-channel architecture** to ensure both automated system-level SIM binding and interactive user convenience:

```
PERSONAL PROFILE (User 0)                         WORK PROFILE (User 11)
┌─────────────────────────────────┐               ┌─────────────────────────────────┐
│       SMS Bridge (Main)         │               │       SMS Bridge (Work)         │
│                                 │               │                                 │
│  ┌───────────────────────────┐  │               │  ┌───────────────────────────┐  │
│  │ SmsReceiver               │  │  Root Direct  │  │ Google Play Services      │  │
│  │ (Raw 3GPP PDU capture)    │──┼───────────────┼─▶│ (SMS Retriever API)       │  │
│  └─────────────┬─────────────┘  │   Broadcast   │  │                           │  │
│                │                │ (UID 0 / IPC) │  │  → PhonePe, Tata Neu,     │  │
│  ┌─────────────▼─────────────┐  │               │  │    Freecharge SIM Binding │  │
│  │ RootSmsBroadcaster        │  │               │  └───────────────────────────┘  │
│  │ (app_process root helper) │  │               │                                 │
│  └───────────────────────────┘  │               │  ┌───────────────────────────┐  │
│                                 │  TCP Socket   │  │ ReceiverService           │  │
│  ┌───────────────────────────┐  │ (127.0.0.1:   │  │ (Port 65432 Listener)     │  │
│  │ BridgeService             │──┼────65432)────▶│  └─────────────┬─────────────┘  │
│  │ (15s Deduplication Cache) │  │               │                │                │
│  └─────────────▲─────────────┘  │               │  ┌─────────────▼─────────────┐  │
│                │                │               │  │ OtpAutoFillService        │  │
│  ┌─────────────┴─────────────┐  │               │  │ (Accessibility Auto-Fill) │  │
│  │ BridgeNotifListener       │  │               │  ├───────────────────────────┤  │
│  │ (Ignores SMS apps,        │  │               │  │ NotificationHelper        │  │
│  │  whitelist filtered)      │  │               │  │ (1-Tap Copy Notification) │  │
│  └───────────────────────────┘  │               │  └───────────────────────────┘  │
└─────────────────────────────────┘               └─────────────────────────────────┘
```

1. **System Channel (Root PDU Broadcast)**:
   - Intercepts incoming SMS via `SmsReceiver` on User 0 and extracts the raw binary GSM/3GPP PDUs.
   - Executes `RootSmsBroadcaster` via `su` (`app_process`) with UID 0.
   - Dispatches `android.provider.Telephony.SMS_RECEIVED` directly to User 11 via `ActivityManager` binder IPC.
   - Triggers Google Play Services SMS Retriever and native SMS receivers in the work profile for 100% automated SIM binding.

2. **User Experience Channel (Local Loopback TCP)**:
   - Forwards parsed sender, body, and timestamp over `127.0.0.1:65432`.
   - `ReceiverService` in User 11 extracts the OTP using regex pattern matching.
   - Injects the OTP via `OtpAutoFillService` (Accessibility Service) when an input field is present.
   - Automatically copies the OTP to the clipboard.
   - Posts a high-priority heads-up notification with a **"Copy OTP"** action button.

---

## Key Features

- ⚡ **Root-Powered SMS Dispatch** — Injects full 3GPP PDU binary broadcasts directly into User 11 without touching system files.
- 🏦 **Automated SIM Binding & UPI Compatibility** — Supports PhonePe, Tata Neu, Freecharge, BHIM, Google Pay, and other banking applications.
- 🔁 **Infinite Loop Protection** — Built-in blacklist for SMS apps (`Google Messages`, `Truecaller`, `com.android.mms`, `cn.nubia.mms`) and a 15-second deduplication cache in `BridgeService`.
- 📋 **1-Tap OTP Copy & Notification Action** — Extracts 4–8 digit numeric and alphanumeric verification codes.
- ✍️ **Instant OTP Auto-Fill** — Optional accessibility service (`OtpAutoFillService`) to automatically type detected OTPs into active input fields.
- 🎯 **App Whitelist Picker** — Selectively forward notifications from specific apps using a searchable multi-select dialog.
- 🔋 **Battery Optimization Management** — One-tap whitelist launchers and real-time status indicators in both apps to prevent OS background killing.
- 🔒 **100% Offline & Secure** — Runs entirely on-device via local loopback (`127.0.0.1`). Zero internet permissions required for data transport.

---

## Installation & Setup

### Requirements
- Android 9.0 to 14.0
- Work Profile active (via **Island**, **Shelter**, or MDM)
- Root access (Magisk / KernelSU / APatch) on the device for automated SIM binding.

---

### Step 1: Download & Install APKs

Download the latest releases from the [Releases](https://github.com/BibekG1/SMSBridge/releases) page:

1. **Install Main App on Personal Profile (User 0)**:
   ```bash
   adb install -r SMSBridge-Main-v1.0.8.apk
   ```

2. **Install Work App into Work Profile (User 11)**:
   ```bash
   # Find your work profile user ID (usually 10, 11, or 12)
   adb shell pm list users

   # Install into the work profile (e.g., user 11)
   adb install -r --user 11 SMSBridge-Work-v1.0.8.apk
   ```
OR JUST INSTALL MANUALLY BY YOURSELF IN YOUR DEVICE.
---

### Step 2: Configure Permissions

#### 1. SMSBridge (Main)
1. Open **SMS Bridge (Main)** on your Personal Profile.
2. Tap **"Enable Notification Access"** → Allow SMS Bridge (Main).
3. Tap **"Verify & Grant Root Access"** → Grant Superuser when prompted by Magisk/KernelSU.
4. Tap **"Whitelist in Battery Settings"** → Set battery usage to **Unrestricted**.
5. Toggle **"Enable Bridge"** on.

#### 2. SMSBridge (Work)
1. Open **SMS Bridge (Work)** inside your Work Profile.
2. Toggle **"Enable Receiver"** on.
3. Tap **"Whitelist in Battery Settings"** → Set to **Unrestricted**.
4. *(Optional)* Tap **"Enable Auto-Fill"** to enable the accessibility service for automatic field entry.

---

## Essential ADB Commands for Work Profile Apps

Because Android restricts inter-app communication and profile visibility by default, run the following commands via ADB or a root shell to ensure work profile apps can see each other, access browsers, and handle payment links:

### 1. Package Visibility (`QUERY_ALL_PACKAGES`)
Allows work profile apps (PhonePe, Tata Neu, Freecharge, etc.) to detect installed browsers and UPI apps:
```bash
adb shell su -c "appops set --user 11 com.phonepe.app QUERY_ALL_PACKAGES allow"
adb shell su -c "appops set --user 11 com.tatadigital.tcp QUERY_ALL_PACKAGES allow"
adb shell su -c "appops set --user 11 com.freecharge.android QUERY_ALL_PACKAGES allow"
adb shell su -c "appops set --user 11 com.smsbridge.work QUERY_ALL_PACKAGES allow"
```

### 2. Cross-Profile Interaction (`INTERACT_ACROSS_PROFILES`)
Allows apps to initiate and receive cross-profile intents (such as opening payment URLs in personal profile browsers):
```bash
adb shell su -c "
pm grant --user 11 com.phonepe.app android.permission.INTERACT_ACROSS_PROFILES
appops set --user 11 com.phonepe.app INTERACT_ACROSS_PROFILES allow
pm grant --user 0 com.phonepe.app android.permission.INTERACT_ACROSS_PROFILES
appops set --user 0 com.phonepe.app INTERACT_ACROSS_PROFILES allow
"
```

### 3. Island Cross-Profile Intent Filters
To make web links (`http`, `https`) or payment links (`upi://pay`) clicked inside the Work Profile open in your Personal Profile apps:
1. Open the **Island** app.
2. Go to **Settings** → **Island** → **Cross-profile intent filters**.
3. Enable **Web URLs** and **UPI / Custom URIs**.

---

## Project Structure

```
SMSBridge/
├── app-main/                             # Main Profile Application (User 0)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/smsbridge/main/
│   │   │   ├── BridgeMessage.kt          # Unified data model
│   │   │   ├── BridgeNotificationListener.kt # Notification listener with loop protection
│   │   │   ├── BridgeService.kt          # Socket client with 15s deduplication
│   │   │   ├── FilterManager.kt          # Whitelist storage & package filtering
│   │   │   ├── MainActivity.kt           # UI, root status, battery whitelist
│   │   │   ├── MessageStore.kt           # In-memory circular message log
│   │   │   ├── RootHelper.kt             # Superuser execution & verification helper
│   │   │   ├── RootSmsBroadcaster.kt     # app_process root binary PDU broadcaster
│   │   │   └── SmsReceiver.kt            # OS SMS listener & root forwarder
│   │   └── res/layout/activity_main.xml
│   └── build.gradle.kts
│
├── app-work/                             # Work Profile Application (User 11)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/smsbridge/work/
│   │   │   ├── CopyReceiver.kt           # Handles 1-tap notification copy actions
│   │   │   ├── MainActivity.kt           # UI, receiver controls, accessibility toggle
│   │   │   ├── MessageStore.kt           # Circular log of received payloads
│   │   │   ├── NotificationHelper.kt     # High-priority heads-up notification builder
│   │   │   ├── OtpAutoFillService.kt     # Accessibility service for input field injection
│   │   │   ├── OtpDetector.kt            # Regex OTP extraction engine
│   │   │   ├── ReceivedMessage.kt        # Data model with parsed OTP field
│   │   │   └── ReceiverService.kt        # TCP ServerSocket daemon on port 65432
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       └── xml/accessibility_service_config.xml
│   └── build.gradle.kts
│
└── .github/workflows/release.yml         # CI/CD Automated GitHub Release workflow
```

---

## Troubleshooting

| Issue | Cause | Solution |
|---|---|---|
| **Root Forwarding: Not Detected** | Magisk did not prompt or root was denied | Open Magisk / KernelSU app, go to Superuser section, and enable permissions for **SMS Bridge (Main)**. |
| **SIM Binding Times Out** | Work profile GMS did not receive broadcast | Verify the work profile user ID matches (default is User 11). Check via `adb shell pm list users`. |
| **Notification Loop** | SMS app notification being forwarded | Ensure SMS apps (Google Messages, Truecaller) are not manually whitelisted in App Filter. In v1.0.8, SMS apps are automatically ignored. |
| **Service Killed After Sometime** | OS battery saver stopping background service | In phone settings, set Battery Optimization to **Unrestricted** for both apps. |
| **Receiver: INACTIVE after reboot** | Android blocks background auto-launch on reboot | Open **SMS Bridge (Work)** once after restarting your phone to initialize the socket listener. |

---

## Security & Privacy

- **No Remote Servers**: Zero external network traffic. Communication runs entirely over localhost loopback (`127.0.0.1`).
- **No Permanent Database**: Messages are held in an in-memory ring buffer (last 10 items) and cleared when the service stops.
- **Root Safety**: The root broadcaster does not modify `/system` or vendor partitions. It executes an isolated Java binary (`app_process`) in runtime memory to trigger the standard Android telephony intent.

---

## Disclaimer

> **Notice:** SMSBridge is created for personal productivity, testing, and interoperability research in managed multi-profile Android environments on user-owned hardware.
>
> - **Inbox Integrity:** SMSBridge operates as a non-intrusive broadcast listener. It does not alter, consume, delete, or modify carrier messaging, your default SMS app, or the system telephony inbox database in your primary profile.
> - **Data Privacy:** All message routing occurs strictly on-device via local loopback (`127.0.0.1`). No messages, OTPs, or personal data are ever transmitted to external servers, cloud services, or third parties.
> - **User Responsibility:** Users are solely responsible for complying with the terms of service and security policies of their mobile carriers, financial institutions, and third-party applications installed on their devices.

---

Made with ❤️ by [BIBEK](https://github.com/bibekg1)
