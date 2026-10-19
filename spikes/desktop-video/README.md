# Desktop video spike

A throwaway prototype for the M1 "Desktop video" spike (docs/roadmap.md). The app does not use it, and it is deleted
before the spike PR, which carries only ADR 0010 and the docs/platform-notes.md updates.

Two engines, picked in the launcher (`engine`) or with `-Pspike.engine=...`:
- **`window` (candidate 2, the default):** libmpv renders into a native child window (`wid`) inside a Compose window;
  our Compose controls live in a second, undecorated, transparent window owned by the video window that follows it.
- **`texture` (candidate 1, measured briefly):** MediaMP (`org.openani.mediamp:mediamp-mpv`) renders libmpv into D3D11
  textures that Compose's own Direct3D 12 renderer draws, so the controls are ordinary Compose content in the same
  window. Its output is 8-bit SDR (HDR is tone-mapped), which rules it out (ADR 0010).

**libmpv.** Both use the same `libmpv-2.dll` and DLLs from MediaMP's `mediamp-mpv-runtime-windows-x64` 0.5.2 jar,
extracted to `spikes\desktop-video\build\mpv`: mpv 0.41.0 built with `-Dgpl=false` and FFmpeg 8.0.1 reporting "LGPL
version 2.1 or later". That build has `-Dwin32-smtc=disabled` (no Windows media controls) and a trimmed FFmpeg.

## Run it (Windows 10/11 x64)
Prerequisites: a JDK 21 on `PATH` or in `JAVA_HOME` (Gradle provisions its own toolchain JDK), current GPU drivers.
For HDR: an HDR display with Settings > System > Display > "Use HDR" on.

```powershell
git fetch origin spike/desktop-video
git switch spike/desktop-video
.\gradlew.bat :spikes:desktop-video:run
```
The first run downloads MediaMP's jars (about 25 MB) and extracts the DLLs. In the launcher, paste a local path (or
Browse…) or a Jellyfin direct-play URL, pick the options, then Play. Closing the player returns to the launcher.

**Test media.** `.\spikes\desktop-video\make-test-media.ps1` (needs `winget install Gyan.FFmpeg`, used only as a local
tool) writes 90 s synthetic files to `spikes\desktop-video\build\test-media`: HEVC 1080p60 and 4K60, SDR (8-bit) and
HDR10 (Main10, PQ, BT.2020, mastering metadata), each with three audio tracks (AAC 2.0, AC-3 5.1, E-AC-3 2.0) and a
styled ASS track. They prove decoding and presentation at 60 fps; real files (PGS, film ASS, TrueHD, Dolby Vision)
come from your library.

**Skipping the launcher.** Every `-Pspike.<name>=<value>` becomes a system property; a source starts playing right
away. The source can also come from the environment variable `SPIKE_SOURCE`, which keeps a URL with an API key out
of Gradle's caches. In PowerShell, quote each `-P` argument (unquoted, `-Pspike.selfTest=true` is split at the dot):
```powershell
$env:SPIKE_SOURCE = "$PWD\spikes\desktop-video\build\test-media\hevc-2160p60-hdr10.mkv"
.\gradlew.bat :spikes:desktop-video:run "-Pspike.fullscreen=borderless"
.\gradlew.bat :spikes:desktop-video:run "-Pspike.engine=texture"
.\gradlew.bat :spikes:desktop-video:run "-Pspike.selfTest=true"
Remove-Item Env:SPIKE_SOURCE
```
Names: `source`, `engine` (`window`, `texture`), `fullscreen` (`compose`, `compose-fix`, `borderless`), `hwdec`, `vo`,
`gpuApi` (`d3d11`, `opengl`, `auto`), `hdr`, `mediaControls`, `embed`, `controlsWindow`, `stats`, `selfTest`
(`true`/`false`) and `mpvOptions` (`name=value,name=value`, e.g. `start=40%,sid=1`). `-PmpvDir=C:\path` loads another
`libmpv-2.dll` (for example one built with `win32-smtc`); `-PskikoRenderApi=OPENGL` moves Compose off Direct3D 12.

**Fullscreen modes** (`spike.fullscreen`): `borderless` (the default) strips the frame from the same HWND with Win32
and covers the monitor; `compose` is Compose's `WindowPlacement.Fullscreen` (AWT's exclusive full-screen mode);
`compose-fix` applies the frame's bounds again after leaving it. With `compose` at a display scale above 100 %, the window comes
back from fullscreen at the right size in logical pixels applied as physical ones, so the video is clipped and the
controls window sticks out (ADR 0010).

**Jellyfin direct-play URL.** Create an API key for the test (Dashboard > API Keys) and delete it afterwards. Item id:
the web UI's details page (`...details?id=<itemId>`); media source id: `MediaSources[0].Id` in `GET /Items/<itemId>`.
```
https://<server>/Videos/<itemId>/stream.mkv?static=true&mediaSourceId=<mediaSourceId>&ApiKey=<key>
```
The launcher masks the URL; logs, CSV and overlay replace `ApiKey=` values with `***`.

**Controls.** Click: play/pause. Double-click: fullscreen. Bottom bar: play/pause, -10 s/+30 s, seek bar, Audio and
Subtitles menus, Stats, Fullscreen. Keys: Space or K play/pause, ← → seek 5 s, F fullscreen, Esc leave fullscreen,
A next audio track, S next subtitle (then off), I stats overlay, H "hide the controls window while idle".

**Self-test** (`-Pspike.selfTest=true`, candidate 2): waits for playback, resizes five times, moves, visits every
other monitor and comes back, maximizes, goes fullscreen and back, switches every audio and subtitle track, seeks,
hides the controls window, pauses and resumes, then drives real mouse and keyboard input through `java.awt.Robot`
(click to pause, double click for fullscreen, Esc, Right arrow with the seek bar focused, Space with a button
focused, a drag-resize through the window's border, H mode with the pointer still: do not touch the mouse or keyboard
meanwhile), then exits 0 (pass), 2 (a step failed), 3 (an exception) or 4 (hung).
After each step it logs the geometry of the frame, the video canvas and the controls window as AWT and Win32 see them,
and it takes full-screen screenshots (`selftest-*.png`: they show everything on your screen, keep them private).

**Output.** `spikes\desktop-video\build\spike-out\`: `events-<time>.log` (what the prototype did), `mpv-<time>.log`
(mpv's verbose log), `stats-<time>.csv` (one row per second while playing: drops, CPU, GPU, colour spaces, window
size, subtitle timing), `crash-<time>.txt` and the JVM's `hs_err_pid<pid>.log` after a crash. They hold local paths:
do not commit them. mpv resets `frame-drop-count` on every seek and loop, so add up the increases, not the last value.

## Already measured on the development machine
See ADR 0010: 60 fps HEVC (1080p/4K, SDR/HDR10), HDR10 output with Windows HDR on, the second (SDR) monitor on the
other GPU, ASS and PGS, audio switching latency, fullscreen modes, the alignment of the two windows, Jellyfin direct
play, and whether the app registers with Windows' media controls.

## Still to check by hand (please report back)
| # | Case | What to look at |
|---|------|-----------------|
| 1 | HDR10 with Windows HDR **off** | overlay `output` should read `bt.709 / gamma2.2`; does the tone-mapped picture look right next to another player? |
| 2 | HDR picture by eye (HDR on) | `hevc-2160p60-hdr10.mkv` and a real HDR10 film: not washed out, not dim, highlights bright |
| 3 | Audio switching by ear | while playing, A (or the Audio menu) across AAC/AC-3/E-AC-3 and TrueHD/E-AC-3: audible gap, click, or sync drift? |
| 4 | Media keys | keyboard Play/Pause, Next with the player focused and unfocused: expected to do nothing (no `win32-smtc`); is the app in the Windows media flyout? |
| 5 | Two windows | Alt+Tab and the taskbar: one entry or two? Clicking the controls: does the video window's title bar turn inactive? Minimize/restore |
| 6 | Dragging | drag the window around and resize by its edges with the mouse (the self-test only sets sizes): does the controls window lag visibly? Its bottom corners follow the window's rounded corners? |
| 7 | Fullscreen (borderless, the default) | F / double-click / Esc: taskbar hidden? Controls on top? Click another monitor or Alt+Tab away and back: does the player stay fullscreen, without lag? |
| 8 | H mode | press H, wait 3 s: the controls window disappears and fullscreen stays on; does moving the mouse over the video bring the controls back? |
| 10 | Seeking and keys | seek bar drag, ← →, −10 s / +30 s, Space after clicking a button: one seek per action, to the expected time? Click on the video: pauses at once? |
| 11 | Tones vs another player | HDR film, same frame in both, against a reference (the film's SDR release, or the OS player); then with mpv options: in the launcher's "Extra mpv options" field type only `target-peak=400` (or `tone-mapping=bt.2390`, `hdr-contrast-recovery=0.3`); on the command line `"-Pspike.mpvOptions=target-peak=400"` |
| 9 | Candidate 1 for comparison | `-Pspike.engine=texture` with the same files: same smoothness? HDR tone-mapped as expected |

## Headless smoke test (any OS with a system libmpv)
`./gradlew :spikes:desktop-video:test -Pspike.testMedia=/path/to/file.mkv` plays a file with `vo=null`/`ao=null` and
checks the binding, track switching, seeking and the stats CSV. Without `-Pspike.testMedia` it does nothing.
