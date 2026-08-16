# Notivisor

Your phone's notifications, shown inside your VR headset — including while you are in an immersive
game. One app, installed on both devices, talking directly over Bluetooth or Wi-Fi. No account, no
server, nothing leaves the two devices.

[Русская версия](README.ru.md)

**❤️ Support the project:** see [`SUPPORT.md`](SUPPORT.md). **🔒 Privacy:** [
`privacy-policy.md`](privacy-policy.md). **📜 Terms:** [`terms.md`](terms.md).

## The problem

Take the headset off to see who messaged you and you have left the game. The existing answers are
to install a messenger inside VR, or to route your notifications through somebody's cloud.

Notivisor does neither. A third-party process on Horizon OS cannot draw over an immersive app —
`SYSTEM_ALERT_WINDOW` gives a 2D panel that VR never composites, and `XR_EXTX_overlay` isn't open to
third parties. The one layer the system still draws over a running game is its own notification
shade. So the phone reads its notifications and hands each one to the headset, which re-posts it
locally on a high-importance channel. That is the whole trick, and it is why the pop-up appears
where nothing else can.

## Features

- Every notification your phone shows, appearing over whatever you are running in the headset
- **Reply without taking the headset off** — a message's reply box works from inside VR, and the
  answer is sent by the phone as if you had typed it there
- **The notification's own buttons** come with it: archive, mark read, snooze, whatever the app put
  there
- **Answer or decline a call** from the headset, on its own channel so it doesn't get lost
- **One-time codes** get a copy button, so a login code arriving mid-session isn't a reason to stop
- **Messages read as a conversation**, not five separate pop-ups, when the source app supports it
- **See your phone's screen inside the headset**, view-only, over Wi-Fi — no ADB, no cable
- **Type with your phone's keyboard instead of pointing at letters** — write on the phone,
  autocorrect and swipe included, and it arrives in the headset ready to paste into any field. On
  headsets that let you switch keyboards, focusing a field asks the phone for text by itself
- **Control the headset's media from the phone**: play, pause, skip, volume
- Dismiss in either place and it clears in both
- Per-app control: mirror everything except what you uncheck, or only what you check
- Notification photos and sender avatars come across too, over Wi-Fi
- App icons travel across once and are cached, so notifications look like themselves
- Send the clipboard across, or share a link straight into the headset's browser
- Silence the phone automatically while the headset has your notifications
- Headset battery on the phone's screen, and a sound to find a headset you have put down
- Only forward while the headset is actually worn, if you want it that way
- A documented broadcast API for automation tools like Tasker
- A separate notification channel per source app, so you can mute one app with the headset's own
  settings
- Repeats filtered out, so unread counters and download progress don't flood you
- Bluetooth LE by default, Wi-Fi if you prefer it
- AES-256-GCM encryption keyed by a pairing code your two devices agree on
- A quick settings tile to pause forwarding without opening the app
- Quit that actually stops everything and leaves the background

## Requirements

- Phone: Android 8.0 (API 26) or newer
- Headset: Meta Quest 3 or 3S; other Android-based headsets work with the caveat below
- Notification access on the phone, and unrestricted battery use
- Bluetooth on both devices, or a shared Wi-Fi network

Developed against a Galaxy Z Flip 4 (Android 16) and a Quest 3 on Horizon OS v206.

## Install

Download the APK from [Releases](../../releases) and install it on **both** devices — it works out
which one it is running on by itself. Sideloading onto a Quest needs Developer Mode and
`adb install`, or a tool like SideQuest.

Building from source:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17. Everything else comes from the Gradle wrapper.

## Setup

**On the phone**, the app opens with a checklist:

1. **Notification access** — Android won't let any app read notifications without it.
2. **Unrestricted battery use** — skip this and the system stops the app about a minute after your
   screen goes off, Samsung especially. The checklist links straight to the right screen.
3. **Bluetooth permissions.**

Then tap **Connect a headset** and a QR code appears.

**On the headset**, open the app and tap **Scan QR**. The code carries the pairing code, the
connection type and the address, so nothing has to be matched by hand. If the headset has no usable
camera, type the 16 digits instead.

Both devices restart their side automatically after a reboot.

## Troubleshooting

**"Pairing codes differ."** The devices are connected but can't understand each other. Re-scan the
QR. Reinstalling one side wipes its code.

**The headset never finds the phone.** Check both are on the same connection type. If Bluetooth
won't settle, switch both to Wi-Fi — the headset then needs the phone's address, which the QR code
also carries.

**Notifications stop overnight.** Almost always battery restrictions on the phone. Re-check the
setup card; some vendors reset this after a system update.

**Nothing scans on Pico.** Everything works except QR scanning — Pico restricts camera access to
authorised enterprise builds. Type the pairing code instead.

## Privacy

Notification text goes from your phone to your headset and nowhere else. No account, no analytics,
no network calls beyond the direct link. That link is encrypted with AES-256-GCM under a key derived
from your pairing code, so being in Bluetooth range is not enough to read anything. Details in
[`privacy-policy.md`](privacy-policy.md).

## Contributing

Issues and pull requests are welcome. Forking to prepare one is the normal workflow and isn't
restricted; see [`NOTICE.md`](NOTICE.md) for what is and isn't permitted beyond that. When reporting
a connection problem, the log at the bottom of the Statistics card is the useful part.

## License

Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE), with additional terms in
[`NOTICE.md`](NOTICE.md): redistribution only by contributing back to this repository, and the
Notivisor name, icon and package ID may not be used on any other build. Personal use and
modification are unrestricted; commercial use needs a separate agreement. See [`terms.md`](terms.md)
for the terms of using the app itself.
