# Remote Desktop — Android client

Native Android client for [CloudCompile/remote-desktop](https://github.com/CloudCompile/remote-desktop)
(the browser-based remote desktop: one Python process serving noVNC, audio,
clipboard and file transfer on a single port).

## What it does

- **Saved connections** — every server you log into is remembered automatically
  (name = hostname); connect again with one tap.
- **Quick connect** — type any server URL (`http://192.168.x.x:8090`, a
  Tailscale or Cloudflare HTTPS URL) and go. Plain-HTTP LAN hosts are allowed
  on purpose.
- **Fullscreen sessions** — immersive (status/nav bars hidden), screen stays
  awake, back button asks before leaving so a session never dies by accident.
- **Keyboard toggle** — floating button to summon/dismiss the soft keyboard
  even when the desktop toolbar is hidden.
- **File upload** — the server's Files panel uses the native file picker.
- **Everything else is the server's mobile-first UI** — trackpad touch layer,
  app menu, clipboard history, audio, auto-reconnect — always the version your
  server ships, no app update needed for UI changes.

## Build

```sh
./gradlew :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

- Application ID: `com.cjhauser.remotedesktop` (v1.0.0, versionCode 1)
- minSdk 26 / targetSdk 36
- Release signing: `remote-desktop-release.keystore` (committed, alias
  `remotedesktop-upload`; password in `remote-desktop-keystore-password.txt`)
