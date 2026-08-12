# Privacy Policy

**Effective Date:** 2026-08-10

**Notivisor** ("we," "our," or "us") is committed to protecting your privacy. This Privacy Policy
explains how we collect, use, and safeguard your information when you use our Android application (
the "App").

## 1. Information Collection and Use

### Notification content

The App reads the notifications your phone displays — the sending app, title, text, and timestamp —
using Android's Notification Listener API. This is the App's core function; without it, there is
nothing to forward.

- **Purpose:** Each notification is forwarded, in real time, to the one VR headset you have paired
  with your phone.
- **Privacy:** Notification content is transmitted directly to your paired headset over Bluetooth or
  local Wi-Fi and is never sent to us or to any server. The connection is sealed with AES-256-GCM
  under a key derived from the 16-digit pairing code shown on your phone, so a device within range
  cannot read the traffic without that code.

### App settings

Your configuration — which apps are mirrored, the connection type, and the pairing code — is stored
in the App's private, on-device preferences on both your phone and your headset. It is not
accessible to other apps and is not synced anywhere.

### Notification fingerprint (deduplication)

To avoid forwarding the same notification twice, the App keeps a short-lived fingerprint of recently
seen notifications on the phone, and a map between forwarded notifications and their local copies on
the headset, so dismissing one on your phone clears it there too. Neither ever leaves the device
it's stored on.

## 2. Third-Party Services

The App does not use Firebase, Google Analytics, Crashlytics, advertising SDKs, or any other
third-party analytics or tracking service. It makes no network requests beyond the direct connection
to your own paired device. Nothing about your usage of the App is reported to us or to anyone else.

The source code of the App is available for review on GitHub, under a noncommercial license.

## 3. Permissions

The App requests the following permissions and uses them only for the stated purpose:

- **Notification access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) — to read the notifications being
  forwarded, as described in Section 1.
- **Bluetooth** (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`) — to discover and
  connect to your paired device.
- **Camera** (headset installation only) — to scan the pairing QR code shown on your phone. No image
  is stored or transmitted; it is decoded on device and discarded immediately.
- **Post notifications** — to display forwarded notifications on the headset and to show the App's
  own connection status.
- **Foreground service, wake lock, receive boot completed** — to keep the connection alive while
  forwarding is enabled and to restart it automatically after a device reboot.

## 4. Children's Privacy

The App is not directed at children and does not knowingly collect personal information from anyone,
including children under 13.

## 5. Data Retention and Security

We do not operate any server that stores your data, and the App does not transmit personal data to
us. Everything the App keeps — settings, pairing state, and the deduplication fingerprint — stays on
your own two devices and is removed when you uninstall the App from each of them.

## 6. Your Rights

Because we do not collect or hold any personal data about you, there is nothing for us to access,
export, or delete on your behalf. Uninstalling the App removes all data it stored locally.

## 7. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Material changes will be reflected by a new
Effective Date at the top of this document and published together with the App release that
introduces them.

## 8. Contact Us

If you have any questions or concerns about this Privacy Policy, please contact us.

**Developer:** vasmarfas
**Contact:** https://github.com/vasmarfas/Notivisor/issues
