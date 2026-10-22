# Logcat analysis — Redmi Pad 2 (André)

**Date:** 2026-10-22  
**Cloud agent:** ADB probe from VM (no USB / no LAN path to Pad)  
**Goal:** Capture ~8–15s logcat for `com.limelight.debug` + AudioFlinger while audio “cortes” reproduce; relate to beta-0.1 (master) vs beta-0.2 (PR #9 FX min-buffer).

---

## Verdict: device NOT connected

| Check | Result |
|--------|--------|
| `adb` binary | Present: `/home/ubuntu/Android/Sdk/platform-tools/adb` (1.0.41 / 37.0.1) |
| `adb devices -l` | **Empty** (daemon started on `127.0.0.1:5037`) |
| USB (`/dev/bus/usb`) | **Absent** — cloud VM has no USB passthrough |
| Wireless / mDNS | `adb mdns services` → **no services**; `adb connect 127.0.0.1:5555` → connection refused |
| Env `ADB_*` / known Pad IP | **None** |
| Prior logcat under `/tmp` | **None** for this run |

**What is missing for cloud ADB**

1. Either USB forwarding of the Redmi Pad 2 into this VM (not available), **or**
2. Wireless ADB: Pad and VM must share a reachable network; VM is on private `172.30.0.2` / Docker `172.17.0.1` — **not** André’s home LAN, so `adb connect <pad-ip>:5555` from here cannot work unless he tunnels/port-forwards.

**Conclusion:** Capture must be done on André’s Windows machine (path from plan), then paste counters or upload the log file.

No new APK / code change from this session — analysis first; no device evidence for a one-line fix.

---

## APK identity (what to check on Pad)

Local artifacts all share the **same** AndroidManifest version fields (cannot distinguish by versionCode alone):

| Artifact | package | versionCode | versionName | notes |
|----------|---------|-------------|-------------|--------|
| `releases/app-nonRoot-debug-beta-0.1.apk` | `com.limelight.debug` | **315** | **12.2** | master tip / prior beta |
| `releases/app-nonRoot-debug-beta-0.2.apk` | `com.limelight.debug` | **315** | **12.2** | PR #9 — FX min-buffer (`cursor/audio-fx-min-buffer-bc11`) |
| `releases/app-nonRoot-debug-alpha.apk` | `com.limelight.debug` | **315** | **12.2** | currently same build as beta-0.2 tip in tree |

**On device, use:**

```text
adb shell dumpsys package com.limelight.debug | findstr /i "versionName versionCode lastUpdateTime"
```

Also confirm **buffer size** in logcat (beta-0.2 goal with EQ on: buffer **> 1920**, not ~1920 / ~10 ms):

```text
Audio track configuration:
```

| Expected signal | beta-0.1 (EQ on) | beta-0.2 (EQ on) |
|-----------------|------------------|------------------|
| Small buffer ~`bytesPerFrame*2` (~1920) | likely | should **not** |
| `getMinBufferSize` path (larger, e.g. prior evidence ~8640) | only on large attempts | intended for FX-on small attempts too |
| Underrun `thread sleep of 1024 frames` (~21 ms mute) | was the remaining ~2% bug | pass bar: **0–1** in 8 s; fail if **> 10** |

sha256 beta-0.2: `9523bf3e10f002cfa16de9b3188f2e20d4fbd8e9ea12f3ff539d85f71fe77e2f`

---

## Counters to report (from prior Pad campaigns — baseline)

These are **historical** Pad numbers (not from this run). Use the same greps on a fresh capture:

| Pattern | Meaning | Prior bad range (~8 s) |
|---------|---------|-------------------------|
| `Audio track configuration` | buffer size + lowLatency flag | e.g. `8640 false` or small ~1920 |
| `Audio packet queue overflow` | native LBQ hit bound 30 | ~19–22 / 8 s |
| `Network dropped` / `expected.*received` / `+29`/`+30` | flush jump after bound | always +29/+30 when flushing |
| `Too much pending audio data` | Java pending gate (older builds) | n/a if gate removed |
| `underrun due to thread sleep of 1024` | mixer hole ≈ 21 ms → **corte** | 0 → 164 depending on experiment |
| `write muted data to hw` | AudioFlinger muted fill | tracks underruns |
| `Audio write short` | non-blocking write discarded samples | Task 3 failure |

Also: `adb shell ps -T -p $PID` → check **AudioDec** / **AudioRecv** nice (NI) if setpriority builds are installed.

---

## Exact Windows commands (André)

Use his ADB (adjust if path differs):

```bat
set ADB=C:\Users\andre\AppData\Local\AdbCleanApp\platform-tools\platform-tools\adb.exe

"%ADB%" devices -l
"%ADB%" shell pm path com.limelight.debug
"%ADB%" shell dumpsys package com.limelight.debug | findstr /i "versionName versionCode lastUpdateTime"
```

**Optional wireless** (same Wi‑Fi as PC; enable Wireless debugging on Pad):

```bat
"%ADB%" pair <PAD_IP>:<PAIR_PORT>
"%ADB%" connect <PAD_IP>:<CONNECT_PORT>
"%ADB%" devices -l
```

**Capture while streaming (~10 s of audio with headphones + system EQ as usual):**

```bat
for /f %%i in ('"%ADB%" shell pidof com.limelight.debug') do set PID=%%i
echo PID=%PID%
"%ADB%" shell ps -T -p %PID%
"%ADB%" logcat -c
timeout /t 10 /nobreak
"%ADB%" logcat -d --pid=%PID% > %USERPROFILE%\Desktop\moonlight-pad-logcat.txt
"%ADB%" logcat -d -s AudioFlinger_Threads:I AudioTrack:I LimeLog:I moonlight:I > %USERPROFILE%\Desktop\moonlight-pad-audio-tags.txt
```

**PowerShell greps (paste counts back):**

```powershell
$f = "$env:USERPROFILE\Desktop\moonlight-pad-logcat.txt"
Select-String -Path $f -Pattern "Audio track configuration"
@(
  "Audio packet queue overflow",
  "Network dropped",
  "expected",
  "Too much pending audio data",
  "underrun due to thread sleep of 1024",
  "write muted data to hw",
  "Audio write short",
  "\+29",
  "\+30"
) | ForEach-Object {
  $p = $_; $c = (Select-String -Path $f -Pattern $p).Count
  "{0,5}  {1}" -f $c, $p
}
```

Or zip/paste `moonlight-pad-logcat.txt` + `moonlight-pad-audio-tags.txt` + the `dumpsys` / `ps -T` output.

---

## Correlation hypothesis (for when logs arrive)

André reports **cortes** (cuts / mute gaps), not necessarily crackle.

- **~21 ms mute gaps** correlate with `AudioFlinger_Threads` **`underrun due to thread sleep of 1024 frames`** (+ often `write muted data to hw`). That was the remaining Pad bug with EQ on and a too-small FX track buffer (~1920).
- **Crackle / discontinuity** historically correlated with **`Audio packet queue overflow`** + **`expected N, received N+29/+30`** (queue flush). Drop-on-bound aimed at that path; FX min-buffer (beta-0.2) aims at the underrun/mute path.
- If beta-0.2 is installed and buffer is still ~1920 with EQ on → install/build mismatch or FX path not taken.
- If buffer is large but underruns remain high → do **not** stack another buffer tweak; re-open mixer/scheduling diagnosis.

---

## Next step (one)

**André:** on Windows, run the capture block above during ~10 s of streaming with the APK he thinks is installed, then paste:

1. `dumpsys` versionName / versionCode / **lastUpdateTime**  
2. the counter table from PowerShell  
3. the `Audio track configuration:` line  

Cloud agent will re-analyze; **no code push** until those counters show which failure mode the cortes match.
