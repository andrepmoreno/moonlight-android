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
  - Buffer-size force-min shortcut is **reverted / not carried** into the fix branch

- **Task 3: FAILED — single non-blocking write (no loop)**
  - Branch: `cursor/audio-nonblocking-write-bc11`
  - Evidence (failed APK ~12:43): `Audio write short: wrote 48 of 480, pending 120 ms`
  - Frame = 480 samples @ 48kHz = 5ms; only 48 samples (~1ms) written, rest discarded
  - Native 30-packet queue not drained → **20 overflows / 8s** with +29/+30 jumps = crackle

- **Task 4: IN PROGRESS — non-blocking write LOOP**
  - Same branch: `cursor/audio-nonblocking-write-bc11` (updates draft PR #1)
  - Change: `playDecodedAudio` only — loop `WRITE_NON_BLOCKING` until frame drained or `wrote <= 0`
  - API 23+ uses `WRITE_NON_BLOCKING`; below M uses blocking `write(offset, length)` in the same loop
  - Removed rate-limited `Audio write short` log and `lastShortWriteLogMs`
  - No `getPendingAudioDuration`, no pre-write discard, `setup()` untouched
  - No moonlight-common-c / Sunshine / AudioTrack recreate / DROP_LIMIT / 40ms changes
  - APK artifact: `/opt/cursor/artifacts/app-nonRoot-debug-nonblocking-loop.apk`
  - Alpha: `releases/app-nonRoot-debug-alpha.apk` + tag `alpha` retargeted to this commit

## André — Pad measurement (non-blocking write LOOP)

Package must be `com.limelight.debug`. Do not uninstall Play Store `com.limelight`.

```bash
ADB="C:/Users/andre/AppData/Local/AdbCleanApp/platform-tools/platform-tools/adb.exe"

"$ADB" install -r app-nonRoot-debug-nonblocking-loop.apk
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
"$ADB" logcat -d --pid="$PID" | tee /tmp/moonlight-nonblocking-loop-8s.log
```

Useful greps:

```bash
grep -c "Audio packet queue overflow" /tmp/moonlight-nonblocking-loop-8s.log
grep "Network dropped audio data" /tmp/moonlight-nonblocking-loop-8s.log
grep "Audio write short" /tmp/moonlight-nonblocking-loop-8s.log   # should be gone
grep "Audio track configuration" /tmp/moonlight-nonblocking-loop-8s.log
grep -i "AudioTrack" /tmp/moonlight-nonblocking-loop-8s.log | head
```

Pass only if:
1. overflow in 8s is **0 or 1**
2. no repeated +29/+30 jumps on `Network dropped audio data`
3. AudioTrackImpl fine, amplitude > 0

**If overflow still ≥ 10: STOP — no further patch.**

## Commits
- `test: force min AudioTrack buffer to check Pad drain` on `cursor/force-min-audio-buffer-bc11` (hypothesis dead; do not merge)
- `fix: use non-blocking AudioTrack write to avoid decode backlog` on `cursor/audio-nonblocking-write-bc11` (`ddb3b236`) — FAILED on Pad
- `fix: drain AudioTrack with non-blocking write loop` on `cursor/audio-nonblocking-write-bc11` (`be0eab83`)
- Draft PR #1: https://github.com/andrepmoreno/moonlight-android/pull/1 (head updated via push; ManagePullRequest unavailable in this session — body still describes single-shot write)
- Alpha download: https://github.com/andrepmoreno/moonlight-android/raw/alpha/releases/app-nonRoot-debug-alpha.apk
- Artifact: `/opt/cursor/artifacts/app-nonRoot-debug-nonblocking-loop.apk`
