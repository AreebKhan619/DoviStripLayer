# DV Strip Player

Android TV middleware app that poses as a video player, removes the **Dolby Vision** layer
from whatever is handed to it, and forwards the cleaned media to the real player of your
choice. Built for TVs that do not support Dolby Vision (where DV content plays with broken
purple/green colors).

## What it does

| Incoming content | Result |
|---|---|
| No Dolby Vision | Forwarded untouched, instantly |
| DV **Profile 7 / 8** (hybrid DV + HDR10 base — most MKV remuxes and WEB-DLs) | DV layer removed **losslessly** (no re-encode) → clean **HDR10** |
| DV **Profile 5** (DV-only, no compatible base layer) | Warning dialog — removal without a many-hour re-encode is mathematically impossible; you choose *Play anyway* or *Cancel* |

Sources supported: local files (`file://`, `content://`), direct HTTP(S) file URLs
(including `127.0.0.1` / LAN URLs from torrent or debrid apps), and HLS/DASH streams.

## How it works

1. The app registers `ACTION_VIEW` intent filters for video, so any app's "open with" /
   default-player flow can hand it media.
2. **Probe** (~1–3 s, bundled ffprobe): detects DV and its profile — via the container's DOVI
   configuration record, with a frame-decode fallback that spots in-band RPU NAL units.
3. **Strip**:
   - *Local file with enough free space* → lossless remux into the app cache with a progress
     bar (`ffmpeg -c copy -bsf:v dovi_rpu=strip=1`), then the clean file is handed to your
     player. Perfect seeking. Repeated plays of the same file reuse the cached result.
   - *Direct MP4/MKV URLs (and local files without spare storage)* → **pass-through patch
     proxy** (`127.0.0.1:46836`): the file is served byte-identical with full HTTP Range
     support — native seeking, real duration, instant start, all tracks kept — while a
     handful of same-size byte patches neutralize the container's Dolby Vision signaling
     (`dvvC`/`dvh1` in MP4, BlockAdditionMapping in MKV) in flight, and (MKV) rewrite the
     in-bitstream DV RPU NAL units into filler NALs. With DV gone at both the container and
     bitstream level, players and TV pipelines treat the video as plain HEVC/HDR10.
     For MKVs whose track header lacks a `Colour` element, an HDR10 `Colour` element is
     synthesized in place (reusing the removed DV mapping's bytes, same length) so the TV
     switches to HDR mode from the first frame instead of only after a seek.
   - *Adaptive inputs (m3u8/DASH) and odd containers* → fallback: one continuous FFmpeg
     session strips DV into a rolling local HLS window. Live-window semantics: limited
     seeking, subtitles dropped.
4. Engine: FFmpeg 8 (`ffmpeg-kit` fork `com.antonkarpenko:ffmpeg-kit-https`). The strip
   command was validated against official Dolby *Sol Levante* P5/P8.1 samples: output carries
   zero DV markers and an intact HDR10 base (bt2020nc / SMPTE 2084).

## Install (sideload on Android TV)

```
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then on the TV:

1. Open **DV Strip Player** from the launcher → pick your **Target player** (e.g. VLC,
   MX Player, Just Player, mpv).
2. When opening a video from a file manager / torrent app / media server app, choose
   **DV Strip Player** and select "Always" to make it the default video player.

## Limitations (by design / by physics)

- **Profile 5 → SDR is not performed.** P5 has no backwards-compatible base layer; producing
  SDR requires a full tonemapping re-encode that runs far slower than realtime on TV
  hardware. The app warns instead and lets you decide.
- **Proxy mode has limited seeking** — the cleaned stream is generated on the fly.
- **DRM apps (Netflix, Prime, Disney+…) cannot be intercepted** by this or any middleware;
  they decode internally and never hand streams to external players.
- HLS/DASH input plays the FFmpeg-selected variant (no adaptive bitrate switching).

## Build from source

Requirements: JDK 17, Android SDK (platform 34).

```
gradle :app:assembleDebug        # or use Android Studio
```

Unit tests (`gradle :app:testDebugUnitTest`) cover the ffprobe JSON parsing (against real
captured fixtures, including official Dolby P5/P8.1 sample probes), the decision engine, and
the FFmpeg command builders.

## Project docs

- **Full project context (read first when returning to this project):** `docs/PROJECT-CONTEXT.md`
  — the user environment, verified DV facts, the approach history (what failed and why),
  verification method, and the future-work list.
- Design spec: `docs/superpowers/specs/2026-09-13-dv-strip-player-design.md`
- Implementation plan: `docs/superpowers/plans/2026-09-13-dv-strip-player.md`
