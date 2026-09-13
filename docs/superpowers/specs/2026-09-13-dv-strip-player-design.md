# DV Strip Player — Design

**Date:** 2026-09-13
**Status:** Approved by user

## Problem

The user's TV does not support Dolby Vision (DV). DV content — especially Profile 5 — renders
with broken colors. The user wants an Android TV middleware app that registers as a video
player, intercepts any video handed to it (local file, HTTP URL, HLS/DASH stream, localhost
server URL), removes the Dolby Vision layer, and forwards the cleaned media to a real player
chosen in settings.

Target behavior by content type:

| Content | Result |
|---|---|
| No DV | Forward untouched, instantly |
| DV Profile 7/8 (hybrid DV + HDR10 base) | Strip DV → clean HDR10 |
| DV Profile 5 (DV-only, IPTPQc2, no compatible base) | Warn user: play as-is (wrong colors) or cancel. No on-device re-encode (user decision — re-encode is 6–12h+ on TV hardware). |

## Key technical facts

- P7/P8: DV data = RPU NAL units (HEVC NAL type 62) + container-level DV config record
  (`dvcC`/`dvvC` box in MP4, BlockAdditionMapping in MKV). Removing both via lossless remux
  (`-c copy -bsf:v filter_units=remove_types=62`) yields plain HDR10. No re-encode.
- P5: base layer is not backwards compatible; stripping metadata alone produces purple/green
  output. True SDR requires tonemap re-encode — out of scope per user decision.
- DRM apps (Netflix, Prime, etc.) never hand streams to external players; out of scope by nature.

## Architecture

Android TV app, Kotlin, minSdk 24. Engine: FFmpeg via `ffmpeg-kit` AAR (retired upstream but
artifacts are immutable on Maven Central; `https` variant covers file/http/https/HLS/DASH input,
MKV/MP4 mux, `filter_units` bsf, ffprobe). Fallback if the shipped FFmpeg mishandles the DV
config record: bundle a newer static ffmpeg binary as a fake `.so`.

### Components

1. **InterceptActivity** — exported, `VIEW` intent filters for `video/*` across `file://`,
   `content://`, `http://`, `https://` (plus common extension patterns), so the app is
   selectable as default video player. Shows probe spinner / progress bar / P5 warning dialog.
2. **MediaProbe** — ffprobe wrapper. Detects DV presence + profile:
   - Container DV config record when the demuxer exposes it (MP4).
   - Fallback: decode the first ~10 frames and look for "Dolby Vision RPU" frame side data
     (container-independent, catches MKV P8).
   - Profile heuristic when config record absent: RPU + proper HDR10 VUI signaling
     (bt2020/smpte2084) → treat as P8 (strip); RPU + unspecified/ICtCp matrix → treat as P5 (warn).
     Second `dvhe` video track → P7.
3. **Decision engine** — no DV → forward as-is. P7/8 → strip. P5 → warning dialog.
4. **Stripper — temp-file mode** (local files when free space ≥ file size × 1.05):
   lossless remux to app cache (MKV output — carries all audio codecs incl. TrueHD), progress
   bar driven by FFmpeg statistics callbacks vs probed duration. Forward via FileProvider
   `content://` URI with read grant.
5. **Stripper — proxy mode** (all URLs; local files without space): foreground service running
   a local HTTP server (NanoHTTPD) on `127.0.0.1:<port>`. FFmpeg reads the source (including
   HLS/DASH manifests natively), strips RPUs, muxes streamable MKV to a named pipe; the server
   pipes it to the player. Instant start, zero storage. **Known limitation: seeking is
   restricted in proxy mode** (progressive stream, no random access).
6. **SettingsActivity** — D-pad friendly: target player picker (queries installed handlers of
   `VIEW video/*`, excludes self), cache auto-cleanup, "always use proxy" debug toggle.

### Data flow

```
Source app ──VIEW intent──▶ InterceptActivity
                              │ probe (1–3s)
                              ├─ no DV ──────────────▶ forward original URI to player
                              ├─ P5 ──▶ warn ──play──▶ forward original URI to player
                              └─ P7/8 ─┬─ local+space ▶ remux temp file (progress) ─▶ player
                                       └─ else ───────▶ proxy service URL ──────────▶ player
```

## Error handling

- Probe failure / unreadable source → offer "forward untouched" instead of hard-failing.
- FFmpeg session failure mid-remux → clean up partial temp file, offer proxy mode or pass-through.
- Player not installed / picker empty → prompt to choose a player in settings.
- Temp cache: auto-delete files after playback intent fired + configurable retention; always
  evict oldest when space is needed.

## Verification

- Run official Dolby sample clips (P5, P8.1) and a P7 MKV through the pipeline; assert with
  ffprobe that output has zero type-62 NAL units and no DV config record.
- Validate FFmpeg command lines on desktop ffmpeg before baking into the app.
- Manual playback test on the user's TV with their chosen player.

## Deliverables

- Full Kotlin/Gradle source in this repo.
- Debug-signed APK (fine for sideloading) built locally.

## Out of scope

- DV Profile 5 → SDR re-encode (user-declined; too slow on TV hardware).
- DRM-protected app streams (technically impossible to intercept).
- Adaptive bitrate passthrough for HLS (FFmpeg selects one variant).
- Byte-patching zero-copy proxy (future optimization).
