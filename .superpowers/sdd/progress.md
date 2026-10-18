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

- **Task 5: IN PROGRESS — USAGE_MEDIA (AudioAttributes)**
  - Branch: `cursor/audio-usage-media-bc11` (clean from `b48494cb` / v12.2)
  - **Only** intentional functional change: `createAudioTrack` uses
    `.setUsage(AudioAttributes.USAGE_MEDIA)` instead of `USAGE_GAME`
  - `playDecodedAudio` = original v12.2 (blocking `track.write` + `getPendingAudioDuration() < 40` gate)
  - No getMinBufferSize force shortcut, no bufferSize/performance/channel/sample-rate changes
  - No moonlight-common-c / Sunshine / per-frame logging
  - APK artifact: `/opt/cursor/artifacts/app-nonRoot-debug-usage-media.apk`
  - Alpha: `releases/app-nonRoot-debug-alpha.apk` + tag `alpha` retargeted to this commit

## André — Pad measurement (USAGE_MEDIA)

Package must be `com.limelight.debug`. Do not uninstall Play Store `com.limelight`.

```bash
ADB="C:/Users/andre/AppData/Local/AdbCleanApp/platform-tools/platform-tools/adb.exe"

"$ADB" install -r app-nonRoot-debug-usage-media.apk
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
"$ADB" logcat -d --pid="$PID" | tee /tmp/moonlight-usage-media-8s.log
```

Useful greps:

```bash
grep -c "Audio packet queue overflow" /tmp/moonlight-usage-media-8s.log
grep "Network dropped audio data" /tmp/moonlight-usage-media-8s.log
grep -c "underrun due to thread sleep" /tmp/moonlight-usage-media-8s.log
grep "Audio track configuration" /tmp/moonlight-usage-media-8s.log
grep -i "AudioTrack" /tmp/moonlight-usage-media-8s.log | head
```

Pass only if **all three**:
1. overflow in 8s is **0 or 1**
2. no repeated +29/+30 jumps on `Network dropped audio data`
3. "underrun due to thread sleep" for that session is **0 or 1**

**If underrun still ≥ 10: STOP — no further patch.**

## Commits
- `test: force min AudioTrack buffer to check Pad drain` on `cursor/force-min-audio-buffer-bc11` (hypothesis dead; do not merge)
- `fix: use non-blocking AudioTrack write to avoid decode backlog` on `cursor/audio-nonblocking-write-bc11` — FAILED on Pad
- `fix: drain AudioTrack with non-blocking write loop` on `cursor/audio-nonblocking-write-bc11` — FAILED (underrun flood ~12:53)
- `fix: use USAGE_MEDIA for AudioTrack attributes` on `cursor/audio-usage-media-bc11` (`3a00617d`, from clean v12.2)
- `release: publish USAGE_MEDIA APK as alpha` (`a7d4aacd`); tip + tag `alpha` → `c00aa061`
- Draft PR: **needs create** for `cursor/audio-usage-media-bc11` → `master` (ManagePullRequest unavailable here; `gh pr create` denied). Compare: https://github.com/andrepmoreno/moonlight-android/compare/master...cursor/audio-usage-media-bc11
- Do **not** merge/update draft PR #1 (non-blocking write) for this hypothesis
- Alpha download: https://github.com/andrepmoreno/moonlight-android/raw/alpha/releases/app-nonRoot-debug-alpha.apk
- Artifact: `/opt/cursor/artifacts/app-nonRoot-debug-usage-media.apk`
