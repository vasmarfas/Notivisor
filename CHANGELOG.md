# Changelog

## 1.5.1

### Added

- **Open source licenses in the About card.** Every third-party component inside the app with its
  copyright holder, license and source link, and the full license texts.
- A RuStore build without `WRITE_SECURE_SETTINGS`, which RuStore does not accept. In that build the
  headset's adb has to be switched back on from a computer after a reboot.

### Changed

- A browser that connects while the headset is already casting gets the picture at once, from the
  latest keyframe, instead of waiting for the next one.
- The headset's web server keeps answering under load: extra connections are turned away at once
  instead of piling up, and malformed requests get a proper error.
- When the Quest casting service refuses a connection without adb, as recent Horizon OS builds do on
  some headsets, the error says so and points to the "Quest + adb" mode.

## 1.5.0

### Added

- **The headset can be watched in any browser on the same Wi-Fi.** Turn on The headset in a browser
  on the headset's Stream tab and it shows an address and a QR code for the page; the watching
  device needs nothing installed. Picture and sound, up to four viewers at once, about 0.1 s behind
  live in Chrome. The page can save a frame, record the picture to an MP4 file, go picture in
  picture and show stream statistics. A password is optional and asked by the browser itself; the
  stream is plain HTTP and is not encrypted.
- **The headset can be managed from that page.** Apps: the installed list with icons, launch, stop,
  uninstall, download the APK, install by dropping APK files. Files: browse the headset's storage,
  upload, download, rename, delete. A console for adb shell commands. Device: battery and storage,
  volume, Home and Back, the proximity sensor, a screenshot, open a link, type text, reboot, the
  app's log. Casting settings can be changed from the page too. Management is a separate switch,
  off by default, and works only with the password; everything except launching apps and the
  casting settings needs the headset's own adb.
- **The page can start with the app.** With Turn on together with the app the address is always
  there, and capture still starts only when someone opens it.
- **Quest casting no longer needs adb.** The default Quest mode starts Horizon OS's casting service
  directly, so `adb tcpip 5555` after every reboot is gone. The old way stays as Quest + adb for
  the case a firmware update closes the direct route, and its sound now comes from the casting
  service as well.
- **The headset's own adb survives a reboot.** It still takes `adb tcpip 5555` from a PC and one
  "Always allow" in the headset, once. After that the app switches wireless debugging back on by
  itself when Quest + adb, scrcpy, the proximity button or browser management needs it. The Stream
  tab shows the state of adb and the steps that are left.
- **Screen capture mode for Pico.** Android's own screen capture with the sound of running apps,
  no adb. It asks for consent on every start.
- **Casting parameters on the headset:** resolution, 30 or 60 fps, bitrate, which eye, sound and
  stabilization. They apply while casting; the picture is back in about two seconds.
- A link to the project chat in Telegram, in the About card and in the README.

### Changed

- The headset only captures while someone watches and stops 15 s after the last viewer leaves.
- A capture that fails or goes quiet no longer ends the browser view. The headset tries again and
  the page says what is wrong, for example that adb is waiting for consent in the headset.
- A change of picture size no longer ends casting to the phone; the headset reconnects it.

### Fixed

- **scrcpy sound on Horizon OS v209.** The firmware no longer has the capture device scrcpy uses by
  default, so the headset falls back to playback capture.
- Buttons placed side by side no longer break their labels into two lines on narrow screens.

## 1.4.0

### Added

- **Photos and files go both ways between the phone and the headset.** Use Send files on the main
  tab, or pick Send via Notivisor in any app's share menu, on either device. Files of any type
  travel over Wi-Fi, encrypted with the pairing key, up to 2 GiB each, and land in Downloads on the
  other device. The sender shows progress, and the receiver posts a notification that opens the
  file. An interrupted transfer leaves nothing behind. Both devices need this version, the link has
  to be on Wi-Fi, and the receiving device has to run Android 10 or later.
- **Text and links can be shared from either device.** A link opens in the headset's browser, or
  arrives on the phone as a notification. Other text goes to the clipboard of the other device, so
  sharing plain text no longer ends in a "no link" message.

### Fixed

- **The phone showed its mobile data address instead of the Wi-Fi one.** On a Galaxy S25 Ultra the
  Link tab read 100.65.131.73, the carrier's address, while the phone sat on Wi-Fi at
  192.168.20.104. The address was taken from whichever network interface came first, and Samsung
  keeps mobile data up next to Wi-Fi, so the result depended on the order the system listed them
  in. The same address went into the pairing QR code, so a headset that scanned it tried to reach
  the phone through the carrier. The address now comes from the Wi-Fi network itself, and a VPN
  running over Wi-Fi is skipped as well.
- **The cast, mirror and QR scanner screens ran under the system bars on Android 15 and later.** Their
  buttons could sit behind the navigation bar. All of them now keep clear of the bars, the display
  cutout and the keyboard, and the type-on-phone screen does the same when the keyboard is hidden.
- **Pictures and icons are decoded at a limited size on the headset.** Anything over 640 px on its
  longest side is downsampled while decoding instead of being loaded in full.
- **The camera stayed on after leaving the QR scanner.** It kept running until the app went to the
  background. Closing the scanner now releases it.

## 1.3.1

### Fixed

- **Wi-Fi stopped working on Android 17.** An app built for Android 17 gets no access to the local
  network until the user allows it, and nothing reports the refusal: the headset's connection times
  out, the phone's listening port never sees it, and the phone cannot be found on the network. The
  link, both screen streams and pairing for direct capture all depend on that access. The permission
  is now asked for together with Bluetooth, as the Nearby devices item of the phone's checklist and
  at start on the headset. It is in the same group as Bluetooth, so a phone that already allowed
  Bluetooth gets it without a dialog. If it is missing, the Wi-Fi link now says so instead of
  retrying into timeouts.

## 1.3.0

Both screens are laid out again, around four tabs instead of one long scroll, and notifications
finally appear on Pico.

### Added

- **The headset finds the phone by itself.** The phone announces its address on the network, and
  the headset picks it up when the stored one stops answering, so a phone that changed its IP does
  not have to be typed in again. A Find the phone button does the same on demand.
- The proximity sensor can be switched from the headset as well, not only from the phone.
- **The two sides compare their versions.** Each end sends its app version when the link comes up,
  and a screen that sees a different one says so. Nothing is blocked — but a phone updated ahead of
  the headset no longer looks like a broken connection with no explanation.

### Changed

- **Both screens are split into four tabs** — Home, Link, Streaming, Notifications — instead of one
  scroll with everything on it. The app list lives under Notifications, where it no longer buries
  what used to sit after it.
- **Port numbers are off the screens.** A tester compared 47830 on one screen with 47820 on the
  other and read it as a mismatch; one is the screen stream, the other the link. The address is
  shown without a port, and the port field sits behind Enter manually on the headset.
- **A scanned pairing code now says what it applied**, in a dialog naming the transport and the
  address. Scanning takes a fraction of a second, and the old line of text below the fold made it
  look like nothing had happened.

### Fixed

- **Notifications never appeared on Pico.** A Pico owner reported everything arriving and nothing
  showing, the same as with any other sideloaded app. The headset's own notification centre keeps a
  notification only if it carries a `notification_type` extra; anything without one is not merely
  hidden but cancelled a few milliseconds after it is posted, which is why the app's own diagnosis
  read "the system took it and then dropped it". Notifications now carry that marking, and on a
  PICO OS 5.13.3 the same build goes from dropped to a card drawn in the room, listed in the
  headset's own notification centre, with the shell's badge counting up. It appears over a running
  immersive app as well, without interrupting it. Nothing changes on Quest, which ignores the extra.

### Known limitations

- **Pico draws the card but does not let you act on it.** Reply fields and action buttons are not
  reachable from a notification there, which the Pico owner who tested this reports is the same for
  every notification on the device, not just ours.
- **Pico shows no notifications at all in passthrough.** The shell refuses them while see-through is
  on, for every app.

## 1.2.1

Streaming now goes both ways: the phone could already be watched from the headset, and the headset
can now be watched from the phone.

### Added

- **Watch the headset on the phone.** A new Headset view card on the phone opens a window that
  shows what the person in the headset is actually seeing — the immersive view, not the flat panel.
  The headset side has a matching Cast the headset button, and either end starts both.
- The picture comes from Horizon OS's own casting service, the same one Meta Quest Developer Hub
  drives over adb. Nothing is installed on the headset for it and no screen-share dialog appears;
  it does need wireless debugging paired on the headset once, the same as controlling the phone
  from the headset does.
- **The sound comes with the picture**, and the window has a button to mute it. Audio is captured
  separately from the video and the same way on both capture paths, and it travels as 48 kHz
  stereo, so there is nothing to decode on the phone. A headset that will not give up its audio is
  cast silently rather than not at all.

- **Two ways to capture the headset, chosen on the phone.** Quest casting keeps working with the
  headset off your head, but it is Quest only — Pico has no such service. scrcpy works on any
  headset and is the lighter path, at the cost of needing the proximity sensor to think the headset
  is worn. Whichever you pick is the one you get: if it cannot start, the phone says why rather than
  quietly using the other one.
- **Switch the headset's proximity sensor off from the phone**, and back on again, so a headset you
  have taken off keeps running. The card shows which state the headset is actually in, read back
  from it rather than assumed.

### Changed

- Bundled scrcpy server updated to 4.1 (VP8/VP9 encoding for devices without H.264, colorspace and
  gamepad fixes). Its Quest workaround is unchanged from 4.0, so this does not by itself alter
  anything on the headset.

- **The headset now says why you cannot see a notification.** A Pico owner reported everything
  arriving and nothing appearing. The log only ever proved the app had called the system, never that
  the system kept the notification, so the headset screen now carries a Notifications card that
  reports which of the four things actually happened: the headset refuses them, the channel is off,
  the system took it and dropped it, or it was accepted normally. Where a setting is at fault, a
  button goes straight to it — Pico hides the stock Android settings, but the screens are still
  reachable.
- **Two ways for the app to put a notification on screen itself**, where the system will not. A
  panel it draws over everything, or a plain system message. The message needs no permission at all
  and travels the same window type a headset uses for its own messages, so it is the one to try
  first on a headset that shows nothing. Off by default; measured on a Quest 3, which lifts both
  into its shell as floating panels.

### Fixed

- **Casting produced no picture at all.** Three separate faults, each enough on its own: the
  headset's casting service caches a failed graphics init for the life of its process, so it has to
  be stopped outright before every session; the headset opens two connections and only answers on
  the first, while only the second carries its greeting; and scrcpy sees the headset as a
  7392×4774 display, which no encoder on it will take.
- **A viewer stopped listening after the first stream ended.** Restarting a stream left the window
  stuck on "Stopped" with nothing able to reconnect to it, and reopening it from the notification
  only raised the same dead window. A viewer now keeps its port open until you close it.

### Known limitations

- **The headset needs wireless debugging paired once, from a computer.** Horizon OS has no
  on-device pairing screen, so `adb tcpip 5555` is the only way in, and it does not survive a
  reboot of the headset. The first connection also raises an authorisation dialog inside VR that
  has to be accepted with the headset on.
- **The phone cannot control the headset through this window.** Injecting a flat touch reaches the
  2D panel, which is not what the immersive view is drawn on, and how the casting protocol carries
  input back has not been worked out from public sources. It is a view, not a remote.
- **What travels is the media and game mix.** Sounds the headset plays for itself on the alarm path
  — the locator tone among them — stay on the headset and are not part of the capture.
- **Picture and sound are not locked together.** They are two streams with no common clock, so the
  two can drift apart; the audio queue is kept short so the drift stays within a few hundred
  milliseconds rather than growing.
- Like mirroring the other way, this needs the Wi-Fi transport and the phone's address, and it is a
  session you start rather than an always-on mode.

## 1.1.0

Copying and pasting on the headset no longer needs any setup, mirroring starts with a single
press, and the app finally links to its own source, issue tracker and support page.

### Added

- **Copy on the headset works with nothing to set up.** The copy button on a mirrored login code,
  and any text the phone sends, land on the headset's clipboard through a moment of window focus —
  no wireless debugging, no shell session, no pairing of any kind.
- **Text from the phone arrives as a card you can read.** When there is no shell session to paste
  with, the headset shows the text in a notification: tap to copy, then long-press the field and
  paste. A short login code is usually faster read off the card than pasted.
- **Type and send text**, a new button on the phone's Headset card. Previously the phone could only
  be asked for text by focusing a field with this app's keyboard, which a Quest cannot do — Horizon
  OS has no keyboard settings screen at all.
- **About section** on both devices: source code, bug reports, support and the privacy policy, with
  the installed version.
- **Share the connection log** from the Statistics card. The log is redacted at source — notification
  titles and bodies never reach a log line — so it is safe to attach to a bug report as-is.

### Fixed

- **The copy button on the headset did nothing.** Android only lets the focused app write the
  clipboard, and a broadcast receiver is never focused, so the write was discarded silently.
- **Mirroring from the headset's card needed two presses and some luck.** It connected without
  asking the phone to start capturing, and the first attempts raced the phone bringing its capture
  up. Both entry points now request the capture, and the receiver retries while it starts.
- **Mirroring failed silently on Android 10 and older**, where wireless debugging does not exist:
  the app assumed the adb port was reachable instead of probing it, and took the scrcpy path into a
  dead end rather than falling back to the screen-share dialog.
- **A dropped Bluetooth connection could stall for good** when the system refused the connection
  attempt outright — that refusal is now retried like any other failure.
- The headset's notification id map grew without bound when a dismissal was lost in transit.
- The headset card on the phone reported "On charge" before the headset had reported anything.
- **Three storage/telephony permissions the app never uses were being requested anyway.**
  `libadb-android` ships without a `targetSdkVersion` in its own manifest, and Android's manifest
  merger implies `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE` and `READ_PHONE_STATE` for any
  library manifest that looks that old. Suppressed with `tools:node="remove"` — nothing in the app
  reads phone state or touches shared storage through Android's own APIs.

### Changed

- The app list is back to being the last section on the phone's screen, with Quit above it.
- Source comments removed in favour of `CODEMAP.md`, which is where this project keeps the
  reasoning, the invariants and the platform traps.

### Known limitations

- **Automatic paste into a focused field is not possible on Quest.** Horizon OS exposes neither a
  keyboard settings screen (so this app's keyboard can never be enabled) nor developer options (so
  there is no shell session to paste through). The copy flow above is the answer; if you have paired
  wireless debugging by other means, automatic paste switches itself on.
- **Pico does not give ordinary apps camera access**, so the pairing code is typed there instead of
  scanned. Everything else works.
- Screen mirroring needs the Wi-Fi transport and the phone's address; it is deliberately a session
  you start, not an always-on mode.

### Install

One APK on both devices — it works out which one it is running on. The headset side is a normal
sideload (SideQuest or `adb install`). Phones need Android 8.0 or newer, notification access and
unrestricted battery use; the app walks you through both.

## 1.0.1

First public release: notification mirroring to the headset with replies, action buttons, calls,
one-time codes, conversation grouping, app icons and rich content, clipboard and link handoff,
screen mirroring with input, phone-as-keyboard, auto Do Not Disturb, headset battery and locator, a
quick settings tile and a broadcast API for automation tools. Direct Bluetooth LE or Wi-Fi link,
AES-256-GCM encrypted, no server involved.
