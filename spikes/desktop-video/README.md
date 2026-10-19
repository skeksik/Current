# Desktop video spike (candidate 2)

A throwaway prototype for the M1 "Desktop video" spike (docs/roadmap.md). The app does not use it, and it is deleted
before the spike PR, which carries only ADR 0010 and the docs/platform-notes.md updates.

**What it is.** libmpv renders into a native child window (`wid`) inside a Compose window. Our own Compose controls
live in a second, undecorated and transparent window that is owned by the video window and follows it. Candidate 1
(libmpv drawn into Compose as a GPU texture, as MediaMP does) was not built: it renders into an 8-bit SDR texture, so
it cannot output HDR10.

**libmpv.** The `libmpv-2.dll` comes, with its FFmpeg and other DLLs, from MediaMP's
`mediamp-mpv-runtime-windows-x64` 0.5.2 jar. That is an LGPL-2.1+ build: mpv 0.41.0 with `-Dgpl=false`, and FFmpeg
without `--enable-gpl` or `--enable-version3`. The prototype uses only the DLLs, none of MediaMP's code. That build has
`-Dwin32-smtc=disabled`, so libmpv's Windows media controls are not compiled in (see "Media keys" below).

## Run it (Windows 10/11 x64)
Prerequisites: JDK 21 (as in CI; for example Temurin 21) on `PATH` or in `JAVA_HOME`, current GPU drivers.
For HDR: an HDR display and Settings > System > Display > "Use HDR" switched on.

```powershell
git fetch origin spike/desktop-video
git switch spike/desktop-video
.\gradlew.bat :spikes:desktop-video:run
```
The first run downloads the runtime jar (about 22 MB) and extracts the DLLs to `spikes\desktop-video\build\mpv`.
To try another libmpv build, point at a folder holding its `libmpv-2.dll`:
`.\gradlew.bat :spikes:desktop-video:run -PmpvDir=C:\path\to\folder`.

In the launcher, paste a local path (or use Browse…) or a Jellyfin direct-play URL, pick the options, then Play.
Closing the player window returns to the launcher.

**Skipping the launcher.** Every `-Pspike.<name>=<value>` becomes a system property; `spike.source` starts playing
right away. Names: `source`, `hwdec`, `vo`, `gpuApi` (`d3d11`, `opengl`, `auto`), `hdr`, `mediaControls`, `embed`,
`controlsWindow`, `stats`, `selfTest` (`true`/`false`) and `mpvOptions` (`name=value,name=value`). In PowerShell, quote
each `-P` argument: unquoted, `-Pspike.selfTest=true` is split at the dot. Example:
```powershell
.\gradlew.bat :spikes:desktop-video:run "-Pspike.source=D:\Videos\test.mkv" "-Pspike.selfTest=true"
```
`-PskikoRenderApi=OPENGL` (or `SOFTWARE`) switches Compose itself off Direct3D 12.

**If it crashes.** Everything lands in `spikes\desktop-video\build\spike-out\`:
- `events-<time>.log`: what the prototype did, flushed line by line (also printed to the console).
- `crash-<time>.txt`: the stack trace of a Java exception.
- `hs_err_pid<pid>.log`: the JVM's report of a native crash (in libmpv, a driver or Skia).
- `mpv-<time>.log`: mpv's own log (verbose).

The launcher's isolation switches narrow the cause down: "Embed video in our window" off lets mpv open its own window,
"Controls window" off drops the transparent window, "Stats" off stops sampling, and gpu-api `opengl` with vo `gpu`
avoids mpv's Direct3D 11 path.

**Self-test.** "Self-test, then exit" (or `-Pspike.selfTest=true`) runs a script: wait for playback, resize five
times, move, maximize, fullscreen, switch every audio and subtitle track, seek, hide the controls window, pause and
resume, then close. Each step goes to the events log with a screenshot (`selftest-*.png`), and the process exits with
0 (pass), 2 (a step failed), 3 (an exception) or 4 (hung). It cannot move the window to another monitor; do that by
hand. The workflow `.github/workflows/spike-desktop-video.yml` runs it on a GitHub Windows runner (no GPU: Direct3D
there is Microsoft's software renderer).

**Jellyfin direct-play URL.** Create an API key for the test (Dashboard > API Keys) and delete it afterwards. Take
the item id from the web UI's details page URL (`...details?id=<itemId>`) and the media source id from
`MediaSources[0].Id` in `GET /Items/<itemId>`:
```
https://<server>/Videos/<itemId>/stream?static=true&mediaSourceId=<mediaSourceId>&deviceId=spike-desktop-video&ApiKey=<key>
```
The launcher masks the URL; the logs, CSV and overlay replace `ApiKey=` values with `***`.

**Controls.** Click: play/pause. Double-click: fullscreen. Bottom bar: play/pause, -10 s/+30 s, seek bar, Audio and
Subtitles menus, Stats, Fullscreen. Keys: Space or K play/pause, ← → seek 5 s, F fullscreen, Esc leave fullscreen,
A next audio track, S next subtitle (then off), I stats overlay, H "hide the controls window while idle" (see below).

**Output.** `spikes\desktop-video\build\spike-out\`: the files above plus `stats-<time>.csv` (one row per second while
playing). Please do not commit these. They hold local file paths.

## Test media
Your own files are the best test. Ideally:
- 1080p60 HEVC (8- or 10-bit) and 4K60 HEVC Main10 HDR10, at real bitrates. Synthetic clips compress far better
  than real video, so they are no load test.
- An MKV with styled ASS subtitles (positioning, rotation, fonts), and a Blu-ray remux with PGS subtitles.
- A file with several audio tracks (for example AC-3, E-AC-3, DTS, TrueHD).

## What to measure
For each run, note the launcher options (defaults: hwdec auto-safe, vo gpu-next, HDR passthrough on), Windows HDR on
or off, the display (resolution, refresh rate), the GPU, and whether the machine is a laptop with two GPUs.
Let each video play for at least 2 minutes before reading numbers. Task Manager > Performance > GPU ("3D" and
"Video Decode" graphs) is the reference for GPU load; the overlay's GPU rows are an approximation from the same
counters (they may show "unavailable" on a non-English Windows).

| # | Case | What to read |
|---|------|--------------|
| 1 | 1080p60 HEVC, windowed and fullscreen | overlay `hwdec` (expect `d3d11va`), `drops (vo/dec)` after 2 min, `delayed/mistimed`, `cpu % proc/sys`, GPU 3D and Video Decode (Task Manager) |
| 2 | 4K60 HEVC 10-bit, windowed and fullscreen | same as 1; optionally once with hwdec `no` to see the software-decoding cost |
| 3 | HDR10, Windows HDR **on** | overlay `output` row: `bt.2020 / pq` means HDR passthrough; does the picture look right (not washed out or dim)? Windows HDR **off**: `output` should be SDR (`bt.709`), tone-mapped, still looking reasonable |
| 4 | ASS and PGS subtitles | ASS styles, positions and rotation drawn correctly; PGS shown at the right size and place; switching via the menu and S; `last ms … sub` |
| 5 | Audio track switching | audible gap or glitch, A/V sync afterwards (`avsync`), `last ms … aud`; note the codecs switched between |
| 6 | Windows media keys | expected **not** to work with this DLL (`win32-smtc` row shows `disabled`). Check whether keyboard media keys do anything, and whether Windows' media flyout shows the app |
| 7 | Resizing | drag edges and corners, maximize/restore, move between monitors (mixed DPI if you have it): does the controls window lag behind or misalign? Black or stale frames? Drops while resizing? |
| 8 | Fullscreen | F, double-click, Esc: does the video fill the screen with the controls on top? Taskbar hidden? Drops at the switch? |
| 9 | Two-window behaviour | clicking the controls makes the video window's title bar look inactive? Alt+Tab shows one window or two? Minimize/restore keeps the controls aligned? |
| 10 | H mode (controls window hidden while idle) | after 3 s idle the controls window disappears: does moving the mouse over the video bring it back (whether mouse input reaches our canvas through mpv's child window)? Do GPU 3D load or drops change with nothing above the video? |
| 11 | Jellyfin direct play | starts, seeks (`last ms seek`), no buffering at the file's bitrate |

Report the numbers in the chat (or send the CSV/log files privately); they go into ADR 0010.

## Headless smoke test (any OS with a system libmpv)
`./gradlew :spikes:desktop-video:test -Pspike.testMedia=/path/to/file.mkv` plays a file with one video, two audio
and one subtitle track with `vo=null`/`ao=null`. It checks the binding, track switching, seeking and the stats CSV.
Without `-Pspike.testMedia` it does nothing.
