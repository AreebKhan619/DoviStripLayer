# Target Device — Full Hardware & Platform Report

*Captured 2026-09-17 from the live device over adb, by an unprivileged `shell` user
(uid 2000, SELinux enforcing, no root). Every value below was read from the device itself;
nothing is quoted from vendor marketing material or recalled from memory.*

**Reading rules for this document**

- **Measured** = read directly from a device interface. **Derived** = computed from measured
  values (the arithmetic is shown). **Inferred** = a judgement call, always labelled as such.
- Load-bearing values were confirmed against **two or more independent sources**; the
  cross-confirmation ledger is at the end.
- Deliberately omitted for privacy: MAC addresses, the global IPv6 address, the LAN IP,
  and serial numbers. They are readable with the commands in the last section if needed.
- Several values are simply **not readable without root** on a locked retail unit. Those are
  listed explicitly in §14 so nobody wastes time retrying them.

---

## 1. Device identity

| Field | Value | Source |
|---|---|---|
| Marketing model | Vu VIBE TV | `ro.product.model` |
| Brand | VU | `ro.product.brand` |
| Manufacturer (ODM) | **KONKA** | `ro.product.manufacturer` |
| Product name | `KKRTK2885GTV_VU_L` | `ro.product.name` |
| ODM product name | `KKRTK2885GTVDVB_K` | `ro.product.odm.name` |
| Board / device codename | `bandra` | `ro.product.board`, `ro.product.device` |
| Hardware SKU | `TV` | `ro.boot.hardware.sku` |
| Vendor SKU | `x` | `ro.boot.product.vendor.sku` |
| Device characteristics | `nosdcard,tv` | `ro.build.characteristics` |
| Netflix (NRDP) model group | `REFPLUSOCA4KRTD2885NGTV` | `ro.vendor.nrdp.modelgroup` |

**Decoding the product string.** `KKRTK2885GTV_VU_L` = **KK**(Konka) + **RTK2885**(Realtek
RTD2885) + **GTV**(Google TV) + **VU**(retail brand) + **L**(variant). The ODM build adds
**DVB** (tuner build). The string is accurate about the silicon — see §2.

## 2. SoC

| Field | Value | Source |
|---|---|---|
| **SoC part number** | **Realtek RTD2885N** | `ro.soc.manufacturer` + `ro.soc.model` |
| Platform / BSP name | `rtd6748` | `ro.boot.hardware` → `ro.hardware`, `ro.board.platform` |
| EGL vendor | `realtek` | `ro.hardware.egl` |
| HW composer | `video` | `ro.hardware.hwcomposer` |
| Arch generic timer | 27 MHz | `vendor.mali.platform_agt_frequency_khz=27000` |

**The two-name trap.** `ro.soc.model` (**RTD2885N**) is the marketed part number — Android 12+
mandates this field precisely so there is one reliable place to read it. `ro.board.platform`
(**rtd6748**) is Realtek's internal platform/BSP designation for the same silicon: the
bootloader sets `androidboot.hardware=rtd6748`, `ro.hardware` and `ro.board.platform` inherit
from it, and every vendor config file is keyed on it.

> **Use `RTD2885N`** when searching for specifications or other people's reports.
> **Use `rtd6748`** when looking up files under `/vendor`.
> `ro.board.platform` is *not* an SoC part number and must not be read as one.

The vendor image is a multi-SoC BSP. `/vendor/etc` carries config for: `rtd2816a`, `rtd2818a`,
`rtd2819a`, `rtd2841a`, `rtd2851a`, `rtd2851f`, `rtd2851m`, `rtd2875q`, `rtd2885m`, `rtd2885p`,
`rtd6702`, `rtd6748`. This one-image-many-SKUs design is what gates the Dolby Vision
situation in §11.

## 3. CPU

**Quad-core ARM big.LITTLE, two clusters.** Implementer `0x41` (ARM Ltd) throughout.

| Cores | MIDR part | ARM core | Variant / rev | cpufreq policy | Frequencies |
|---|---|---|---|---|---|
| 0, 1 | `0xd05` | **Cortex-A55** | `0x2` / 0 | `policy0` | OPPs 600 MHz, 1066 MHz (running at 1066) |
| 2, 3 | `0xd0a` | **Cortex-A75** | `0x3` / 1 | `policy2` | min 700 MHz; max not readable (§14) |

- Architecture: ARMv8-A. Governor: `schedutil` on both clusters.
- Single package (`topology/package_cpus_list = 0-3`).
- ISA features: `neon vfpv3 vfpv4 idiva idivt lpae evtstrm` plus crypto extensions
  **`aes pmull sha1 sha2 crc32`**.
- BogoMIPS 54.00 on every core. This is the **arch timer**, not core speed: 27 MHz × 2 = 54,
  matching the 27 MHz AGT property in §2.

### ⚠️ 3.1 — The userspace is 32-bit only

**This device runs a 32-bit-only Android userspace on 64-bit-capable silicon.** Five
independent confirmations:

| Evidence | Value |
|---|---|
| `ro.product.cpu.abilist64` | **empty** |
| `ro.product.cpu.abilist` | `armeabi-v7a,armeabi` |
| `ro.zygote` | `zygote32` |
| `/system/lib64/libc.so` | does not exist |
| `uname -m` | `armv8l` (32-bit ARM), and `/proc/cpuinfo` is in 32-bit format |

**Consequence for this project: an `arm64-v8a` build will not run on this TV.** If the APK is
ever ABI-split to cut size, the ABI to keep is **`armeabi-v7a`** — see the corrected
limitation 7 in `PROJECT-CONTEXT.md`.

## 4. GPU

| Field | Value | Source |
|---|---|---|
| GPU | **Imagination Technologies PowerVR B-Series BXE-4-32** | SurfaceFlinger GLES renderer string |
| Driver build | `23.2@6491706` | same |
| OpenGL ES | **3.2** | GLES string; `ro.opengles.version=196610` (0x30002) |
| Vulkan | **1.3.0** | `android.hardware.vulkan.version=4206592` (derived: 4206592−(1<<22)=12288; 12288>>12=3) |
| Vulkan compute / level | supported | `android.hardware.vulkan.compute`, `.level` |
| EGL | 1.5 Android META-EGL | SurfaceFlinger |
| AEP | supported | `android.hardware.opengles.aep` |

Notable GL extensions: ASTC LDR compression, `GL_EXT_YUV_target`, `GL_OVR_multiview`,
`GL_EXT_shader_pixel_local_storage2`, `EGL_EXT_surface_SMPTE2086_metadata` and
`EGL_EXT_surface_CTA861_3_metadata` (HDR static-metadata surfaces).

## 5. Memory

| Field | Value | Source |
|---|---|---|
| MemTotal | **1,739,624 kB** (≈1.66 GiB) | `/proc/meminfo` |
| Physical RAM | **2 GB** *(inferred — MemTotal excludes VPU/GPU/secure carveouts)* | derived |
| zram swap | 1,023,996 kB (≈1000 MiB), device `zram0` | `/proc/meminfo` SwapTotal, `/sys/block` |
| Dalvik heap | 384 MB | `dalvik.vm.heapsize` |
| Low-RAM device | no | feature `android.hardware.ram.normal` |
| tmpfs size | 849 MB | `df` (≈½ MemTotal, Android default — corroborates MemTotal) |

## 6. Storage and partitions

Single **eMMC** (`mmcblk0`, with `mmcblk0boot0`/`boot1`). No SD card slot (`nosdcard`).

| Mount | Size | Used | Device |
|---|---|---|---|
| `/data` | 11 GB | 3.9 GB (39%) | `dm-44` |
| `/` (system) | 807 MB | 100% | `dm-6` |
| `/product` | 635 MB | 100% | `dm-10` |
| `/system_ext` | 314 MB | 100% | `dm-7` |
| `/vendor` | 201 MB | 100% | `dm-9` |
| `/oem` | 13 MB | 1% | `dm-8` |
| `/odm` | 816 kB | 100% | `dm-11` |

All system partitions are `dm-*` devices — **dynamic partitions inside `super`, under
dm-verity**. Raw eMMC capacity is not readable (§14).

**Partition table** (`/dev/block/by-name`, A/B slotted unless noted):
`FW_AKERNEL` (audio DSP firmware), `FW_VKERNEL`, `FW_VKERNEL2` (video DSP firmware),
`FW_TZFW` (TrustZone), `FW_DVLOGO` (Dolby Vision logo asset), `boot`, `bootloader`, `dtbo`,
`oem`, `super`, `tvconfigs`, `vbmeta`, `vbmeta_system`, `vendor_boot`; plus non-slotted
`factory`, `factory_default`, `impdata`, `metadata`, `misc`, `persist`, `rescue_linux`,
`reserved_0`–`reserved_3`, `tvdata`, `userdata`.

## 7. Display

| Field | Value |
|---|---|
| Panel resolution | **3840 × 2160** |
| Refresh modes | 60.000004 Hz, 30.000002 Hz |
| Pixel density | 80.674 × 80.682 dpi; Android density 640 (xxxhdpi) |
| Physical size | **≈54.6 in diagonal** *(derived: 3840/80.674 = 47.60 in × 2160/80.682 = 26.77 in)* |
| UI render resolution | 1920 × 1080 @ density 320 (framework renders 1080p, scans out 4K) |
| **HDR types** | **`[2, 3]` = HDR10 + HLG only** |
| Peak luminance | 500.0 nits (max and max-average); min 0.0 |
| Color modes | `[0]` — `ColorMode::NATIVE` only |
| HDR conversion | `HDR_CONVERSION_SYSTEM`, preferred output `HDR_TYPE_INVALID` |
| ALLM / game mode | `allmSupported=true`, `gameContentTypeSupported=true`, `minimalPostProcessingSupported=true` |

In `Display.HdrCapabilities`: 1 = DOLBY_VISION, 2 = HDR10, 3 = HLG, 4 = HDR10_PLUS.
**Type 1 is absent — the display pipeline cannot output Dolby Vision. Type 4 is also absent —
no HDR10+.**

## 8. Video codecs

Runtime list = **vendor OMX components** (below) + Android's software codecs from
`/apex/com.android.media.swcodec` (`media.swcodec` and `media.codec` both running).
The vendor chain is `ro.media.xml_variant.codecs=_4k_3` → `media_codecs_4k_3.xml` →
`media_codecs_realtek_video_4k_2.xml` + `media_codecs_realtek_audio_basic.xml`.

### `OMX.realtek.video.decoder` — 6 concurrent instances per type

| MIME | Codec | Max size | Bitrate |
|---|---|---|---|
| `video/avc` | H.264 | **1920 × 1088** | — |
| `video/hevc` | H.265 | 4096 × 2176 | 1–80 Mbps |
| `video/x-vnd.on2.vp9` | VP9 | 4096 × 2176 | 1–80 Mbps |
| `video/av01` | AV1 | 4096 × 2176 | 1–80 Mbps |
| `video/mpeg2` | MPEG-2 | 1920 × 1088 | — |
| `video/mp4v-es` | MPEG-4 Part 2 | 1920 × 1088 | — |
| `video/3gpp` | H.263 | 1920 × 1088 | — |

Features on AVC/HEVC/VP9/AV1: `adaptive-playback`, `tunneled-playback`, `low-latency`.

> **H.264 hardware decode is capped at 1080p.** Only HEVC, VP9 and AV1 reach 4K. 4K H.264
> content will fall back to software decode on a 1.0–1.07 GHz A55/A75 pair, or fail.

### `OMX.realtek.video.decoder.secure` — 1 instance, `secure-playback` required
`video/avc` (1920×1088), `video/hevc`, `video/x-vnd.on2.vp9`, `video/av01` (all 4096×2176),
`video/mp4v-es` (1920×1088).

### Others
- `OMX.realtek.video.raw.decoder` — `video/raw`, up to 4096×2176, tunneled required.
- `OMX.realtek.video.encoder` — `video/avc` only, 176×144 to 1920×1088, ≤12 Mbps, 1 instance.

### ❌ No `video/dolby-vision`
Verified by reading the **complete text** of the actually-loaded manifest
`media_codecs_realtek_video_4k_2.xml` — not by pattern-matching. Zero occurrences of
`dolby-vision` in the loaded chain. See §11.

## 9. Audio

**Vendor MediaCodec audio is almost empty by design** — `media_codecs_realtek_audio_basic.xml`
declares exactly one component, `OMX.realtek.audio.decoder` for `audio/mpeg-L2` (MP2, 2ch,
32/44.1/48 kHz). Everything else is either an Android software codec or handled below
MediaCodec entirely.

### Dolby Audio — present and fully licensed

Dolby audio on this platform is decoded by the **audio DSP behind the audio HAL** (hence the
`FW_AKERNEL` firmware partitions) and **never passes through MediaCodec**. An empty codec
manifest says nothing about it. Both the built-in **speaker** and **HDMI-ARC** outputs
advertise:

`ENCODING_AC3` · `ENCODING_E_AC3` · `ENCODING_AC4` · `ENCODING_DOLBY_TRUEHD` ·
`ENCODING_DOLBY_MAT` — plus `ENCODING_PCM_16BIT`, `ENCODING_AAC_LC`, `ENCODING_AAC_HE_V1`,
`ENCODING_AAC_HE_V2`. Sample rates 32/44.1/48 kHz; channel masks up to `0x18FC`.

That is Dolby Digital, Dolby Digital Plus, AC-4 and the Atmos transport formats (MAT/TrueHD).

**Independently confirmed from the config side:** `ro.boot.product.vendor.sku=x` selects
`/vendor/etc/audio/sku_x/audio_policy_configuration.xml`, which declares exactly
`AUDIO_FORMAT_AC3`, `AUDIO_FORMAT_E_AC3`, `AUDIO_FORMAT_AC4`, `AUDIO_FORMAT_DOLBY_TRUEHD`,
`AUDIO_FORMAT_MAT` — an exact match with the runtime list above.

**No DTS.** The image also ships `sku_x_dts` / `sku_y_dts` / `sku_z_dts` variants, but the
non-DTS `sku_x` is the one selected.

## 10. DRM and secure media

| Scheme | Status | Source |
|---|---|---|
| **Widevine** | present | `/vendor/apex/com.google.android.widevine.nonupdatable.apex`; AIDL service `android.hardware.drm.IDrmFactory/widevine` |
| **PlayReady** | present | HIDL `android.hardware.drm@1.0–1.3::IDrmFactory/playready` |
| **HDCP2** | present | HIDL `android.hardware.drm@1.0–1.3::ICryptoFactory/hdcp2` |
| ClearKey | present | `/vendor/lib/mediadrm/libdrmclearkeyplugin.so` |
| Secure video path | present | `OMX.realtek.video.decoder.secure`, `secure-playback required=true` |

Widevine **security level (L1/L3) is not determinable from an unprivileged shell** — the
`media.drm` dumpsys service is not registered. The Netflix NRDP model group and the presence
of a secure tunneled decode path both point to L1, but that is inference, not measurement.

## 11. Dolby Vision status (project-critical)

Three layers, and the inconsistency between them is the whole reason this project exists.

| Layer | DV state | Evidence |
|---|---|---|
| Display / panel | **absent** | `mSupportedHdrTypes=[2,3]` — no type 1 |
| Android codec list | **absent** | full read of the loaded manifest chain: zero `dolby-vision` |
| Kernel / VPU firmware | **loaded and live** | `/sys/class/dolbyvisionEDR/dolbyvisionEDR0/` registered at boot |

The `dolbyvisionEDR0` node is a **character-device class node** (it has a `dev` attribute),
SELinux-denied to shell, exposing an interface to the vendor media HAL. It has **no tunable
attributes** — there is no Realtek equivalent of Amlogic's `dolby_vision_policy` /
`dolby_vision_enable`. Do not go looking for `/sys/class/amdolby_vision/`; wrong vendor.

The BSP is fully DV-capable: `/vendor/etc` ships `dvhe.st`, `dvhe.stn`, `dvhe.dtr`, `dvav.se`
and `dav1.10` as `video/dolby-vision` decoders (OMX **and** Codec2, secure and non-secure)
inside the `_4k_1 / _4k_2 / _4k_4 / _4k_5 / _4k_6 / _4k_14` variant files. This SKU loads
`_4k_3`, which has none of them. `FW_DVLOGO_a/b` partitions exist as part of the common board
layout.

**Dolby Audio is licensed on this unit (§9); Dolby Vision is not.** These are separately
licensed, separately certified products, and "Dolby Audio yes, Dolby Vision no" is a standard
product tier — Realtek even ships a `media_codecs_dolby_audio_only.xml` for it. The audio path
is enabled all the way to Atmos while the video path is enabled nowhere, which reads as
deliberate, granular provisioning rather than an oversight.

## 12. Connectivity

| Interface | Status |
|---|---|
| Ethernet (`eth0`) | present, in use, IPv4 + IPv6 (SLAAC, temporary + stable-privacy addresses) |
| Wi-Fi (`wlan0`) | present, down at capture time; `p2p0` (Wi-Fi Direct) and `ap0` (SoftAP) exist |
| Bluetooth | present, Classic + LE |
| USB | host mode |
| HDMI-CEC | supported |

Android features: `ethernet`, `wifi`, `wifi.direct`, `wifi.passpoint`, `bluetooth`,
`bluetooth_le`, `usb.host`, `hdmi.cec`, `gamepad`, `camera.external`, `type.television`,
`audio.low_latency`, `audio.output`, `location`, `location.network`, `screen.landscape`,
`security.model.compatible`.

Bluetooth profiles enabled: A2DP **source**, AVRCP target, GATT, HID device + host, OPP,
ASHA central, BAS client. Disabled: HFP AG, PAN NAP/PANU.

**No `android.hardware.tv.tuner` feature** is exposed, despite `DVB` appearing in the ODM
build name — the Vu SKU does not surface a tuner to Android.

## 13. OS, boot and security

| Field | Value |
|---|---|
| Android | **14** (SDK 34), `release_or_codename` 14 |
| Build ID | `UKRC.260302.018` |
| Build date | Fri 27 Mar 2026 16:29:23 UTC (`ro.build.date.utc=1774628963`) |
| Security patch | **2026-04-01** |
| Build type / tags | `user` / `release-keys` |
| Kernel | **Linux 5.4.242+-ab127** `#2 SMP PREEMPT Thu Mar 19 22:03:31 UTC 2026`, `armv8l`, Clang 11.0.2 |
| Treble / VNDK | enabled / 34 |
| Zygote | `zygote32` |
| Locale | `en-IN` |

### Security posture — no root path

| Check | Value | Meaning |
|---|---|---|
| `ro.boot.verifiedbootstate` | **`green`** | AVB verified, unmodified |
| `ro.boot.flash.locked` | **`1`** | **bootloader locked** |
| `ro.boot.veritymode` | **`enforcing`** | dm-verity enforcing |
| `ro.debuggable` | `0` | non-debuggable build |
| SELinux | enforcing (`u:r:shell:s0`) | |
| `su` | not present | |

`/vendor` cannot be modified: it is a dynamic partition inside `super` under enforcing
dm-verity, with a locked bootloader and green verified-boot state. Editing
`media_codecs_4k_3.xml` or repointing `ro.media.xml_variant.codecs` is not possible on this
unit.

## 14. Not readable without root

Recorded so nobody retries them. All returned `Permission denied` or an absent service to
uid 2000:

`/proc/partitions` · `/proc/bus/input/devices` · `/sys/class/thermal/thermal_zone*/{type,temp}`
· `cpufreq/scaling_max_freq` and `cpuinfo_{min,max}_freq` (all policies) ·
`policy2/scaling_cur_freq` and `scaling_available_frequencies` ·
`/sys/devices/system/cpu/cpu*/cache/*` (not exposed at all) · `/sys/block/mmcblk0/size` ·
`/sys/block/zram0/disksize` · `/proc/device-tree/{model,compatible}` ·
`/sys/class/mmc_host/mmc0/*/cid` and friends · `dumpsys media.drm` (service not registered) ·
`/sys/class/dolbyvisionEDR/dolbyvisionEDR0/*` attributes.

## 15. Reproducing this report

Connect first — **the TV's IP changes**, so read it from the TV's network settings:

```sh
adb connect <tv-ip>:5555
```

Run `grep` **on the device** by quoting the whole remote command. From Windows, prefer a POSIX
shell with `MSYS_NO_PATHCONV=1` for any `/sys` or `/proc` path, or Windows will rewrite it.
PowerShell has no `grep` and strips inner double quotes.

```sh
# Identity, SoC, OS, boot state
adb shell 'getprop ro.soc.manufacturer; getprop ro.soc.model'        # RTD2885N — the real part
adb shell 'getprop ro.board.platform; getprop ro.hardware'           # rtd6748 — the BSP name
adb shell 'getprop | grep -iE "product\.(brand|model|name)|build\.(id|type|version)"'
adb shell 'getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked; getprop ro.boot.veritymode'

# CPU / ABI  (the 32-bit finding)
adb shell 'cat /proc/cpuinfo'
adb shell 'getprop ro.product.cpu.abilist64; getprop ro.zygote; uname -a'
adb shell 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo $p; cat $p/affected_cpus $p/scaling_available_frequencies 2>&1; done'

# GPU / display
adb shell 'dumpsys SurfaceFlinger' | grep -E "GLES:|EGL implementation"
adb shell 'dumpsys display' | grep -iE "hdrCapabilities|supportedHdrTypes|supportedColorModes"

# Codecs — read the WHOLE loaded chain, do not pattern-match
adb shell 'getprop ro.media.xml_variant.codecs'                      # -> _4k_3
adb shell 'cat /vendor/etc/media_codecs_4k_3.xml'                    # -> its <Include> list
adb shell 'cat /vendor/etc/media_codecs_realtek_video_4k_2.xml'
adb shell 'cat /vendor/etc/media_codecs_realtek_audio_basic.xml'

# Audio (Dolby lives here, NOT in MediaCodec)
adb shell 'dumpsys audio' | grep -oE 'ENCODING_[A-Z0-9_]+' | sort -u
adb shell 'getprop ro.boot.product.vendor.sku'                       # -> x
adb shell 'grep -oE "AUDIO_FORMAT_[A-Z0-9_]+" /vendor/etc/audio/sku_x/audio_policy_configuration.xml | sort -u'

# DRM / Dolby Vision
adb shell 'service list | grep -i drm'
adb shell 'lshal | grep -iE "drm|crypto"'
adb shell 'ls /sys/class/ | grep -i dolby'
adb shell 'ls /dev/block/by-name/'
```

## 16. Cross-confirmation ledger

Every load-bearing claim and the independent sources that agree on it.

| Claim | Sources |
|---|---|
| SoC is Realtek RTD2885N | `ro.soc.model`; `ro.soc.manufacturer`; NRDP model group `…RTD2885N…`; all build fingerprints (`KKRTK2885GTV`) |
| `rtd6748` is the BSP name, not the part | `ro.boot.hardware` (bootloader-set) → `ro.hardware`/`ro.board.platform` derive from it; `_4k_3_rtd6748` perf variant; file `media_codecs_performance_4k_3_rtd6748.xml` exists |
| 2×A55 + 2×A75 | `/proc/cpuinfo` MIDR parts `0xd05`/`0xd0a`; cpufreq `policy0`=cpus 0-1 and `policy2`=cpus 2-3 split matches exactly |
| **32-bit-only userspace** | `ro.product.cpu.abilist64` empty; `ro.product.cpu.abilist`=armeabi-v7a; `ro.zygote=zygote32`; no `/system/lib64/libc.so`; `uname -m=armv8l`; 32-bit `/proc/cpuinfo` format |
| BogoMIPS is the timer, not core clock | `/proc/cpuinfo` 54.00; `vendor.mali.platform_agt_frequency_khz=27000` (27 × 2 = 54) |
| GPU PowerVR BXE-4-32, GLES 3.2 | SurfaceFlinger renderer string; `ro.opengles.version=196610`=0x30002; `ro.hardware.vulkan=powervr` |
| MemTotal ≈1.66 GiB | `/proc/meminfo`; `df` tmpfs = 849 MB ≈ ½ MemTotal |
| Display 4K, HDR10+HLG, 500 nits | `dumpsys display` hdrCapabilities; SurfaceFlinger mode list (3840×2160 @60/30) |
| No `video/dolby-vision` codec | full text read of `media_codecs_realtek_video_4k_2.xml`; root `media_codecs_4k_3.xml` include list; `grep -ic dolby-vision` = 0 |
| **Dolby Audio present** | `dumpsys audio` runtime encodings on speaker *and* hdmi_arc; `/vendor/etc/audio/sku_x/audio_policy_configuration.xml` declares the identical five formats; `ro.boot.product.vendor.sku=x` selects that file |
| DV driver live in kernel | `/sys/class/dolbyvisionEDR/dolbyvisionEDR0/` present, created at boot, has a `dev` attribute |
| Bootloader locked / verity on | `ro.boot.flash.locked=1`; `ro.boot.verifiedbootstate=green`; `ro.boot.veritymode=enforcing`; all system mounts are `dm-*` |
| Widevine present | vendor APEX `com.google.android.widevine.nonupdatable.apex`; registered AIDL `IDrmFactory/widevine` |

### Corrections made during this capture

Two earlier conclusions were wrong and were caught by this cross-checking pass. Both are
recorded because the *method* that produced them is the trap:

1. **"The TV has no Dolby anything."** Drawn from an empty MediaCodec audio manifest. Wrong —
   Dolby audio is decoded by the audio DSP behind the audio HAL and never appears in a codec
   manifest. An empty manifest is not evidence of absence when the feature lives in another
   layer.
2. **"No DV entries in `media_codecs_realtek_video_4k.xml`."** That is not the file this SKU
   loads; the real include is `media_codecs_realtek_video_4k_2.xml`. The conclusion survived
   re-checking, but it had been verified against the wrong file. Resolve the `<Include>` chain
   from `ro.media.xml_variant.codecs` before trusting any codec claim.

A third near-miss: grepping for `MediaCodec name="…" type="…"` found almost nothing, because
decoders declare formats as nested `<Type>` children, not as an attribute. Read the file.
