# Platform notes (researched 2026-10-18)

Facts below were read from the linked sources on that date. Re-check before relying on them for a release. "Not verified" items must be researched before code depends on them.

## Kotlin and Compose targets
- Kotlin/Native tiers: `macosArm64`, `iosArm64`, `iosSimulatorArm64` are Tier 1; `tvosArm64`, `tvosSimulatorArm64` are Tier 2 (compile-tested on CI, not necessarily run-tested).
  `macosX64` and `tvosX64` are deprecated. Default minimum tvOS is 15.0. Apple final binaries only build on a macOS host.
  Source: https://kotlinlang.org/docs/native-target-support.html
- Compose Multiplatform 1.12.2 (page built 2026-10-15) lists Android 5.0+, iOS 14+, macOS 13 arm64 / Windows 10+ / Linux (desktop), and web with WasmGC browsers. tvOS is not mentioned.
  Source: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Our catalog pins CMP 1.12.1 (from the wizard); 1.12.2 is newer. Bump deliberately with `/update-deps`.

## Liquid Glass and SwiftUI
- Liquid Glass is rendered by the system, not Compose; it applies to native `TabView`, `NavigationStack` and toolbars (Xcode 26 / iOS 26 SDK; iOS 26 only, older versions need a fallback).
  Source: https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html

## Swift interop
- Swift export is Alpha; supports suspend -> async, Flow -> AsyncSequence, enums, sealed types; generics are erased; limited class/collection support.
  Source: https://kotlinlang.org/docs/native-swift-export.html
- SKIE converts Flow/StateFlow to AsyncSequence, suspend to async, sealed classes/enums to Swift enums. Release 0.10.15 (latest at check time) mentions Kotlin 2.4.20 support.
  Sources: https://skie.touchlab.co/ , https://github.com/touchlab/SKIE/releases

## Apple playback (custom UI)
- PiP in a custom player: `AVPictureInPictureController` with an `AVPlayerLayer` or `AVSampleBufferDisplayLayer`; app supplies its PiP button, restores its UI in the delegate,
  must keep a strong reference, may only start PiP on user interaction (App Store rejects otherwise); needs audio session / background modes; on tvOS Now Playing must go through `MPNowPlayingSession`.
  Source: https://developer.apple.com/documentation/avkit/adopting-picture-in-picture-in-a-custom-player
- tvOS custom player: you must implement all remote commands yourself via `MPRemoteCommandCenter` plus gesture recognizers, and update Now Playing info;
  `AVPlayerViewController` handles these automatically and can be extended (custom transport items, info tabs, contextual actions such as Skip).
  Sources: https://developer.apple.com/documentation/avfoundation/supporting-remote-interactions-in-tvos , https://developer.apple.com/documentation/avkit/customizing-the-tvos-playback-experience
  Open decision for tvOS: fully custom UI vs `AVPlayerViewController` with custom items.
- Now Playing: `MPNowPlayingInfoCenter` exists on iOS, macOS (also has `playbackState`) and tvOS. Source: https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter
- Subtitles: a developer forum thread reports native subtitles work with `AVPlayerLayer` but are lost with `AVSampleBufferDisplayLayer`; `AVPlayerItemLegibleOutput` gives attributed text that must be laid out manually.
  This is a user report, not Apple guidance. Source: https://developer.apple.com/forums/thread/700128

## Desktop and web playback
- vlcj: GPLv3+ (commercial licence available); vlcj 5 requires VLC/LibVLC 4.0+; vlcj 4.x is the stable branch for older VLC. Source: https://github.com/caprica/vlcj
- mpv: GPLv2+ by default; LGPLv2.1+ only when built without GPL-only files (intended for libmpv). Source: https://github.com/mpv-player/mpv/blob/master/Copyright
- hls.js: Apache-2.0, needs Media Source Extensions; Safari plays HLS natively. Source: https://github.com/video-dev/hls.js
- Media Session API (web): metadata, action handlers, hardware media keys; MDN marks it "Limited availability", not Baseline. Source: https://developer.mozilla.org/en-US/docs/Web/API/Media_Session_API

## Service APIs
- Jellyfin playback info request fields (PlaybackInfoDto): userId, maxStreamingBitrate, startTimeTicks, audioStreamIndex, subtitleStreamIndex, maxAudioChannels, mediaSourceId, liveStreamId,
  deviceProfile, enableDirectPlay, enableDirectStream, enableTranscoding, allowVideoStreamCopy, allowAudioStreamCopy, autoOpenLiveStream, alwaysBurnInSubtitleWhenTranscoding.
  Source: https://kotlin-sdk.jellyfin.org/dokka/jellyfin-model/org.jellyfin.sdk.model.api/-playback-info-dto/index.html (Jellyfin Kotlin SDK docs)
- Jellyfin Kotlin SDK is available for JVM and Android only (LGPL-3.0), so it cannot be used from common code. Source: https://github.com/jellyfin/jellyfin-sdk-kotlin
- Jellyfin auth: the third-party-hosted page we found shows `Authorization: MediaBrowser Client="..", Device="..", DeviceId="..", Version=".."` (+ `Token=".."`), `POST /Users/AuthenticateByName` with `Username`/`Pw`,
  and says legacy headers (`X-Emby-Token`, `api_key`) may be disabled. This is NOT the official docs: confirm against the official OpenAPI/docs before coding.
- Seerr (merger of Overseerr and Jellyseerr): API key goes in the `X-Api-Key` header; cookie auth via `/auth/local` (cookie `connect.sid`). OpenAPI spec:
  https://raw.githubusercontent.com/seerr-team/seerr/refs/heads/develop/seerr-api.yml . Reference: https://docs.seerr.dev/api/seerr-api/ . The reference page we read did not list discover or request-creation endpoints: read the spec.
- Stash: GraphQL at `<server>:<port>/graphql` (default `localhost:9999/graphql`); API key in an `ApiKey` header; cookie auth is legacy. Source: https://docs.stashapp.cc/api/

## Not verified (research before depending on it)
- Windows media-key / System Media Transport Controls integration from the JVM, and which desktop engine to use (libmpv vs VLC) including Compose Desktop embedding.
- Jellyfin CORS configuration for a browser client on another origin, and whether mixed-content (HTTPS page to HTTP server) is workable; official Jellyfin auth docs.
- Compose Multiplatform web accessibility (screen reader support); the accessibility docs page does not cover web.
- Seerr discover and request-creation endpoints (read the OpenAPI spec above).
- Media3 capabilities for the Android fallback (e.g. FFmpeg extension) and licences of any custom Apple fallback engine library.
- Exact CMP/Kotlin/AGP/Gradle compatibility of our pinned set (it builds on Windows; Apple targets are not built yet).
