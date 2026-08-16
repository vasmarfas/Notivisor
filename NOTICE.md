Required Notice: Copyright 2026 vasmarfas

This file supplements [LICENSE](LICENSE). The license text is unmodified PolyForm Noncommercial
License 1.0.0 and controls whenever the two disagree; this file adds the required copyright notice,
explains the license in plain language, and adds a distribution and branding restriction that a
copyright license doesn't cover on its own.

## What the license means in practice

Not legal advice, just a summary — read [LICENSE](LICENSE) for the actual terms.

- Personal use, running it on your own devices, studying the code, and modifying your own copy for
  yourself are all free.
- Any commercial use is not licensed: selling the app or a device with it preinstalled, bundling it
  into a paid product or service, or using it as part of a business's operations all fall outside "
  noncommercial purposes" and need a separate agreement with the copyright holder.
- If you distribute the software, recipients must get a copy of the license too.

## Forking and contributing

Forking [github.com/vasmarfas/Notivisor](https://github.com/vasmarfas/Notivisor) to prepare and
submit a pull request is the normal way to contribute, and this notice doesn't restrict it. Keep a
fork around for as long as you're working toward a PR, sync it with upstream, iterate on it — that's
exactly what it's for. Keeping a personal fork you never publish is ordinary personal use and is
fine regardless.

What isn't permitted is treating a fork as an independent product rather than a contribution in
progress:

- **Rebranding** a fork — a different name, icon, or application ID meant to present it as a
  separate app — is not allowed; see [Name, icon and package ID](#name-icon-and-package-id) below.
- **Distributing** a fork to other users as an alternative or competing version — publishing it to
  an app store, advertising it, or otherwise shipping it to people who aren't contributing to it —
  is not allowed, even if you keep the Notivisor name.
- Maintaining a fork as ongoing, separately-promoted development that isn't headed toward a pull
  request against the repository above isn't what forking is for. A fork sitting on GitHub with no
  active PR is not a problem by itself; publishing or promoting it as a standalone alternative is.

## Third-party components

This app ships a compiled copy of the **scrcpy** server (`app/src/main/assets/scrcpy-server`),
which mirrors and controls the phone's screen without a MediaProjection prompt. scrcpy is
Copyright (C) Romain Vimont and the scrcpy contributors, licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); its source is at
<https://github.com/Genymobile/scrcpy>. Because that file is redistributed as a binary, this
attribution travels with every build.

The app also links, unmodified and via Gradle, against AndroidX and Jetpack Compose, ZXing,
Shizuku, dadb and Conscrypt, all under the Apache License 2.0, plus two libraries whose terms are
worth stating exactly:

- **libadb-android** — dual licensed GPL-3.0-or-later *or* Apache-2.0; used here under
  **Apache-2.0**. Its upstream notes that it carries an LGPL dependency of its own.
- **sun-security-android** — GPL-2.0 **with the Classpath Exception**, the same exception the
  OpenJDK sources it repackages carry. That exception is what permits linking it into this app
  without the GPL extending to the rest of the work.

## Name, icon and package ID

The name **Notivisor**, the app icon, and the Android application ID `com.vasmarfas.notivisor`
identify official builds of this project only, distributed by the maintainer. They are not covered
by the code license and may not be used to label, describe, or identify any other build, fork, or
distribution — even one that would otherwise be permitted — nor to imply that an unofficial build is
endorsed by or affiliated with this project.

## Contributing

Pull requests, patches, and issues submitted to the repository above are welcome and are contributed
for the purpose of developing this project. By submitting one, you agree that your contribution
becomes part of the project on the same basis as the rest of the codebase, and that the maintainer
may use it — including in builds the maintainer publishes, such as on Google Play — without the
noncommercial restriction limiting the maintainer's own use of the combined work. It remains covered
by the same license for everyone else.
