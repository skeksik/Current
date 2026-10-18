# 0007: Headless engines, shared controller, custom player UI

Status: accepted (feature target and staging updated 2026-10-18)

## Decision
Three layers, whichever engine is used:
1. **Engine** (`PlayerEngine`, per platform, headless): load, play/pause/seek, tracks, state, errors, renders to a video surface. No built-in controls.
2. **Controller** (`PlayerController` in `feature:player`, shared Kotlin): owns the plan, fallback logic, progress reporting/resume, track selection,
   skip-intro/next-episode. Exposes `PlayerUiState`.
3. **Player UI**: the app's own custom controls, in SwiftUI (Apple) or Compose (others). Only the video surface is platform-specific.

**Route selection** (`PlaybackPlanner`, shared Kotlin): Direct Play, Direct Stream, Remux, or server Transcode (HLS), chosen from media info,
engine capabilities, quality limit and user permissions. Jellyfin's playback-info request accepts a device profile and flags for direct play/stream/transcoding
(PlaybackInfoDto: deviceProfile, maxStreamingBitrate, enableDirectPlay, enableDirectStream, enableTranscoding, allowVideoStreamCopy, allowAudioStreamCopy,
audioStreamIndex, subtitleStreamIndex), so the server does part of the negotiation. Confirmed against Jellyfin 12.2 on 2026-10-18
(without a DeviceProfile the server offers no transcode URL); details in docs/platform-notes.md.
Stash (through an extension, ADR 0008) is a second source: the raw file with byte ranges, or Stash's own transcodes (HLS, MP4, WEBM). The planner
decides from the file's container and codecs, not from Stash's stream labels, and the controller reports progress to whichever server the media came from.

**Native first, fallback second.** Try the platform's native engine; if the plan says it cannot play the media, or it errors at runtime, retry on the fallback
engine, then a server remux, then a server transcode. The choice is automatic per title; users never pick an engine.

## Feature target
The player aims for the functionality of a full-featured custom engine, on every platform as far as the platform allows:
- **Routes:** automatic per title (native engine, fallback engine, server remux, server HLS transcode) from the server's playback info and the
  engine's capabilities; a quality limit; remembered quality and zoom; buffering and HLS segment-length settings.
- **Video:** HDR10 and HLG with real HDR output where the display supports it (BT.2020); Dolby Vision through its HDR10/SDR base layer where
  full Dolby Vision is not available; hardware decoding wherever offered (HEVC, and AV1 on capable hardware) with optimized software AV1 and VP9
  as fallback; 4K60 at high bitrates without dropped frames.
- **Audio:** multichannel and spatial audio; passthrough where the platform allows.
- **Formats without transcoding:** what Jellyfin libraries commonly hold: MKV, MP4, TS, AVI, WebM; H.264, HEVC, AV1, VP9, MPEG-2, VC-1;
  AAC, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus; subtitles SRT, ASS/SSA, PGS, VobSub, WebVTT.
- **Subtitles:** embedded and external, text and image-based; appearance settings for text subtitles; a delay offset; preferred audio and
  subtitle languages; online subtitle search when the server offers it.
- **Platform:** Picture in Picture; AirPlay on Apple (native player first, a compatible server stream otherwise); Now Playing and media keys.
- **Episodes:** auto-skip intros from server segments; auto-play the next episode with a Next Up prompt, timed by the outro segment or a user setting.
- **Diagnostics:** a playback info overlay (engine, route, protocol, stream type, codecs, resolution, bitrate, frame rate, dynamic range,
  audio track, container) and a problem report whose logs are stripped of secrets.

Where a native engine falls short of this (checked per platform in its spike), that platform gets a fallback engine. On the web, what browsers
cannot play goes to the server instead.

**Staging.** M2: the desktop engine (from the M1 spike) and web (browser video + hls.js, server transcode as fallback). M4: Android (Media3, plus a
fallback where needed) and Apple (AVPlayer plus a fallback engine). Fallback engines are built on existing libraries (libmpv, FFmpeg and the like)
rather than from scratch. Candidate licences checked: mpv is GPLv2+ by default and LGPLv2.1+ only when built without GPL-only files (intended for libmpv);
vlcj is GPLv3+ (commercial licence available) and vlcj 5 requires LibVLC 4. Re-check licences against App Store / distribution needs before choosing.

## Platform notes
See docs/platform-notes.md: Picture in Picture, Now Playing and remote commands, subtitles and tvOS remote handling all become the app's responsibility with a custom UI.
