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
| Physical RAM | **2 GB** — *measured, not inferred* | factory menu front page: `DDR/EMMC 2GB/16GB`. The ~340 MB shortfall vs MemTotal is VPU/GPU/secure carveout |
| zram swap | 1,023,996 kB (≈1000 MiB), device `zram0` | `/proc/meminfo` SwapTotal, `/sys/block` |
| Dalvik heap | 384 MB | `dalvik.vm.heapsize` |
| Low-RAM device | no | feature `android.hardware.ram.normal` |
| tmpfs size | 849 MB | `df` (≈½ MemTotal, Android default — corroborates MemTotal) |

## 6. Storage and partitions

Single **16 GB eMMC** (`mmcblk0`, with `mmcblk0boot0`/`boot1`) — capacity from the factory menu
front page (`DDR/EMMC 2GB/16GB`), since the raw size is unreadable from shell (§14). No SD card
slot (`nosdcard`).

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

### 11.1 The silicon supports Dolby Vision — measured, not inferred

**The RTD2885N decodes Dolby Vision at 4K60. Realtek measured it on this exact chip.**

Android's `media_codecs_performance_*.xml` files hold *measured* capability: performance points
a vendor declares after running the codec on real hardware, which CTS validates on any device
that exposes the codec. A vendor cannot declare an operating point for a codec the silicon
cannot run. Of the thirteen `/vendor/etc` files carrying the **`rtd6748`** suffix — this chip —
five contain Dolby Vision entries:

| Performance profile for this SoC | `dolby-vision` entries |
|---|---|
| `media_codecs_performance_4k_2_rtd6748.xml` | 10 |
| `media_codecs_performance_4k_5_rtd6748.xml` | 10 |
| `media_codecs_performance_c2_4k_2_rtd6748.xml` | 10 |
| `media_codecs_performance_c2_4k_11_rtd6748.xml` | 10 |
| `media_codecs_performance_c2_4k_rtd6748.xml` | 10 |
| **`media_codecs_performance_4k_3_rtd6748.xml`** ← **selected by this TV** | **0** |

The declared figures are full-rate, not placeholders:

```xml
<MediaCodec name="OMX.realtek.video.dvhe.st.decoder" type="video/dolby-vision" update="true">
    <Limit name="performance-point-1920x1080" range="120-120" />
    <Limit name="performance-point-3840x2160" range="60-60" />
</MediaCodec>
```

Identical points for `dvhe.stn`, `dvhe.dtr` and `dav1.10`; `dvav.se` is 1080p120 only. All five
also exist in `.secure` form for DRM playback. **The only difference between this TV and a
DV-capable set built on the same silicon is which profile number the SKU selects** — `_4k_3`
rather than `_4k_2` or `_4k_5`.

By Dolby's codec-string convention the five map to profiles **5** (`dvhe.stn`), **8**
(`dvhe.st`), **4** (`dvhe.dtr`), **9** (`dvav.se`) and **10** (`dav1.10`). Note what is
**absent: `dvhe.dtb`, profile 7** — even DV-enabled SKUs of this chip would not decode P7
dual-layer UHD-BD remuxes natively. (Profile mapping is from the standard Dolby naming
convention, not read off the device.)

The corresponding decoder declarations live in the `_4k_1 / _4k_2 / _4k_4 / _4k_5 / _4k_6 /
_4k_14` codec variant files, OMX and Codec2, secure and non-secure. `FW_DVLOGO_a/b` partitions
exist as part of the common board layout.

### 11.2 The unit is a Dolby-tier product running with Dolby stripped at runtime

**This overturns an earlier reading in this very document — that the model was never a Dolby
product and its DV tuning data "was never generated". Both were wrong.**

The bootloader declares a codec tier that is not the one in force:

| Property | Value | Origin |
|---|---|---|
| `ro.boot.variant.codecs` | **`4k_2`** | bootloader (project config) |
| `ro.media.xml_variant.codecs` | **`_4k_3`** | set at runtime |
| `ro.media.xml_variant.codecs_performance` | `_4k_3_rtd6748` | set at runtime |

The two tiers differ by exactly two `<Include>` lines:

| `media_codecs_4k_2.xml` — the declared project tier | `media_codecs_4k_3.xml` — what actually loaded |
|---|---|
| `media_codecs_realtek_video_4k_2.xml` | `media_codecs_realtek_video_4k_2.xml` |
| **`media_codecs_realtek_video_dolby_vision_4k.xml`** (10 DV decoders) | *(absent)* |
| `media_codecs_realtek_audio_basic.xml` | `media_codecs_realtek_audio_basic.xml` |
| **`media_codecs_realtek_audio_dolby.xml`** | *(absent)* |

`4k_3` is `4k_2` **minus the two Dolby includes** — identical video base, identical audio base.
**The project this unit declares is the Dolby tier; something downgraded it at runtime.**

The selection is dynamic, not baked into the build. `/vendor/etc/init/mediainit.rc` starts
`/vendor/bin/mediainit` at `early_hal`, and init derives the properties from what it publishes:

```
on property:ro.vendor.rtk.media.boot.variant.codecs=*
    setprop ro.media.xml_variant.codecs _${ro.vendor.rtk.media.boot.variant.codecs}
    setprop ro.media.xml_variant.codecs_performance _${…}_${ro.hardware}

on property:ro.vendor.rtk.media.product.vendor.sku=*
    setprop ro.boot.product.vendor.sku ${ro.vendor.rtk.media.product.vendor.sku}
```

**Most likely cause — hypothesis, not proven.** The factory app reports **DV MD5 absent** on
this unit: the Dolby Vision provisioning blob is missing or fails its checksum, so media init
fails safe to the Dolby-less tier. The owner reports that after changing Project ID once (which
wiped user data) the factory app *did* show DV MD5 data, though DV decoding was never confirmed.

Tracing it further is impossible from an unprivileged shell: `/vendor/bin/mediainit` is
SELinux-protected (`mediainit_exec` — cannot be read or pulled), `/mnt/vendor/tvconfigs`
(`mmcblk0p33`, ro), `/mnt/vendor/factory` (`p4`, rw), `/mnt/vendor/factory_ro` (`p6`, ro) and
`/mnt/vendor/impdata` (`p36`, rw) are all denied, and even `/vendor/build.prop` is unreadable.

### 11.3 The open lead — Project ID via the factory app

`com.toptech.tvfactory` and `com.toptech.factorytoolsgtv` are installed and expose **Project ID**
selection. Project ID drives the bootloader-supplied properties and the provisioning data, and
the partitions it writes are mounted **rw**. This is a **vendor-sanctioned path requiring
neither root nor an unlocked bootloader** — which is exactly why the earlier "no on-device
workaround exists" conclusion was wrong. The locked bootloader never blocked this route.

#### What the factory app actually does — decompiled (jadx), not guessed

Both APKs are world-readable under `/system/app` and can be pulled without root (§15).
Decompiling `TopTvFactory.apk` settles what the factory screens mean.

**1. "DV MD5" is a checksum of a file named by the project INI.** From
`com/toptech/tvfactory/picture/PicturePageLogic.java`:

```java
RtkProjectConfigs cfg = RtkProjectConfigs.getInstance();
String config  = cfg.getConfig("[MISC_PQ_MAP_CFG]", "PQ");
String config2 = cfg.getConfig("[MISC_PQ_MAP_CFG]", "PQ_HDR");
String config3 = cfg.getConfig("[MISC_PQ_MAP_CFG]", "PQ_OSD");
String config4 = cfg.getConfig("[MISC_PQ_MAP_CFG]", "DV");     // <-- "DV MD5"
md5DV.setSumary(byteToString(sb, getMd5(messageDigest, config4)));
```

and `getMd5` does `new File(str)` — the INI **value is an absolute path** — returning `null`
unless the file both `exists()` and `canRead()`. `byteToString(null)` renders the literal
string **`"File not exist or can not read."`**

So **"DV MD5 absent" means precisely: the currently selected project's INI either has no `DV`
key under `[MISC_PQ_MAP_CFG]`, or names a Dolby Vision picture-table file that is not on the
device.** It is a read-only factory QC readout — **not a switch**, and not itself the DV enable.

**2. A "project" is literally an INI file.** `getProjectIniList()` returns INI filenames,
numbered 1..`getProjectMaxIdx()`, with the active one flagged `select`. Every call routes
through `com.realtek.system.RtkProjectConfigs` to the running HIDL service
`vendor.realtek.rtkconfigs@1.0::IRtkProjectConfigs/default` — the app is a thin UI over it.
**The on-screen list will therefore show real INI names**, which normally encode model and
panel. That list cannot be read from shell (`/mnt/vendor/tvconfigs/model/` is denied), so it is
the one piece of information only the TV can give you.

**3. What a project change actually rewrites**, from `applyPidConfig()`:

```java
pidChgConfig.applyPanelSetting            = true;   // <-- the risk, in one line
pidChgConfig.applyIRSetting               = true;
pidChgConfig.applyBootAnimationSetting    = true;
pidChgConfig.applyBootlogoSetting         = true;
pidChgConfig.applyAmpSetting              = true;
pidChgConfig.applyTunerSelectSetting      = true;
pidChgConfig.applyPcbSetting              = true;
pidChgConfig.applyPanelEyeDiagramSetting  = true;
pidChgConfig.applyDynamicTconlessSetting  = true;
```

then it copies/deletes `vby1_eyediagram.bin` according to `[PANEL] EYE_DIAGRAM_BIN`, and
broadcasts `android.intent.action.FACTORY_RESET` with the reason set to
`RtkProjectConfigs.getOemImageName()` — the wipe, confirmed in code.

Note what is **absent** from that list: no PQ or DV item. The picture tables are not "applied"
by the factory app; they are read from the INI at boot by the PQ subsystem.

**4. The app reads only six INI keys in total** — `[MISC_PQ_MAP_CFG]` → `PQ`, `PQ_HDR`,
`PQ_OSD`, `DV`; `[PANEL]` → `EYE_DIAGRAM_BIN`, `m_pPanelName`. It **never reads or sets any
codec-variant key.** The link between the project and `ro.boot.variant.codecs` therefore lives
in the bootloader or `mediainit`, not here — which is why §11.2's causal claim remains a
hypothesis rather than a proven chain.

#### The consequence for strategy

The app only *reports* the checksum; it cannot author or import a DV table. So the lever is
**not** "provision DV data" — it is "select a project whose INI ships one". Usefully, that makes
**DV MD5 a direct per-project readout of DV provisioning**: switch, look, decide.

One refinement the decompile suggests. The bootloader *already* declares the Dolby tier
(`4k_2`) while the DV picture table is missing. That points at a unit which is DV-provisioned at
the codec level but whose DV **picture data** was never flashed — in which case the minimal
repair would be restoring that one file rather than changing project at all. It is not
actionable from here: the INI, its `DV` path, and the file itself are all inside
`/mnt/vendor/tvconfigs`, which shell cannot read.

Because the table is *panel-specific calibration*, a project built for a different panel carries
tuning for that panel's peak luminance and primaries. **Working DV with wrong colour is a
genuine third outcome**, distinct from both today's washed-out state and a clean success. If the
list exposes model names or sizes, prefer the closest sibling to this set — roughly 55-inch
(§7), 500-nit, same panel family. `[PANEL] m_pPanelName` is readable by the app, so the factory
UI may be able to show you the panel name for comparison.

**Objective test after any project change — two commands, no guesswork:**

```sh
adb shell 'getprop ro.boot.variant.codecs; getprop ro.media.xml_variant.codecs'
adb shell 'dumpsys display | grep -o "mSupportedHdrTypes=\[[^]]*\]"'
```

- If `ro.media.xml_variant.codecs` becomes `_4k_2`, the DV decoders are registered.
- **The second command is the real success criterion.** It currently reads `[2, 3]`. If **`1`**
  (DOLBY_VISION) appears, the display pipeline will output DV and the problem is solved at
  source. A codec flip *without* the HDR list changing would mean a registered decoder feeding
  a display that still cannot present DV — plausibly the same washed-out result by a new route.

> **Before changing anything: record the current Project ID and photograph the factory screens.**
> Project ID also selects panel timings, backlight curve, tuner region and audio tuning. A
> project built for a different panel can produce wrong geometry, wrong colour, or a display too
> broken to navigate back with — and recovery depends entirely on knowing what to return to.
> It also wipes user data.

**If it works, it retires this app entirely — and additionally fixes Profile 5**, which byte
rewriting fundamentally cannot address (no HDR10 base layer to fall back to). If it half-works,
nothing is lost and the app still covers the gap.

#### The project list — read programmatically, 2026-09-17

**It is readable without root and without touching the TV's remote.** `FactoryMenuActivity` is
`exported="true"` with an `android.intent.action.MAIN` filter, so it can be launched over adb,
driven with `input keyevent`, and read with `uiautomator dump` (commands in §15). Navigation is
read-only: `onPreferenceItemClick` is the only path to the confirm dialog, so arrow keys alone
can never change anything.

The factory menu's front page also **confirms two values that were inferences in §5 and §6**:

```
Project Name : IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_12V_6R10W
Panel Name   : PWM47K_HV550QUB_F70_V20.ini
DDR/EMMC     : 2GB/16GB          <- physical RAM and flash, measured not derived
Software     : V3.47.0
```

**The list holds 304 projects** (it wraps at 304). The current one is **#179**, flagged
`select`:

```
179  IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_LCD_12V_6R10W.ini      <- CURRENT
193  IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_LCD_12V_6R10W_DV.ini   <- same + _DV
```

**#193 is character-for-character identical to #179 with `_DV` appended.** Same model
(`UG55AK680N`), same panel *and panel revision* (`HV550QUB_F70_V20`), same backlight driver
(`PWM47K`), same `XMX`, `60HZ`, `LCD`, `12V`, `6R10W`. This is the exact hardware twin, which
**removes the wrong-panel-calibration risk** that the rest of this section warns about — that
warning applies to every other candidate, not to #193.

Other DV/Dolby-named projects, none of which match this panel:

| # | Project | Why not |
|---|---|---|
| 2, 20, 21, 71 | `*_Bandra_OLED_*_Dolby.ini` | **OLED** panel — wrong display technology entirely |
| 81 | `EU_Cottongreen_BOE_HV550QUB_E1D_AD82088_Dolby.ini` | HV550QUB but revision **E1D**, not F70_V20 |
| 90, 200 | `…HV650QUB_E72…_DV.ini`, `…HV750QUB_E95…_DV.ini` | 65"/75" panels |
| 114, 131 | `…HV650QUB_F70_60HZ_LCD_XMX_{DV,DOLBY}.ini` | same F70/XMX family but 65" |

Konka clearly ships **paired projects** — the same hardware with and without Dolby (114 vs 77,
131 vs 55, 193 vs 179). That pairing is itself evidence that the difference between them is
Dolby provisioning rather than hardware.

**The full list lives in `PROJECT-IDS.md`** — all 304 entries, no gaps, with #179 and #193
flagged inline, every Dolby/DV project scored for suitability, and the read-only procedure to
re-read it. The complete list was searched for `dolby` / `_dv`: there are exactly **10** such
projects and **#193 is the only one matching this panel**.

#### ❌ OUTCOME — tried 2026-09-17, it does not work

**Project 193 was selected. The switch succeeded. Dolby Vision did not come back.**

The factory menu confirms the project changed — `Project Name` now reads
`IN_VU_UG55AK680N_PWM47K_HV550QUB_F70_V20_XMX_60HZ_LCD_6R10W_DV`, and `Panel Name` is unchanged
at `PWM47K_HV550QUB_F70_V20.ini`. So this is a real negative result, not a failed switch.

Every measurement after the wipe and reboot is **identical to before**:

| Check | Before | After #193 |
|---|---|---|
| `ro.boot.variant.codecs` | `4k_2` | `4k_2` |
| `ro.media.xml_variant.codecs` | `_4k_3` | `_4k_3` |
| `mSupportedHdrTypes` | `[2, 3]` | `[2, 3]` |
| `ro.boot.product.vendor.sku` | `x` | `x` |

And the factory **PQ Adjust** page shows why:

```
PQ MD5      : E8991C6749721F5EBD806DD4229A83EA
PQ_HDR MD5  : 24CF096EB78592816F7849FBDFC69FF7
PQ_OSD MD5  : 77C65DB854BB5D6D39AB9F66A29391FF
DV MD5      : File not exist or can not read.     <-- still absent
```

That string is exactly `byteToString(null)` from §11.2 — `getMd5()` returned null because the
path in `[MISC_PQ_MAP_CFG] → DV` does not exist. **The other three picture tables are present
with real hashes; only the Dolby Vision one is missing.**

**Conclusion: the firmware ships the DV project *definition* but not the DV *data*.** Selecting
a DV project cannot conjure a calibration file that was never flashed into this unit's
`/mnt/vendor/tvconfigs`. The bottleneck was never project selection — it is the missing table,
and it is not reachable (tvconfigs is `ro` and denied without root, §14).

This also strengthens §11.2's causal story without fully proving it: three PQ tables present and
hashing fine, the DV one absent, and the runtime still downgrading `4k_2` → `_4k_3`. Consistent,
but not isolated — that would need a case where DV MD5 *is* present, which we do not have.

**Cost/benefit of what was tried:** one full factory reset, no functional change, no damage.
Panel geometry and colour are correct (as predicted — #193 carries the same
`HV550QUB_F70_V20` config), Dolby Audio is unaffected, and nothing regressed. The unit was left
on #193 rather than reverted: another wipe buys nothing measurable, and if a future firmware
ever ships the DV tables, being on the DV project is the only configuration where they would
take effect.

**The byte-rewriting approach this app implements remains the answer.**

#### Is anything left to try?

Honestly, very little — and nothing cheap.

The one loose thread: the owner recalls that an earlier project change (before this
investigation) *did* show DV MD5 data. If that memory is accurate, some project's DV path
resolves to a file that exists, and the tables are not wholly absent from tvconfigs. Every such
candidate is a **wrong-panel** project though (§ the table above: OLED, 65″/75″, or revision
E1D), so success would mean Dolby Vision running on another panel's calibration — the
"working DV with wrong colour" outcome — at the price of another wipe per attempt, plus one more
to get back. Poor odds, real cost.

Ruled out for the record:

- **Restoring just the DV file.** `/mnt/vendor/tvconfigs` is mounted `ro` and denied to shell;
  no root, locked bootloader, verity enforcing (§13).
- **Editing the INI** to point `[MISC_PQ_MAP_CFG] DV` at an existing table — same access wall,
  and it would be another panel's data anyway.
- **USB firmware upgrade** to a DV-provisioned image. Present in the factory menu, but that is
  flashing vendor firmware on a locked retail set to chase a maybe. Not advisable.

So the practical position is closed: this TV decodes no Dolby Vision, the silicon could, the
data isn't there, and it cannot be put there. Rewriting the bytes above the decoder stays the
only lever.

### 11.4 What holds regardless of the above

- The silicon decodes DV at 4K60 (§11.1).
- The panel is **not** the disqualifier it appears to be: 500 nits is low-end, but Dolby Vision
  ships on panels in that range — it is an end-to-end format, not a brightness threshold.
- Dolby **Audio** works on this unit through the audio HAL (§9) *even though* `4k_3` drops
  `media_codecs_realtek_audio_dolby.xml`, because HAL-side decoding never used MediaCodec at
  all. No contradiction between the two findings.
- **As shipped and unmodified, the device registers no `video/dolby-vision` codec and outputs
  no DV.** The byte-rewriting approach stays necessary until a project change is *proven* to
  work by the two commands above.

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

Also denied, and directly relevant to §11.2 — the project-config chain cannot be traced from
shell on this build:

`/vendor/bin/mediainit` (SELinux `mediainit_exec`; neither readable nor `adb pull`-able) ·
`/mnt/vendor/tvconfigs` (`mmcblk0p33`, ro) · `/mnt/vendor/factory` (`p4`, rw) ·
`/mnt/vendor/factory_ro` (`p6`, ro) · `/mnt/vendor/impdata` (`p36`, rw) ·
`/vendor/build.prop`, `/system/build.prop`, `/odm/etc/build.prop`.

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

# DRM
adb shell 'service list | grep -i drm'
adb shell 'lshal | grep -iE "drm|crypto"'

# Dolby Vision — silicon capability vs what this SKU enables
adb shell 'ls /sys/class/ | grep -i dolby'                           # -> dolbyvisionEDR
adb shell 'for f in /vendor/etc/*rtd6748*.xml; do printf "%s : %s\n" \
    "$(basename $f)" "$(grep -ic dolby-vision $f)"; done'            # measured DV perf points
adb shell 'grep -A3 dolby-vision /vendor/etc/media_codecs_performance_4k_2_rtd6748.xml | head'

# THE PROJECT-TIER MISMATCH (§11.2) — the most important check in this document
adb shell 'echo "bootloader : $(getprop ro.boot.variant.codecs)"; \
           echo "effective  : $(getprop ro.media.xml_variant.codecs)"'
adb shell 'grep -i Include /vendor/etc/media_codecs_4k_2.xml'        # declared tier: has Dolby
adb shell 'grep -i Include /vendor/etc/media_codecs_4k_3.xml'        # in force: Dolby removed
adb shell 'cat /vendor/etc/init/mediainit.rc'                        # how the tier is selected
adb shell 'getprop | grep "^\[ro.boot\."'                            # all bootloader-supplied props
adb shell 'pm list packages | grep -iE "factory|toptech"'            # the factory app
adb shell 'mount | grep -iE "tvconfig|factory|impdata"'              # project config partitions

# AFTER A PROJECT ID CHANGE — did Dolby Vision actually come back? (§11.3)
adb shell 'getprop ro.boot.variant.codecs; getprop ro.media.xml_variant.codecs'
adb shell 'dumpsys display | grep -o "mSupportedHdrTypes=\[[^]]*\]"'  # success = a "1" appears

# Partition layout
adb shell 'ls /dev/block/by-name/'

# The factory apps — world-readable, pullable without root
adb shell 'pm path com.toptech.tvfactory; pm path com.toptech.factorytoolsgtv'
adb pull /system/app/TopTvFactory/TopTvFactory.apk
adb pull /system/app/FactoryTools-GTV/FactoryTools-GTV.apk
adb shell 'lshal | grep -i rtkconfigs'        # -> IRtkProjectConfigs/default, running
```

No `jadx` is needed to read the constants — parse the DEX string table directly. Save as
`dexstr.py` and run `python3 dexstr.py TopTvFactory.apk > strings.txt`:

```python
import sys, zipfile, struct
def uleb(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1; r |= (x & 0x7f) << s
        if not x & 0x80: return r, i
        s += 7
def dex_strings(d):
    if d[:4] != b'dex\n': return []
    n, off = struct.unpack_from('<II', d, 56)          # string_ids_size, string_ids_off
    out = []
    for k in range(n):
        so = struct.unpack_from('<I', d, off + 4 * k)[0]
        _, p = uleb(d, so)
        out.append(d[p:d.index(b'\x00', p)].decode('utf-8', 'replace'))
    return out
sys.stdout.reconfigure(encoding='utf-8', errors='replace')   # required on Windows consoles
with zipfile.ZipFile(sys.argv[1]) as z:
    seen = set()
    for nm in z.namelist():
        if nm.endswith('.dex'):
            for s in dex_strings(z.read(nm)):
                if s not in seen: seen.add(s); print(s)
```

Then: `grep -iE "md5|dolby|projectid|picture_mode" strings.txt | sort -u`

**Reading the factory menu programmatically** — no root, no remote. `FactoryMenuActivity` is
exported, so launch it, drive it with key events and read it with `uiautomator`. Arrow keys only:
never send `KEYCODE_DPAD_CENTER` inside the Project ID list, because that opens the confirm
dialog whose submit path wipes user data.

```sh
adb shell am start -n com.toptech.tvfactory/.FactoryMenuActivity
adb shell input keyevent KEYCODE_DPAD_DOWN      # navigate; CENTER only outside the PID list
adb exec-out uiautomator dump /dev/tty | python3 ui.py
adb shell input keyevent KEYCODE_BACK           # leave; then KEYCODE_HOME
```

`ui.py` — prints the selected row plus all visible text, which is what makes the list readable:

```python
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
nodes = sys.stdin.read().split('<node')
items = [(re.search(r'\btext="([^"]*)"', n).group(1), 'selected="true"' in n)
         for n in nodes if re.search(r'\btext="([^"]*)"', n)
         and re.search(r'\btext="([^"]*)"', n).group(1).strip()]
print("SELECTED:", [t for t, s in items if s] or "(none)")
for t, s in items: print(("  >> " if s else "     ") + t)
```

Route to the list: **Factory Setting → Project ID** (9 × `DPAD_DOWN` from `Test Pattern`).
Verify `SELECTED: ['Project ID']` before the single `CENTER` that opens it.

For the logic rather than the constants, decompile with jadx (~1 min for this APK):

```sh
jadx -d out_factory --no-debug-info -q TopTvFactory.apk
# the three files that matter:
#   com/toptech/tvfactory/picture/PicturePageLogic.java   <- the four PQ/DV MD5s
#   com/toptech/tvfactory/user/ProjectIdFragment.java     <- project list, apply, factory reset
#   com/toptech/tvfactory/api/impl/UserApi.java           <- thin wrapper over RtkProjectConfigs
grep -rhoE 'getConfig\("\[[A-Z_0-9]+\]", *"?[A-Za-z_0-9.]*"?' out_factory/sources | sort -u
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
| **The SoC itself decodes DV at 4K60** | five `*rtd6748*` performance profiles declare `performance-point-3840x2160 = 60-60` for `video/dolby-vision` (measured capability, CTS-validated class of file); the profile this SKU selects declares none; DV decoder components exist in the `_4k_1/_4k_2/_4k_4/_4k_5/_4k_6/_4k_14` codec variants |
| **The declared project tier is the Dolby tier, and was downgraded at runtime** | `ro.boot.variant.codecs=4k_2` (bootloader) vs `ro.media.xml_variant.codecs=_4k_3` (in force); `media_codecs_4k_3.xml` include list is `media_codecs_4k_2.xml`'s minus `…_dolby_vision_4k.xml` and `…_audio_dolby.xml`; `mediainit.rc` shows the value is set at runtime, not built in |
| **"DV MD5" = MD5 of the file at INI key `[MISC_PQ_MAP_CFG] DV`** | decompiled `PicturePageLogic.initMD5()` reads that key and MD5s it via `getMd5()`, which does `new File(str)` and returns null unless `exists() && canRead()`; `byteToString(null)` prints `"File not exist or can not read."`. Siblings `PQ`, `PQ_HDR`, `PQ_OSD` in the same section |
| A project **is** an INI; the app is a thin UI over a vendor service | `getProjectIniList()` / `getProjectMaxIdx()` / `setProjectId()` all delegate to `RtkProjectConfigs` → `vendor.realtek.rtkconfigs@1.0::IRtkProjectConfigs/default` (registered, running per `lshal`) |
| A project change reconfigures the **panel** and wipes data | decompiled `applyPidConfig()` sets `applyPanelSetting`/`applyPcbSetting`/`applyTunerSelectSetting`/… then broadcasts `android.intent.action.FACTORY_RESET` |
| Project ID is served at runtime, list not in the APK | `lshal` shows `vendor.realtek.rtkconfigs@1.0::IRtkProjectConfigs/default` registered and running; app calls `getProjectIdNum` / `getProjectIdName` / `getCurrentProjectId`; `FactoryTools-GTV` has `ProjectIDActivity`, `ProjectIdListLength:` |
| Bootloader locked / verity on | `ro.boot.flash.locked=1`; `ro.boot.verifiedbootstate=green`; `ro.boot.veritymode=enforcing`; all system mounts are `dm-*` |
| Widevine present | vendor APEX `com.google.android.widevine.nonupdatable.apex`; registered AIDL `IDrmFactory/widevine` |

### Corrections made to this document

Four earlier conclusions were wrong. They are recorded rather than quietly edited out, because
in each case the *method* that produced them is the reusable lesson:

1. **"The TV has no Dolby anything."** Drawn from an empty MediaCodec audio manifest. Wrong —
   Dolby audio is decoded by the audio DSP behind the audio HAL and never appears in a codec
   manifest. An empty manifest is not evidence of absence when the feature lives in another
   layer.
2. **"No DV entries in `media_codecs_realtek_video_4k.xml`."** That is not the file this SKU
   loads; the real include is `media_codecs_realtek_video_4k_2.xml`. The conclusion survived
   re-checking, but it had been verified against the wrong file. Resolve the `<Include>` chain
   from `ro.media.xml_variant.codecs` before trusting any codec claim.

3. **"The Dolby display-management tuning was never generated for this model."** Wrong, and it
   was the load-bearing claim in the original §11.2. The bootloader declares this unit's project
   as `4k_2` — the Dolby tier. The data is not nonexistent; it is *absent on this unit*
   (factory app: **DV MD5 absent**). Do not infer "never existed" from "not present"; look for
   the layer that *selects* configuration before concluding the configuration was never made.

4. **"No on-device workaround exists."** Wrong. That assumed the only route to
   `ro.media.xml_variant.codecs` was editing a verity-protected file, so a locked bootloader
   settled it. The real route is the **Project ID** in `com.toptech.tvfactory`, writing
   partitions that are mounted rw — a vendor-sanctioned path needing neither root nor an
   unlocked bootloader. Enumerate the *intended* configuration mechanisms before declaring
   something immutable.

A near-miss worth the same caution: grepping for `MediaCodec name="…" type="…"` found almost
nothing, because decoders declare formats as nested `<Type>` children, not as an attribute.
Read the file.

**The pattern across all four:** every error came from treating an absence as proof — an empty
manifest, a missing file, an unreadable partition — instead of first establishing which layer
owns the decision. Absence of evidence in the wrong layer is not evidence at all.
