# 0007: Headless engines, shared controller, custom player UI

Status: accepted

## Decision
Three layers, whichever engine is used:
1. **Engine** (`PlayerEngine`, per platform, headless): load, play/pause/seek, tracks, state, errors, renders to a video surface. No built-in controls.
2. **Controller** (`PlayerController` in `feature:player`, shared Kotlin): owns the plan, fallback logic, Jellyfin progress reporting/resume, track selection,
   skip-intro/next-episode. Exposes `PlayerUiState`.
3. **Player UI**: the app's own custom controls, in SwiftUI (Apple) or Compose (others). Only the video surface is platform-specific.

**Route selection** (`PlaybackPlanner`, shared Kotlin): Direct Play, Direct Stream, Remux, or server Transcode (HLS), chosen from media info,
engine capabilities, quality limit and user permissions. Jellyfin's playback-info request accepts a device profile and flags for direct play/stream/transcoding
(PlaybackInfoDto: deviceProfile, maxStreamingBitrate, enableDirectPlay, enableDirectStream, enableTranscoding, allowVideoStreamCopy, allowAudioStreamCopy,
audioStreamIndex, subtitleStreamIndex), so the server does part of the negotiation. Details to confirm with the `api-researcher` agent before coding.

**Native first, fallback second.** Try the platform's native engine; if the plan says it cannot play the media, or it errors at runtime, retry on the fallback engine,
then a server transcode.

**Staging.** M3a: native engines + server transcode as the only fallback. M3b: a custom fallback engine only if transcoding proves too limiting, built on an existing
library rather than from scratch. Candidate licences checked: mpv is GPLv2+ by default and LGPLv2.1+ only when built without GPL-only files (intended for libmpv);
vlcj is GPLv3+ (commercial licence available) and vlcj 5 requires LibVLC 4. Re-check licences against App Store / distribution needs before choosing.

## Platform notes
See docs/platform-notes.md: Picture in Picture, Now Playing and remote commands, subtitles and tvOS remote handling all become the app's responsibility with a custom UI.
