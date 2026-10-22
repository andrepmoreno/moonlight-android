# Logcat analysis — Redmi Pad 2 (André)

**Date:** 2026-10-22  
**Source file:** `moonlight-pad-logcat_7f69.txt` (523 lines, pid `16220` = `com.limelight.debug`)  
**Capture window:** `10-22 12:56:18.903` → `10-22 12:56:28.315` (**9.412 s**)  
**Goal:** Relate remaining audio cortes to underrun vs overflow; fingerprint beta-0.1 vs beta-0.2 vs older.

---

## Verdict (this capture)

| Signal | Count / value | Meaning |
|--------|---------------|---------|
| `Audio track configuration:` | **0** (absent) | Capture started **mid-stream**; buffer size / `lowLatency` **unknown** — cannot confirm 1920 vs >1920 |
| `Audio packet queue overflow` | **282** (~**30/s**) | Native LBQ bound hit continuously; decode not draining |
| `Network dropped audio data` | **69** events | Sequence gaps at decode; **not** flush-sized |
| Jump sizes (`recv − expected`) | **+1…+12 only**; **0× +29/+30** | Drop-on-bound path (no `LbqFlushQueueItems`) |
| Packets missing (sum of jumps) | **281** ≈ overflows | Gaps ≈ packets dropped at queue, not Wi‑Fi FEC story |
| `Too much pending audio data` | **0** | Pending gate not in play |
| `underrun due to thread sleep of 1024` | **0** in file | **Inconclusive** — log is `--pid` filtered; AudioFlinger is another process |
| `write muted data to hw` | **0** | Same caveat |
| `Audio write short` | **0** | Not the failed non-blocking-write APK |
| LimeLog audio lines | **0** | No config / short / pending LimeLogs in window |
| `AudioTrackImpl` fine status | 1× `sessionId 3377 sr 48000 ch 2` `5s(f:5000 m:0…)` | Client track reports fine for 5s (no client-side mute flag) |

**Cortes correlate with overflows (drop-on-bound discontinuities), not with visible underruns in this file.**  
~30 drops/s ≈ ~150 ms of audio discarded per second → choppy / cut audio even without mixer mute holes.

---

## Counters (exact)

```
Audio track configuration:              0
Audio packet queue overflow:          282
Network dropped audio data:            69
  jump histogram: +1:35 +2:4 +3:2 +4:2 +5:1 +6:1 +7:4 +8:7 +9:6 +10:3 +11:3 +12:1
  +29 / +30:                            0
Too much pending audio data:            0
underrun due to thread sleep of 1024:   0  (pid-filtered — see caveats)
write muted data to hw:                 0  (pid-filtered)
Audio write short:                      0
LimeLog:                                0
```

### Per-second overflow / netdrop

| Second (12:56) | overflow | netdrop |
|----------------|----------|---------|
| :18 | 9 | 3 |
| :19 | 22 | 10 |
| :20 | 20 | 5 |
| :21 | 22 | 6 |
| :22 | 31 | 6 |
| :23 | 32 | 7 |
| :24 | 40 | 7 |
| :25 | 36 | 7 |
| :26 | 20 | 8 |
| :27 | 30 | 7 |
| :28 | 20 | 3 |

### Other moonlight / audio lines

- Package confirmed via MediaCodec: `Client:com.limelight.debug`, HEVC `c2.mtk.hevc.decoder`
- Video side also stressed: repeated `AV Sync Warning` ~100–120 ms; MediaCodec render intervals ~102–116 ms
- One `AudioTrackImpl [audioTrackData][fine]` line (session 3377)

---

## APK fingerprint

| Build family | Expected log signature | Match? |
|--------------|------------------------|--------|
| **Older (flush on bound)** | overflows + **always +29/+30** jumps | **No** (0× +29/+30) |
| **Failed non-blocking write** | `Audio write short: wrote N of 480…` | **No** |
| **drop-on-bound** (in beta-0.1 **and** beta-0.2 lineage) | overflows still logged; jumps **small**; no flush | **Yes** |
| **beta-0.1** (drop-on-bound + setpriority; FX still small ~1920) | same native signature; config ~**1920** with EQ | **Possible** — config missing |
| **beta-0.2** (PR FX min-buffer) | config **>1920** with EQ (e.g. prior ~8640) | **Unknown** — need config line |

**Best match:** drop-on-bound-era APK (**beta-0.1 or beta-0.2**), **not** stock flush / not non-blocking-write failure.  
Overflow rate here (~30/s) is **worse** than earlier Pad baselines (~19–22 / 8s) — queue stays saturated; every new packet dropped once full.

---

## Correlation: underrun vs overflow

1. **Overflow path (evidenced):** LBQ full → drop new packet (drop-on-bound) → decode later sees seq gap → `Network dropped…` with small jump. Sum(gaps)≈overflows. Audible as discontinuity / corte.
2. **Underrun / 21 ms mute path (not evidenced here):** would show AudioFlinger `underrun due to thread sleep of 1024 frames` + `write muted data to hw`. Absent from this **pid-only** dump — cannot rule out; need tagged AudioFlinger capture.
3. **FX small buffer (~1920)** is the historical root that makes the mixer starve and the LBQ back up. **This file cannot prove buffer size.**

---

## Caveats (capture quality)

1. Mid-session `--pid` dump → **no** `Audio track configuration:` (logged once at track create).
2. No `AudioFlinger_Threads` / system audio tags → underrun/mute counts are **not trustworthy zeros**.
3. No `dumpsys` `lastUpdateTime` / APK hash → cannot distinguish beta-0.1 vs 0.2 by versionCode (both 315 / 12.2).

---

## Next step (ONE)

**Install / confirm `app-nonRoot-debug-beta-0.2.apk` (FX min-buffer), then re-capture from stream *connect* (not mid-session):**

1. `dumpsys package com.limelight.debug` → `lastUpdateTime` after install  
2. Fresh logcat **including connect**, plus a second dump with tags:
   - `AudioFlinger_Threads:I AudioTrack:I LimeLog:I moonlight:I`
3. Must see: `Audio track configuration: <size> <lowLatency>`
   - If **still ~1920** with system EQ on → APK not actually 0.2 / FX path not taken → **fix install**
   - If **>1920** (e.g. ~8640) and overflows still ≫20/8s → buffer fix alone insufficient; next diagnosis is drain/scheduling (not another buffer tweak), with AudioFlinger underrun counts in hand

**No code push from this analysis** — evidence says overflow-driven cortes under drop-on-bound; buffer identity is the missing gate before another fix.

### Windows re-capture (minimal)

```bat
set ADB=C:\Users\andre\AppData\Local\AdbCleanApp\platform-tools\platform-tools\adb.exe
"%ADB%" install -r app-nonRoot-debug-beta-0.2.apk
"%ADB%" shell dumpsys package com.limelight.debug | findstr /i "versionName versionCode lastUpdateTime"
"%ADB%" logcat -c
REM start stream with headphones + EQ, wait ~10s from connect
for /f %%i in ('"%ADB%" shell pidof com.limelight.debug') do set PID=%%i
"%ADB%" logcat -d --pid=%PID% > %USERPROFILE%\Desktop\moonlight-pad-logcat.txt
"%ADB%" logcat -d -s AudioFlinger_Threads:I AudioTrack:I LimeLog:I moonlight:I > %USERPROFILE%\Desktop\moonlight-pad-audio-tags.txt
```

sha256 beta-0.2 (repo): `9523bf3e10f002cfa16de9b3188f2e20d4fbd8e9ea12f3ff539d85f71fe77e2f`

---

## Prior note (superseded for this file)

Earlier section documented cloud ADB unreachable — still true for live device from VM. This section analyzes André’s uploaded `moonlight-pad-logcat_7f69.txt` instead.
