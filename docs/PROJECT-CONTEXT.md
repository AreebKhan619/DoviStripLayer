# DV Strip Player — Full Project Context

*Written 2026-09-13, at the end of the initial development session. Read this first when
returning to the project — it captures everything that is not obvious from the code,
especially the failed approaches (so you don't retry them) and the verified facts (so you
don't re-derive them).*

## Purpose and the user's environment

The user's Android TV **does not support Dolby Vision**. Worse, its SoC decoder
**auto-engages the DV pipeline purely from in-band RPU NAL units** (HEVC NAL type 62) in the
bitstream — regardless of container signaling, player choice, or player settings ("Disable
Dolby Vision" toggles do nothing). Result: DV content plays with washed-out colors.

This app registers as a system video player, intercepts media, removes/neutralizes DV, and
forwards to a real player.

- **User's player:** Vimu (also tested Nova, VLC; Kodi handles DV internally on its own).
- **Typical source:** Stremio → torrentio/torbox debrid → direct HTTPS MKV URLs, 20+ GB 4K remuxes.
- **Verified:** plain HDR10 (non-DV) files trigger HDR correctly in every player on this TV,
  so DV data was always the sole culprit.
- The TV does not always show its HDR badge for app-internal playback even when the panel is
  in HDR mode — check picture-mode names or `adb shell dumpsys display | grep -iE "hdr|colorMode"`.

## Dolby Vision facts that drove the design (all verified)

| Profile | Structure | Strategy |
|---|---|---|
| 7 (UHD-BD) / 8 (WEB-DL "hybrid DV/HDR") | HDR10-compatible base layer + RPU NALs (type 62) + container config record | Remove/neutralize DV → clean HDR10, lossless |
| 5 (DV-only, IPTPQc2) | No compatible base layer | Cannot be fixed without slow tonemap re-encode (6–12h+ on TV hardware). App warns; user chose "play anyway / cancel" over on-device re-encode |

- Container signaling: MP4 `dvvC`/`dvcC`/`dvwC` boxes + `dvh1`/`dvhe` sample entry fourcc;
  MKV `BlockAdditionMapping` (EBML ID 0x41E4) containing a dv fourcc.
- ffprobe detection: stream-level `side_data_type: "DOVI configuration record"` (has
  `dv_profile`); frame-level fallback `"Dolby Vision RPU Data"` via
  `-read_intervals %+#8 -show_entries frame_side_data=side_data_type` (catches in-band-only
  files — many torrent MKVs carry RPUs with **no** container signaling at all).
- Profile heuristic when only frame-level RPUs found: bt2020nc + smpte2084 VUI → treat as 8;
  otherwise → 5 (`ProbeParser.guessProfile`).
- FFmpeg strip command (validated on official Dolby Sol Levante samples):
  `-map 0 -c copy -bsf:v dovi_rpu=strip=1` — removes RPUs + config record, keeps HDR10 VUI;
  140MB in 0.4s on desktop (I/O bound).

## Approach history — what failed and why (do not retry)

1. **Pipe-per-connection proxy** (FFmpeg remux → named pipe → NanoHTTPD chunked): players
   probe + reopen + parallel-request URLs; every reconnect killed and restarted FFmpeg
   (8–15s remote header parse each time) → mutual restart loop, nothing ever played.
2. **Rolling live HLS proxy** (`-re`, `hls_list_size 15`, `delete_segments`): played, and —
   important data point — **displayed HDR correctly** (because RPUs were genuinely stripped),
   but live-window semantics: player showed ~10s duration, froze at the live edge every
   segment, no seeking. `-re` is mandatory there (without it the delete-window outruns the
   player), which is exactly what makes it unusable for movies. Kept only as fallback for
   m3u8/DASH inputs.
3. **Byte-identical pass-through proxy with container-signaling patches only**: perfect
   streaming UX (native Range seeking, real duration, zero CPU/storage), but **colors stayed
   washed out** — this TV engages DV from the in-band RPUs alone. Container patching is
   necessary (for player-level detection, e.g. ExoPlayer selecting video/dolby-vision
   MediaCodec) but not sufficient on this hardware.
4. **Final architecture** = (3) + **in-flight RPU NAL rewriting** (`MkvRpuTransformer`):
   every RPU NAL in the video track is rewritten in place into a same-size filler NAL
   (type 38: header `0x4C 0x01`, `0xFF` padding, `0x80` rbsp-stop). Byte lengths never
   change → all offsets/Range math stay valid. This finally produced correct HDR colors.

Also rejected: growing temp-file remux for remote sources (24GB movies vs. a few GB of TV
storage); ExoPlayer-based re-serving (needless complexity); dovi_tool binary (still needs
FFmpeg around it).

## Current architecture (v1)

```
VIEW intent → InterceptActivity
  probe (MediaProbe/ffprobe, 45s watchdog, rw_timeout for network)
  ├─ no DV   → forward original untouched
  ├─ P5      → warn dialog → play-anyway/cancel
  └─ P7/8    → Decision.stripMode:
      ├─ local file + free space → StripEngine temp-file remux (dovi_rpu=strip=1),
      │    progress bar, cache reuse by MD5(uri|size), post-strip re-probe safety net,
      │    retry chain: dovi_rpu → dovi_rpu -sn -dn → filter_units=remove_types=62
      └─ else → ProxyService (127.0.0.1:46836)
          ├─ PATCH mode (direct MP4/MKV): byte-identical Range serving
          │    + static patches (Mp4DvPatcher: dvh1→hvc1, dvvC→free;
          │                      MkvDvPatcher: void BlockAdditionMapping)
          │    + MkvRpuTransformer (MKV only): cluster-buffered RPU→filler rewrite
          │    + SELF-CHECK: ffprobe of own output logged 3s after start
          └─ HLS mode (m3u8/DASH fallback): continuous FFmpeg → rolling local HLS
  forward → startActivityForResult with caller's FULL extras (position resume etc.);
            onActivityResult relays the player's result back (Stremio progress saving)
            and stops the proxy.
```

Engine: `com.antonkarpenko:ffmpeg-kit-https:2.2.1` (Maven Central, FFmpeg 8.x, Java package
`com.antonkarpenko.ffmpegkit`). The original `com.arthenica:ffmpeg-kit-*` is retired and
**404s on Maven Central** — never switch back. NanoHTTPD 2.3.1 serves HTTP.

`MkvRpuTransformer` operating notes: buffers one Cluster at a time (players seek to Cluster
boundaries via Cues, so mid-file ranges land on element starts); needs `MkvMeta` from
`MkvDvPatcher.analyze()` (first-cluster offset, video track number, NAL length-prefix size
from hvcC byte 21). Anything unparseable (unknown-size clusters, >96MB clusters, laced video
blocks, mid-cluster range starts) degrades to pass-through rather than corrupting bytes.

## Verification assets and method

- `app/src/test/resources/fixtures/`: REAL ffprobe JSON outputs + real Dolby sample slices
  (`p81_dvh1.mp4` = dvh1+dvvC, moov at end; `p81_hev1_prefix.bin` = hev1+dvvC faststart
  header; `p81_dv.mkv` = BlockAdditionMapping + in-band RPUs). Source: official
  [DolbyLaboratories/dolby-vision-contents](https://github.com/DolbyLaboratories/dolby-vision-contents)
  Sol Levante clips (P5 + P8.1, ~140MB each, via git-lfs media endpoint).
- Unit tests (30+) cover parsing, decision logic, command builders, patchers, transformer —
  including tests that WRITE patched/transformed files to `app/build/patched/` so they can be
  verified with desktop ffprobe (`zero DV markers + clean full decode + HDR10 VUI intact`
  was confirmed for: dovi_rpu strip, MP4 patching, MKV patching, RPU transformer).
- Desktop tools used (re-download if needed): gyan.dev ffmpeg-release-essentials (9.0.1),
  quietvoid/dovi_tool 2.3.4. Synthetic P8.1 can be built with dovi_tool `generate`+`inject-rpu`.
- On-device: `adb logcat -s DVStrip` logs every step; the proxy's `SELF-CHECK` line reports
  whether frame RPUs / DV config survive in its own output.

## Build

- No gradlew committed. Build with the cached distribution:
  `~/.gradle/wrapper/dists/gradle-8.14.2-bin/*/gradle-8.14.2/bin/gradle(.bat) :app:testDebugUnitTest :app:assembleDebug`
- AGP 8.5.2, Kotlin 2.2.0 (2.0.0 fails: transitive kotlin-stdlib 2.2.0 metadata), compileSdk 34,
  minSdk 24, JDK 17. APK ~102MB (FFmpeg for 4 ABIs; could split per-ABI to halve it).
- `local.properties` points at `%LOCALAPPDATA%\Android\Sdk`.

## Known limitations / future work (ordered by likely impact)

1. **P7 dual-layer MKV (UHD-BD remuxes):** EL+RPU may live in BlockAdditions per block, not
   in-band. Voiding the mapping hides them from demuxers, but the BlockAdditional payloads
   are not rewritten. If a P7 file misbehaves: extend the transformer to blank BlockMore/
   BlockAdditional (0x75A1 → BlockMore 0xA6 → BlockAdditional 0xA5) in the video track.
2. **MP4 in-band RPU rewriting not implemented** — MP4 gets container patches only. If an
   MP4 with in-band RPUs hits the same SoC auto-detect: needs stsz/stco/stsc sample-table walk
   to locate video sample byte ranges, then the same NAL rewrite. (Rare in practice; the
   torrent world is MKV.)
3. **EBML CRC-32 elements become stale** after voiding mappings / rewriting cluster bytes.
   ffprobe, ExoPlayer, Vimu don't care. If some player rejects the stream: also patch out
   (or recompute) CRC-32 (ID 0xBF) elements in modified scopes.
4. **Mid-cluster range starts** pass through unpatched until the next parseable element
   (players seek via Cues to cluster starts, so this is rare — worst case a brief DV flash).
5. **HLS fallback mode** still has live-window UX (no real seeking, subs dropped) — inherent.
6. **P5 → SDR** remains out of scope by user decision (needs slow tonemap re-encode; could be
   an optional overnight batch mode for local files).
7. APK size: per-ABI splits or arm64-only build would drop ~50MB.
8. Release signing: currently debug-signed only (fine for sideloading).

## Repo map

- Design spec: `docs/superpowers/specs/2026-09-13-dv-strip-player-design.md`
- Implementation plan: `docs/superpowers/plans/2026-09-13-dv-strip-player.md`
- This document: `docs/PROJECT-CONTEXT.md`
- App code: `app/src/main/java/com/dvstrip/player/` (10 files, each single-purpose)
- Tests + fixtures: `app/src/test/`
