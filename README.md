# LinkSave

Download videos in high quality, without a watermark, from a pasted link —
YouTube, Instagram, TikTok and Facebook. Free, no ads, no account.

## Install

Download the latest APK from this **permanent link — the same link
forever, always the newest version, click to download immediately**:

https://github.com/iamdilawarkhan/LinkSave/releases/latest/download/LinkSave.apk

Every release publishes the APK under the same name (`LinkSave.apk`),
so that link never goes stale: it always serves the newest version.
Install it on your phone; updates install directly over the previous
version — no uninstall needed. The app also checks for updates
itself (see below) and offers a one-tap download when a new version
is out.

## How to use

1. Copy a video link in any app.
2. Open LinkSave and tap the green orb (the link is pasted automatically).
   Or share the link straight from YouTube / Instagram / TikTok / Facebook
   → **LinkSave** — no clipboard needed.
3. Choose a quality (HD / SD / MP3 audio) and the download starts to
   `Downloads/LinkSave`.

Tap the orb again with no new link copied to cancel a download. Copy a new
link and tap again to download in parallel (up to 4 at once).

## Features

- YouTube, Instagram, TikTok and Facebook — HD/SD video, MP3 audio-only
- YouTube Shorts download as video (not MP3)
- Up to 4 parallel downloads, ~10× faster with parallel chunks
- **VIDEOS** list with thumbnails; tick-select to delete (app + gallery)
- Long-press the orb to put your own photo inside it
- In-app updates: when a new version is out, a pop-up offers a
  one-tap background download + install (checks `version.json`
  in this repo at most once a day; silent when offline)
- Simple Green Hole-style interface — no extra text, no errors
- Clean install: only INTERNET, network-state and (pre-Android 10) storage
  permissions. No Accessibility service, no overlay, no background service —
  so Google Play Protect does not block it.

## Build from source

Needs Android SDK 34 and JDK 17. The build uses the SDK tools directly
(no Gradle daemon):

```sh
./build.sh   # outputs /tmp/linksave-build/apk/LinkSave.apk
```

Run the unit tests with the jar in `tests/` on the classpath
(see `tests/LinkUtilTest.java`).

> **Do not commit `debug.keystore`** — it is the signing key. Anyone with it
> could sign fake updates as LinkSave. It is excluded in `.gitignore`.
> Keep a private backup of it: losing it means the next release cannot
> update over the installed app.

## Version history

- **v1.18** — in-app updater: a pop-up when a new version is out
  (checked at most once a day from `version.json` in this repo),
  background download, one-tap install
- **v1.17** — bigger robot mascot in the launcher icon, all four brand
  badges visible on OnePlus/OxygenOS
- **v1.16** — launcher icon fixed for OxygenOS safe-zone cropping
- **v1.15** — new 3D robot mascot launcher icon with brand badges
- **v1.14** — adaptive launcher icon, no black anywhere
- **v1.13** — tick-select delete, Shorts download as video, LINK-SAVE title
- **v1.12** — Accessibility / auto-download bubble removed (Play Protect)
- **v1.10** — thumbnails in the VIDEOS list
- **v1.9** — custom photo inside the orb
- **v1.8** — one-line idle hint under the orb
- **v1.7** — tap to cancel, parallel downloads (max 4)
- **v1.5** — Share-intent entry, VIDEOS label fix
- **v1.4** — Instagram fixed (4-path resolver chain)
- **v1.3** — ~10× faster downloads
- **v1.2** — YouTube downloads via Invidious + on-device merge

## Credit

Designed by Dilawar Khan.
