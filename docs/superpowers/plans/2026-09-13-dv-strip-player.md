# DV Strip Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android TV middleware app that registers as a video player, strips Dolby Vision (P7/P8 → HDR10 losslessly; P5 → warn) from local files and URLs/streams, then forwards to a user-chosen player.

**Architecture:** InterceptActivity receives VIEW intents → MediaProbe (ffprobe) detects DV profile → temp-file lossless remux with progress (local files, space permitting) or live NanoHTTPD proxy fed by FFmpeg via named pipe (URLs / low space) → forward to configured player. Engine: FFmpeg 8 via `com.antonkarpenko:ffmpeg-kit-https:2.2.1` (verified on Maven Central; contains `dovi_rpu` bsf).

**Tech Stack:** Kotlin, AGP 8.5.2, Gradle 8.14.2 (cached at `~/.gradle/wrapper/dists`), compileSdk/target 34, minSdk 24, ffmpeg-kit fork, NanoHTTPD 2.3.1, coroutines, AppCompat views (no Compose). JVM unit tests (JUnit4) for all pure logic.

**Verified facts (do not re-verify):**
- `com.antonkarpenko:ffmpeg-kit-https:2.2.1` resolves on Maven Central; Java package is `com.antonkarpenko.ffmpegkit`; ABIs arm64-v8a/armeabi-v7a/x86/x86_64; `libavcodec.so` contains `dovi_rpu`.
- `com.arthenica:ffmpeg-kit-*` is 404 (retired) — do not use.
- SDK: platforms android-34/35, build-tools 34.0.0/35.0.0 at `%LOCALAPPDATA%\Android\Sdk`. JDK 17.

## File structure

```
Dolby/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
├── app/build.gradle.kts
└── app/src/
    ├── main/AndroidManifest.xml
    ├── main/java/com/dvstrip/player/
    │   ├── Prefs.kt            # SharedPreferences wrapper (player choice, toggles)
    │   ├── PlayerRegistry.kt   # enumerate installed video players
    │   ├── ProbeParser.kt      # PURE: ffprobe JSON → DvInfo (TDD)
    │   ├── Decision.kt         # PURE: DvInfo → action; strip-mode selection (TDD)
    │   ├── FfCommands.kt       # PURE: FFmpeg/ffprobe arg builders (TDD)
    │   ├── MediaProbe.kt       # Android: runs FFprobeKit, feeds ProbeParser
    │   ├── StripEngine.kt      # Android: temp-file remux w/ progress + retry -sn
    │   ├── ProxyService.kt     # Android: fg service, NanoHTTPD ← FFmpeg pipe
    │   ├── InterceptActivity.kt# orchestration UI
    │   └── SettingsActivity.kt # player picker, toggles, cache mgmt
    ├── main/res/... (layouts, strings, themes, xml/file_paths, banner)
    └── test/java/com/dvstrip/player/  (ProbeParserTest, DecisionTest, FfCommandsTest)
    └── test/resources/fixtures/       (real ffprobe JSON captured in Task 1)
```

---

### Task 1: Desktop validation of DV strip + fixture capture

Work in scratchpad dir. Purpose: prove the exact FFmpeg commands the app will run, on desktop FFmpeg 8 (same major as bundled), and capture real ffprobe JSON as unit-test fixtures.

- [ ] **1.1** Download & unzip desktop tools: `https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip` and latest `dovi_tool` Windows release from `https://github.com/quietvoid/dovi_tool/releases`.
- [ ] **1.2** Generate a synthetic DV P8.1 sample:
  ```
  ffmpeg -f lavfi -i testsrc2=duration=5:size=640x360:rate=24 -pix_fmt yuv420p10le \
    -c:v libx265 -x265-params "colorprim=bt2020:transfer=smpte-st-2084:colormatrix=bt2020nc" base.hevc
  dovi_tool generate --json gen.json --rpu-out rpu.bin   # gen.json: {"cm_version":"V29","profile":"8.1","length":120,"level6":{"max_display_mastering_luminance":1000,"min_display_mastering_luminance":1,"max_content_light_level":1000,"max_frame_average_light_level":400}}
  dovi_tool inject-rpu -i base.hevc --rpu-in rpu.bin -o dv.hevc
  ffmpeg -i dv.hevc -c copy dv.mkv
  ```
- [ ] **1.3** Capture fixtures (save each to `app/src/test/resources/fixtures/` later):
  - `ffprobe -v quiet -print_format json -show_format -show_streams dv.mkv` → `streams_dv_mkv.json`
  - `ffprobe -v quiet -print_format json -select_streams v:0 -read_intervals "%+2" -show_entries frame=side_data_list dv.mkv` → `frames_dv.json`
  - Same two probes on a plain non-DV file → `streams_plain.json`, `frames_plain.json`
  - Record the exact `side_data_type` strings observed (expect "DOVI configuration record" and a frame-level "Dolby Vision" entry).
- [ ] **1.4** Validate the strip command: `ffmpeg -y -i dv.mkv -map 0 -c copy -bsf:v dovi_rpu=strip=1 out.mkv`, then re-probe `out.mkv` (streams + frames) → assert NO DOVI side data; `dovi_tool info`/`extract-rpu` on extracted hevc finds no RPUs. Save post-strip JSON as fixture `streams_stripped.json`.
- [ ] **1.5** (Best effort, ≤2 attempts) fetch a small real DV MP4 (P5 or P8 WEB-DL style) and repeat 1.4 against it. Skip if not easily found — app has a post-strip re-probe safety net.
- [ ] **1.6** Commit fixtures note: fixtures land in repo in Task 3.

### Task 2: Project scaffold + smoke build

- [ ] **2.1** Write `settings.gradle.kts`:
  ```kotlin
  pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
  dependencyResolutionManagement { repositories { google(); mavenCentral() } }
  rootProject.name = "DVStripPlayer"
  include(":app")
  ```
- [ ] **2.2** Root `build.gradle.kts`:
  ```kotlin
  plugins {
      id("com.android.application") version "8.5.2" apply false
      id("org.jetbrains.kotlin.android") version "2.0.0" apply false
  }
  ```
  `gradle.properties`: `org.gradle.jvmargs=-Xmx2048m`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`
  `local.properties`: `sdk.dir=C\:\\Users\\emertech_frontend\\AppData\\Local\\Android\\Sdk`
- [ ] **2.3** `app/build.gradle.kts` (namespace `com.dvstrip.player`, min 24, target/compile 34, JVM 17; deps: ffmpeg-kit-https 2.2.1, nanohttpd 2.3.1, core-ktx 1.13.1, appcompat 1.7.0, coroutines-android 1.8.1; testImpl junit 4.13.2 + org.json:json:20240303).
- [ ] **2.4** Minimal `AndroidManifest.xml` (launcher SettingsActivity stub only), theme, strings; generate 320×180 banner PNG via PowerShell System.Drawing ("DV STRIP" text on dark background).
- [ ] **2.5** Build: `~/.gradle/wrapper/dists/gradle-8.14.2-bin/*/gradle-8.14.2/bin/gradle.bat -p <proj> :app:assembleDebug` → BUILD SUCCESSFUL, then commit.

### Task 3: Pure logic (TDD): ProbeParser, Decision, FfCommands

- [ ] **3.1** Copy Task-1 fixtures into `app/src/test/resources/fixtures/`.
- [ ] **3.2** Write `ProbeParserTest` (red): parse `streams_dv_mkv.json` → hasDV=true, profile=8, duration≈5.0, container/codec populated; `streams_plain.json` → hasDV=false; `frames_dv.json` → framesHaveDovi=true; `frames_plain.json` → false; `streams_stripped.json` → false; color-heuristic cases (RPU + bt2020nc/smpte2084 → 8; RPU + unknown matrix → 5).
- [ ] **3.3** Implement `ProbeParser` (org.json; tolerant of missing fields):
  ```kotlin
  data class DvInfo(val hasDolbyVision: Boolean, val profile: Int?, val durationSec: Double?,
                    val videoCodec: String?, val container: String?, val sizeBytes: Long?)
  object ProbeParser {
      fun parseStreams(json: String): DvInfo          // side_data_type startsWith "DOVI configuration"→ dv_profile
      fun framesHaveDovi(json: String): Boolean       // any frame side_data_type contains "Dolby Vision"
      fun guessProfile(streamsJson: String): Int      // 8 if bt2020nc+smpte2084 else 5
  }
  ```
- [ ] **3.4** `DecisionTest` (red) → implement:
  ```kotlin
  enum class DvAction { FORWARD, STRIP, WARN_P5 }
  enum class StripMode { TEMP_FILE, PROXY }
  object Decision {
      fun actionFor(dv: DvInfo): DvAction   // no DV→FORWARD; profile 5→WARN_P5; else STRIP
      fun stripMode(isLocalFile: Boolean, sizeBytes: Long?, freeBytes: Long, alwaysProxy: Boolean): StripMode
      // TEMP_FILE iff local && size!=null && free > size + 500MB && !alwaysProxy
  }
  ```
- [ ] **3.5** `FfCommandsTest` (red) → implement arg-list builders exactly as validated in Task 1 (`probeStreams`, `probeFrames`, `strip(in,out)`, `stripToPipe(in,pipe)` with `-f matroska`; retry variant appending `-sn -dn`). Output container rule: mp4/mov input → `.mp4`, else `.mkv`.
- [ ] **3.6** Run `gradle.bat :app:testDebugUnitTest` → all green → commit.

### Task 4: Android glue: Prefs, PlayerRegistry, MediaProbe, StripEngine, ProxyService

(No JVM tests — thin wrappers over verified pure logic; verified on-device.)

- [ ] **4.1** `Prefs.kt`: playerPackage/playerActivity/playerLabel, alwaysProxy (false), retentionHours (24).
- [ ] **4.2** `PlayerRegistry.kt`: union of `queryIntentActivities` for VIEW `video/*` over http and file, `MATCH_ALL`, exclude own package, distinct, sorted.
- [ ] **4.3** `MediaProbe.kt`: suspend probe(input) → DvInfo. Runs `FFprobeKit.executeWithArguments`; streams parse; if no config record, frame fallback + `guessProfile`. content:// input converted via `FFmpegKitConfig.getSafParameterForRead`.
- [ ] **4.4** `StripEngine.kt`: `remuxToFile(input, outFile, durationSec, onProgress, onComplete)` using `FFmpegKit.executeWithArgumentsAsync` + StatisticsCallback (statistics.time/duration → %); on failure delete partial + retry once with `-sn -dn`; post-strip re-probe safety net: if output still has DV config → retry with `filter_units=remove_types=62`. Cache key = MD5(uri+size), `.ok` marker for reuse; cleanup of stale files.
- [ ] **4.5** `ProxyService.kt`: foreground service (channel + notification, `mediaPlayback` type on 29+); NanoHTTPD bound to 127.0.0.1:46836; each `serve()` cancels prior FFmpeg session, registers fresh pipe via `FFmpegKitConfig.registerNewFFmpegPipe`, launches async strip-to-pipe, returns chunked `video/x-matroska` response from `FileInputStream(pipe)`, `Accept-Ranges: none`; 10-min idle stop; `ACTION_STOP` handling.
- [ ] **4.6** Compile check + commit.

### Task 5: InterceptActivity + UI flow

- [ ] **5.1** Layout `activity_intercept.xml`: title, status text, determinate/indeterminate ProgressBar, cancel button (D-pad focusable).
- [ ] **5.2** `InterceptActivity.kt`: parse VIEW intent (file/content/http/https); no player configured → dialog → Settings. Probe with spinner → Decision:
  - FORWARD → forward original URI/mime + title extra, finish.
  - WARN_P5 → AlertDialog "DV-only (Profile 5)… colors will be wrong" [Play anyway / Cancel].
  - STRIP + TEMP_FILE → progress remux → FileProvider URI → forward.
  - STRIP + PROXY → start ProxyService with input → forward `http://127.0.0.1:46836/stream.mkv`.
  - Probe/strip errors → dialog offering pass-through untouched.
- [ ] **5.3** Manifest: exported InterceptActivity with mime-based filter (video/*, x-matroska, HLS/DASH mimes over http/https/file/content) + extension-based filter (`.mkv .mp4 .ts .m3u8 .mpd .mov .webm .avi`); FileProvider (`cache-path` stripped/); permissions INTERNET, FOREGROUND_SERVICE(+MEDIA_PLAYBACK), READ_EXTERNAL_STORAGE≤32, READ_MEDIA_VIDEO≥33; leanback + touchscreen not required.
- [ ] **5.4** Build + commit.

### Task 6: SettingsActivity

- [ ] **6.1** Layout: current player row → single-choice dialog from PlayerRegistry; "always proxy" CheckBox; cache size + Clear button; static behavior summary (P7/8→HDR10, P5→warn).
- [ ] **6.2** Wire prefs, LEANBACK_LAUNCHER + LAUNCHER, banner. Build + commit.

### Task 7: Final build, APK verification, delivery

- [ ] **7.1** `gradle.bat :app:testDebugUnitTest :app:assembleDebug` → green + BUILD SUCCESSFUL.
- [ ] **7.2** Verify APK: `aapt2 dump badging app-debug.apk` → intent filters, banner, leanback; confirm native libs present (unzip -l | grep libavcodec).
- [ ] **7.3** If an emulator/device is reachable via adb: install + launch smoke test (settings opens, intercept handles a sample URL). Otherwise document sideload steps.
- [ ] **7.4** README.md: what it does, limitations (P5 warning, proxy seeking, DRM apps impossible), install (adb install), how to set as default player on Android TV.
- [ ] **7.5** Final commit; deliver APK path `app/build/outputs/apk/debug/app-debug.apk`.
