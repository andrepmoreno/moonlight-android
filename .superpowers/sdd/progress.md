# Progress: moonlight audio drain (plan 2026-10-18)

## Status
- **Task 1: DONE**
  - HEAD `b48494cb` (v12.2)
  - Submodule inited: `moonlight-common-c` @ `874ac95`
  - JDK 17 + ANDROID_HOME=`/home/ubuntu/Android/Sdk` ready
  - Still missing in cloud: USB/network ADB to Redmi Pad 2

- **Task 2: DONE — HYPOTHESIS DEAD**
  - Branch: `cursor/force-min-audio-buffer-bc11`
  - Pad evidence: APK with `Audio track configuration: 8640 false` still had **20** `Audio packet queue overflow` in 8s; jumps always +29/+30
  - Buffer-size force-min shortcut is **reverted / not carried** into later fix branches

- **Task 3: FAILED — single non-blocking write (no loop)**
  - Branch: `cursor/audio-nonblocking-write-bc11`
  - Evidence (failed APK ~12:43): `Audio write short: wrote 48 of 480, pending 120 ms`
  - Frame = 480 samples @ 48kHz = 5ms; only 48 samples (~1ms) written, rest discarded
  - Native 30-packet queue not drained → **20 overflows / 8s** with +29/+30 jumps = crackle

- **Task 4: FAILED — non-blocking write LOOP**
  - Branch: `cursor/audio-nonblocking-write-bc11`
  - Evidence (APK ~12:53): AudioTrack sessionID 1769, flags=0x800 → **~144 lines / 8s** of
    `AudioFlinger_Threads: threadLoop: track(sessionID = 1769) ACTIVE underrun due to thread sleep of 1024 frames`
    and `threadLoop_write write muted data to hw`
  - 1024 frames @ 48kHz ≈ 21ms → ~19/s. Same window: **22 overflows**, jumps always +29/+30
  - Track stayed "fine". Host Opus 48kHz. Crackle = flush of 30-packet queue while write waits on mixer
  - `playDecodedAudio` non-blocking loop **abandoned**; restored to v12.2 body going forward

- **Task 5: FAILED — USAGE_MEDIA (AudioAttributes)**
  - Branch: `cursor/audio-usage-media-bc11` (clean from `b48494cb` / v12.2)
  - Evidence (APK 13:07, pid 23549, session 1777): **21 overflows / 8s**, jumps +29/+30;
    **164** underruns `thread sleep of 1024 frames` + muted HW
  - Track ACTIVE and empty when mixer woke; decode thread still did `write` → native queue blew
  - USAGE_MEDIA **did not change the mixer**; reverted — `createAudioTrack` back to `USAGE_GAME`

- **Task 6: FAILED — dedicated playback thread (ring alone)**
  - Branch: `cursor/audio-playback-thread-bc11` (from clean v12.2); Draft PR #3
  - Evidence (APK 13:23, pid 27491, session 1785): ring in bytecode, `playDecodedAudio` non-blocking
    (copy-to-ring only); still **19 overflows / 8s**, +29/+30 jumps, **124** underruns of 1024 frames
  - Playback-thread architecture **kept** as base for Task 7 (do not revert ring/thread)

- **Task 7: IN PROGRESS — POWER_SAVING performance mode**
  - Branch: `cursor/audio-power-saving-bc11` (from tip of `cursor/audio-playback-thread-bc11`)
  - Only change: `createAudioTrack` SDK ≥ O Builder always
    `trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)`
    (was conditional `LOW_LATENCY`). Keep `USAGE_GAME`. No second `setPerformanceMode`.
  - Untouched: ring, `playDecodedAudio`, `playbackLoop`, bufferSize, setUsage, WRITE_NON_BLOCKING,
    moonlight-common-c, Sunshine
  - Fix commit: `46ac7976c26ae5c463c92f530cd6bda710c08f7a`
  - APK artifact: `/opt/cursor/artifacts/app-nonRoot-debug-power-saving.apk`
  - Alpha: `releases/app-nonRoot-debug-alpha.apk` + tag `alpha` → this branch tip
  - Dex check: `USAGE_GAME` (14); `setPerformanceMode` with const **2** (= POWER_SAVING); `playbackLoop` present

## André — Pad measurement (POWER_SAVING + playback thread)

Package must be `com.limelight.debug`. Do not uninstall Play Store `com.limelight`.

```bash
ADB="C:/Users/andre/AppData/Local/AdbCleanApp/platform-tools/platform-tools/adb.exe"

"$ADB" install -r app-nonRoot-debug-power-saving.apk
# or alpha:
# "$ADB" install -r app-nonRoot-debug-alpha.apk

"$ADB" shell pm path com.limelight.debug
"$ADB" shell pm path com.limelight   # must still exist
```

Session: system EQ on, headphones before connect, same host, no Sunshine/Windows 44100 changes.

After ~15s of audio stream:

```bash
PID=$("$ADB" shell pidof com.limelight.debug | tr -d '\r')
"$ADB" logcat -c
sleep 8
"$ADB" logcat -d --pid="$PID" | tee /tmp/moonlight-power-saving-8s.log
```

Useful greps:

```bash
grep -c "Audio packet queue overflow" /tmp/moonlight-power-saving-8s.log
grep "Network dropped audio data" /tmp/moonlight-power-saving-8s.log
grep -c "underrun due to thread sleep" /tmp/moonlight-power-saving-8s.log
grep -c "underrun due to thread sleep of 1024 frames" /tmp/moonlight-power-saving-8s.log
grep "Audio track configuration" /tmp/moonlight-power-saving-8s.log
grep -i "AudioTrack" /tmp/moonlight-power-saving-8s.log | head
```

Pass only if **all three**:
1. overflow in 8s is **0 or 1**
2. no repeated +29/+30 jumps on `Network dropped audio data`
3. **zero** `"underrun due to thread sleep of 1024 frames"`

**STOP if 1024-frame underrun appears ≥ 10 times.**

## Commits
- `test: force min AudioTrack buffer to check Pad drain` on `cursor/force-min-audio-buffer-bc11` (hypothesis dead; do not merge)
- `fix: use non-blocking AudioTrack write to avoid decode backlog` on `cursor/audio-nonblocking-write-bc11` — FAILED on Pad
- `fix: drain AudioTrack with non-blocking write loop` on `cursor/audio-nonblocking-write-bc11` — FAILED (underrun flood ~12:53)
- `fix: use USAGE_MEDIA for AudioTrack attributes` on `cursor/audio-usage-media-bc11` — FAILED (Pad 13:07)
- Draft PR #2 (USAGE_MEDIA): https://github.com/andrepmoreno/moonlight-android/pull/2 — do not merge
- `fix: move AudioTrack write to dedicated playback thread` on `cursor/audio-playback-thread-bc11` (`8cc177d7`) — FAILED (Pad 13:23)
- Draft PR #3 (playback thread): https://github.com/andrepmoreno/moonlight-android/pull/3 — keep open; do not merge yet
- `fix: request POWER_SAVING AudioTrack performance mode` on `cursor/audio-power-saving-bc11` (`46ac7976c26ae5c463c92f530cd6bda710c08f7a`)
- Draft PR #4 (or compare): https://github.com/andrepmoreno/moonlight-android/compare/master...cursor/audio-power-saving-bc11
  (`gh pr create` / ManagePullRequest unavailable here — 403; open draft via Cursor UI)
- Do **not** merge/update draft PR #1 or #2 for this hypothesis; PR #3 stays as prior step
- Tip + tag `alpha` → ``
- Alpha download: https://github.com/andrepmoreno/moonlight-android/raw/alpha/releases/app-nonRoot-debug-alpha.apk
- Artifact: `/opt/cursor/artifacts/app-nonRoot-debug-power-saving.apk`
