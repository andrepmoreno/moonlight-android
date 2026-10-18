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

- **Task 6: IN PROGRESS — dedicated playback thread**
  - Branch: `cursor/audio-playback-thread-bc11` (clean from `b48494cb` / v12.2)
  - Single file: `AndroidAudioRenderer.java`
  - 1s `short[]` ring + `scratch` allocated in `setup()`; playback `Thread` started in `start()`,
    stopped at beginning of `cleanup()` before `track.pause()`; skip `release()` if join fails
  - `playDecodedAudio`: copy into ring or discard — **no** `track.write`, **no** `getPendingAudioDuration`
  - Playback thread: pull → blocking 3-arg `track.write(scratch, 0, n)` (no WRITE_NON_BLOCKING)
  - No bufferSize / performance / write-mode changes; no moonlight-common-c / Sunshine
  - Fix commit: `8cc177d7`
  - APK artifact: `/opt/cursor/artifacts/app-nonRoot-debug-playback-thread.apk`
  - Alpha: `releases/app-nonRoot-debug-alpha.apk` + tag `alpha` retargeted to this branch tip

## André — Pad measurement (playback thread)

Package must be `com.limelight.debug`. Do not uninstall Play Store `com.limelight`.

```bash
ADB="C:/Users/andre/AppData/Local/AdbCleanApp/platform-tools/platform-tools/adb.exe"

"$ADB" install -r app-nonRoot-debug-playback-thread.apk
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
"$ADB" logcat -d --pid="$PID" | tee /tmp/moonlight-playback-thread-8s.log
```

Useful greps:

```bash
grep -c "Audio packet queue overflow" /tmp/moonlight-playback-thread-8s.log
grep "Network dropped audio data" /tmp/moonlight-playback-thread-8s.log
grep -c "underrun due to thread sleep" /tmp/moonlight-playback-thread-8s.log
grep "Audio track configuration" /tmp/moonlight-playback-thread-8s.log
grep -i "AudioTrack" /tmp/moonlight-playback-thread-8s.log | head
```

Pass only if **all three**:
1. overflow in 8s is **0 or 1**
2. no repeated +29/+30 jumps on `Network dropped audio data`
3. "underrun due to thread sleep" for that session is **0 or 1**

**STOP if overflow ≥ 10.**

## Commits
- `test: force min AudioTrack buffer to check Pad drain` on `cursor/force-min-audio-buffer-bc11` (hypothesis dead; do not merge)
- `fix: use non-blocking AudioTrack write to avoid decode backlog` on `cursor/audio-nonblocking-write-bc11` — FAILED on Pad
- `fix: drain AudioTrack with non-blocking write loop` on `cursor/audio-nonblocking-write-bc11` — FAILED (underrun flood ~12:53)
- `fix: use USAGE_MEDIA for AudioTrack attributes` on `cursor/audio-usage-media-bc11` — FAILED (Pad 13:07)
- Draft PR #2 (USAGE_MEDIA): https://github.com/andrepmoreno/moonlight-android/pull/2 — do not merge
- `fix: move AudioTrack write to dedicated playback thread` on `cursor/audio-playback-thread-bc11` (`8cc177d7`, from clean v12.2)
- Compare / open draft PR: https://github.com/andrepmoreno/moonlight-android/compare/master...cursor/audio-playback-thread-bc11
  (`gh pr create` / ManagePullRequest unavailable here — 403)
- Do **not** merge/update draft PR #1 (non-blocking write) or #2 (USAGE_MEDIA) for this hypothesis
- Tip + tag `alpha` → `4697339850ad474022014bb3c73de1d54e677208`
- Alpha download: https://github.com/andrepmoreno/moonlight-android/raw/alpha/releases/app-nonRoot-debug-alpha.apk
- Artifact: `/opt/cursor/artifacts/app-nonRoot-debug-playback-thread.apk`
