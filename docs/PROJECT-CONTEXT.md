# DV Strip Player — Full Project Context

*Written 2026-09-13, at the end of the initial development session; hardware root cause added
2026-09-17. Read this first when returning to the project — it captures everything that is not
obvious from the code, especially the failed approaches (so you don't retry them) and the
verified facts (so you don't re-derive them).*

## Purpose and the user's environment

The target TV **does not support Dolby Vision** as an output format, yet its video firmware
**auto-engages the DV pipeline purely from in-band RPU NAL units** (HEVC NAL type 62) in the
bitstream — regardless of container signaling, player choice, or player settings ("Disable
Dolby Vision" toggles do nothing). Result: DV content plays with washed-out colors. The exact
mechanism is pinned down in the next section.

This app registers as a system video player, intercepts media, removes/neutralizes DV, and
forwards to a real player.

- **The device:** Vu VIBE TV — `model:Vu_VIBE_TV`, `product:KKRTK2885GTV_VU_L`, `device:bandra`.
  A Vu-branded SKU of a **KONKA** ODM board (`ro.odm.build.fingerprint`). Android 14 build
  `UKRC.260302.018`, kernel 5.4.242, `user` build, **not rooted** (`ro.debuggable=0`, no `su`,
  SELinux enforcing). Reach it over adb on port 5555 — `adb connect <tv-ip>:5555`. **The TV's IP
  changes**, so read it off the TV's network settings each time rather than hardcoding it.
- **The SoC — two identifiers, one chip. Don't repeat this mistake:**
  `ro.soc.model` = **`RTD2885N`** (Realtek; quad-core 2×Cortex-A75 + 2×Cortex-A55, PowerVR GPU).
  That is the real, marketed part number and it is what `ro.soc.model` exists to tell you
  (Android 12+ mandates the field). But `ro.board.platform`, `ro.hardware` and `ro.boot.hardware`
  all say **`rtd6748`** — Realtek's internal platform/BSP designation for the same silicon, set
  by the bootloader and used to key every vendor HAL and config file in `/vendor`. Both names
  appear in the image because the BSP tracks some variants by marketed name (`rtd2885m`,
  `rtd2885p`) and this one by platform number.
  **Use `RTD2885N` when searching for specs or other people's reports; use `rtd6748` when
  looking up files under `/vendor`.** Independent corroboration of the part: the Netflix
  certification string `ro.vendor.nrdp.modelgroup` = `REFPLUSOCA4KRTD2885NGTV`. The product
  string decodes as KONKA + RTK2885 + Google TV + Vu SKU and was accurate about the chip all
  along — `ro.board.platform` is *not* an SoC part number, so don't read it as one.
- **User's player:** Vimu (also tested Nova, VLC; Kodi handles DV internally on its own).
- **Typical source:** Stremio → torrentio/torbox debrid → direct HTTPS MKV URLs, 20+ GB 4K remuxes.
- **Verified:** plain HDR10 (non-DV) files trigger HDR correctly in every player on this TV,
  so DV data was always the sole culprit.
- The TV does not always show its HDR badge for app-internal playback even when the panel is
  in HDR mode — check picture-mode names or `adb shell dumpsys display | grep -iE "hdr|colorMode"`.

## Root cause: how this TV is configured (verified on-device 2026-09-17)

**Dolby Vision is disabled at the two layers apps and the framework can see, and left running
in the layer underneath.** That single fact explains every symptom in this project.

| Layer | State | Evidence |
|---|---|---|
| Display / panel | HDR10 + HLG only, **no DV** | `dumpsys display` → `hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[2, 3], mMaxLuminance=500.0}`. In `Display.HdrCapabilities`, 1=DOLBY_VISION, 2=HDR10, 3=HLG, 4=HDR10_PLUS — type 1 absent. Also `supportedColorModes=[0]`, `mHdrConversionMode=HDR_CONVERSION_SYSTEM`. |
| Android codec list | **no** `video/dolby-vision` codec registered | `ro.media.xml_variant.codecs=_4k_3` → `media_codecs_4k_3.xml`, which includes `media_codecs_realtek_video_4k_2.xml` + `media_codecs_realtek_audio_basic.xml`. Full text of that chain contains zero `dolby-vision`. **Resolve the `<Include>` chain first** — an earlier check against `media_codecs_realtek_video_4k.xml` was the wrong file, and decoders declare formats as nested `<Type>` children, so grepping for a `type="…"` attribute silently finds nothing. |
| Kernel / VPU firmware | **DV/EDR driver loaded and live** | `/sys/class/dolbyvisionEDR/dolbyvisionEDR0/` registered at boot. Character-device class node (has `dev`), SELinux-denied to shell, **no tunable attributes**. |

**The silicon itself decodes DV at 4K60 — this is measured, not inferred.** Five of the
thirteen `*rtd6748*` files in `/vendor/etc` (`media_codecs_performance_4k_2_rtd6748.xml`,
`_4k_5_`, `c2_4k_2_`, `c2_4k_11_`, `c2_4k_`) declare `performance-point-3840x2160 = 60-60` and
`performance-point-1920x1080 = 120-120` for `video/dolby-vision`; the one this SKU selects
(`_4k_3_rtd6748`) declares none. Performance-point files record capability measured on real
hardware, so they cannot exist for a codec the chip can't run. The matching decoder components
(`dvhe.st`, `dvhe.stn`, `dvhe.dtr`, `dvav.se`, `dav1.10` — OMX and Codec2, secure and
non-secure) live in the `_4k_1 / _4k_2 / _4k_4 / _4k_5 / _4k_6 / _4k_14` codec variants. Note
`dvhe.dtb` (**profile 7**) is absent everywhere: even DV-enabled SKUs of this chip would not
decode P7 dual-layer natively — relevant to limitation 1 below.

So no silicon is missing; the gate is licensing and provisioning. The immovable part is not the
codec XML but the **Dolby display-management tuning data**, which is generated during Dolby's
certification of a specific TV model and was never produced for this one — enabling the decoder
would feed a mapping stage with nothing to map to. See `docs/DEVICE-REPORT.md` §11 for the
measured figures and the four-gate breakdown.

**Dolby *Audio* IS licensed and working on this TV — don't confuse the two.** Speaker and
HDMI-ARC both advertise `ENCODING_AC3`, `ENCODING_E_AC3`, `ENCODING_AC4`,
`ENCODING_DOLBY_TRUEHD` and `ENCODING_DOLBY_MAT` (`dumpsys audio`), independently confirmed by
`/vendor/etc/audio/sku_x/audio_policy_configuration.xml` (selected by
`ro.boot.product.vendor.sku=x`). Dolby audio is decoded by the **audio DSP behind the audio
HAL** — the `FW_AKERNEL` firmware partitions — and **never passes through MediaCodec**, which
is why `media_codecs_realtek_audio_basic.xml` is nearly empty. An empty codec manifest is not
evidence of absence when the feature lives in a different layer; a first pass here wrongly
concluded "no Dolby licence at all" from exactly that mistake. Dolby Audio and Dolby Vision are
separately licensed and separately certified — "audio yes, vision no" is a standard product
tier, which is why Realtek ships a `media_codecs_dolby_audio_only.xml` for it.

**Why hybrids (P8.1) don't fall back.** The fallback contract lives in the container:
`dv_bl_signal_compatibility_id = 1` in the DV configuration record means "the base layer is
standalone HDR10; if you can't do DV, ignore the RPU". That field is read at the
codec-selection layer — exactly where DV was removed. The still-live firmware layer triggers on
raw NAL 62 instead and **never consults it**. The device isn't attempting the fallback and
failing; it makes the DV decision somewhere the fallback signal doesn't reach. This is why
approach 3 below (all container signaling removed) *still* rendered washed out, and why
player-level "disable DV" toggles are inert.

**There is no *sysfs* knob** — Realtek exposes DV as a chardev to the media HAL, with no
Amlogic-style `dolby_vision_policy` / `dolby_vision_enable` to flip. Don't go looking for
`/sys/class/amdolby_vision/`; wrong vendor. And `/vendor` itself is immutable: unrooted `user`
build, SELinux enforcing, dm-verity enforcing, bootloader locked.

**But an on-device workaround may well exist — an earlier version of this document said it did
not, and that was wrong.** The codec tier is chosen *at runtime*, not baked into the build, and
the bootloader says this unit should be on the **Dolby** tier:

| Property | Value |
|---|---|
| `ro.boot.variant.codecs` (bootloader / project config) | **`4k_2`** |
| `ro.media.xml_variant.codecs` (actually in force) | **`_4k_3`** |

`media_codecs_4k_3.xml` is `media_codecs_4k_2.xml` **minus exactly two includes** —
`media_codecs_realtek_video_dolby_vision_4k.xml` (10 DV decoders) and
`media_codecs_realtek_audio_dolby.xml`. Everything else is identical. So this is a Dolby-tier
unit that got downgraded at boot by `/vendor/bin/mediainit` (see `/vendor/etc/init/mediainit.rc`).
The likely trigger — **hypothesis, not proven** — is that the factory app reports **DV MD5
absent**, so media init fails safe. Decompiling the factory APK (world-readable at
`/system/app/TopTvFactory/`) pins that down exactly. `PicturePageLogic.initMD5()` does:

```java
String config4 = RtkProjectConfigs.getInstance().getConfig("[MISC_PQ_MAP_CFG]", "DV");
md5DV.setSumary(byteToString(sb, getMd5(messageDigest, config4)));   // getMd5 -> new File(str)
```

**A "project" is an INI file, and "DV MD5" is just the MD5 of whatever absolute path that INI's
`[MISC_PQ_MAP_CFG] → DV` key names** (siblings: `PQ`, `PQ_HDR`, `PQ_OSD`). "Absent" is the app
literally printing `File not exist or can not read.` — so this project either has no `DV` key or
points at a Dolby Vision picture-table file that isn't on the device. It is a read-only QC
readout, **not** the DV enable switch: the app never reads or sets any codec-variant key.

The factory app **`com.toptech.tvfactory`** exposes **Project ID** selection, which drives that
provisioning and writes partitions mounted rw (`/mnt/vendor/factory`, `/mnt/vendor/impdata`).
That is a vendor-sanctioned path needing **neither root nor an unlocked bootloader** — the
locked bootloader never blocked it. The project list is served at runtime by
`vendor.realtek.rtkconfigs@1.0::IRtkProjectConfigs` (running), so it is visible in the app's UI
but not readable from shell (`/mnt/vendor/tvconfigs/model/` is denied).

**The list has been read (2026-09-17), and there is an exact match.** `FactoryMenuActivity` is
`exported="true"`, so the menu can be launched over adb, driven with `input keyevent` and read
with `uiautomator dump` — no root, arrows only, nothing selected. Of 304 projects:

```
179  IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_LCD_12V_6R10W.ini      <- CURRENT
193  IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_LCD_12V_6R10W_DV.ini   <- same + _DV
```

**#193 is #179 character-for-character with `_DV` appended** — same model, same panel *and*
revision (`HV550QUB_F70_V20`), same `PWM47K`, `XMX`, `60HZ`, `12V`, `6R10W`. So the usual
wrong-panel-calibration risk does **not** apply to this candidate (it does to every other Dolby
project in the list — the rest are OLED, or 65"/75", or a different panel revision).

The captured list, every Dolby/DV candidate with a verdict, and the read-only procedure to
re-read it live in **`docs/PROJECT-IDS.md`**.

**The blocker is the wipe, and it is not optional.** `ProjectIdFragment` broadcasts
`android.intent.action.FACTORY_RESET` immediately after `setProjectId()` — it is in the code
path, not a preference. The owner has ruled that out, so this stays a documented lead rather
than a plan. Full analysis in `docs/DEVICE-REPORT.md` §11.2–11.3.

**Until that is proven to work, rewriting the bytes above the decoder remains the only
intervention point, which is what this app does.** The two-command verdict after any project
change:

```sh
adb shell 'getprop ro.boot.variant.codecs; getprop ro.media.xml_variant.codecs'
adb shell 'dumpsys display | grep -o "mSupportedHdrTypes=\[[^]]*\]"'   # success = a "1" appears
```

Record the current Project ID before touching anything: it also selects panel timings, backlight
and tuner region, and it wipes user data.

This also corroborates limitation 0: the panel exposes only HDR10/HLG and the framework picks
the HDR mode at codec-configure time, hence "badge only after a seek/crop" unless the container
itself carries `Colour`.

Re-derivation (run `grep` **on the device** — quote the whole remote command; PowerShell has no
`grep` and strips inner double quotes, so prefer the Bash tool with `MSYS_NO_PATHCONV=1` when
`/sys` paths are involved, or Windows will mangle them):

```sh
adb shell 'getprop ro.soc.manufacturer; getprop ro.soc.model'   # the real part: Realtek RTD2885N
adb shell 'getprop ro.board.platform; getprop ro.hardware'      # the BSP name: rtd6748
adb shell 'getprop | grep -i dolby'                             # expect: nothing
adb shell 'ls /sys/class/ | grep -i dolby'                      # -> dolbyvisionEDR (driver live)
adb shell 'dumpsys display | grep -iE "hdrCapabilities|supportedHdrTypes"'

# The project-tier mismatch — the most important check here
adb shell 'echo "bootloader : $(getprop ro.boot.variant.codecs)"; \
           echo "effective  : $(getprop ro.media.xml_variant.codecs)"'
adb shell 'grep -i Include /vendor/etc/media_codecs_4k_2.xml'   # declared tier: includes Dolby
adb shell 'grep -i Include /vendor/etc/media_codecs_4k_3.xml'   # in force: Dolby removed
adb shell 'cat /vendor/etc/init/mediainit.rc'                   # how the tier gets chosen
adb shell 'pm list packages | grep -iE "factory|toptech"'       # the factory app

# Resolve the codec chain before trusting ANY codec claim, then read the files whole:
# decoders declare formats as nested <Type> children, so grepping for type="..." finds nothing.
adb shell 'cat /vendor/etc/media_codecs_realtek_video_4k_2.xml'
```

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
   washed out** — this TV engages DV from the in-band RPUs alone (mechanism: root-cause
   section above). Container patching is necessary (for player-level detection, e.g. ExoPlayer
   selecting video/dolby-vision MediaCodec) but not sufficient on this hardware. This is also
   the controlled experiment that isolates the trigger: container signaling fully removed and
   still washed out, vs. (2)/(4) where only the in-band NAL 62 bytes changed and HDR was correct.
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

0. **HDR badge only after seek/crop (fixed 2026-09-14):** this TV sets display HDR mode at
   playback start from the container's MKV `Colour` element (0x55B0 under Video), and only
   falls back to the bitstream VUI on a codec reconfigure (seek/crop) — see the root-cause
   section: the framework picks the HDR mode at codec-configure time. DV remuxes often omit
   `Colour`. `MkvDvPatcher.buildColourInjectionPatch` synthesizes an HDR10 `Colour`
   (Matrix=9/Transfer=16/Primaries=9/Range=1) in place by donating the removed DV
   BlockAdditionMapping's bytes + a Void — SAME LENGTH, so offsets/Cues/seeking are untouched.
   Only fires when the track lacks `Colour` AND a DV mapping donor is present and big enough
   (>= ~21 bytes). Logged as `colourInjected=true`. Negative fixture: `p81_nocolour.mkv`
   (Colour id byte-swapped to 0x53FF). Validate with `/tmp/ebml_colour_check.py`-style EBML
   parsing, NOT ffprobe (ffprobe reads color from the bitstream VUI and can't see the
   container element's absence). Limitation: an in-band-only DV MKV with no BlockAdditionMapping
   donor and no Colour can't be fixed this way — would need the size-changing virtual-file
   header rewrite (SeekHead/Cues offset remap), deferred as it wasn't needed for the user's files.
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
7. APK size: per-ABI splits would drop ~50MB — but **the target TV is 32-bit only**, so the
   ABI to keep is **`armeabi-v7a`**. An **arm64-only build would not run at all**
   (`ro.product.cpu.abilist64` is empty, `ro.zygote=zygote32`, there is no `/system/lib64`,
   `uname -m=armv8l`). See `docs/DEVICE-REPORT.md` §3.1.
8. Release signing: currently debug-signed only (fine for sideloading).

## Repo map

- Design spec: `docs/superpowers/specs/2026-09-13-dv-strip-player-design.md`
- Implementation plan: `docs/superpowers/plans/2026-09-13-dv-strip-player.md`
- This document: `docs/PROJECT-CONTEXT.md`
- Full hardware/platform report for the target TV: `docs/DEVICE-REPORT.md` (SoC, CPU/ABI, GPU,
  display, full codec tables, audio, DRM, boot security, plus a cross-confirmation ledger and
  the commands to regenerate it)
- **Factory Project ID list: `docs/PROJECT-IDS.md`** — which project is the default
  (**#179**, the one to return to), which is the Dolby Vision target (**#193**), all 10
  Dolby/DV projects with a suitability verdict each, the complete 304-entry list, and the
  read-only procedure to re-read it over adb
- App code: `app/src/main/java/com/dvstrip/player/` (10 files, each single-purpose)
- Tests + fixtures: `app/src/test/`
