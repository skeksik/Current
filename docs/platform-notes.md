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

## Desktop and web playback (checked 2026-10-18; input for the M1 video spikes)
**Compose over native video.** Compose Multiplatform 1.12 has no supported way to draw Compose over native video:
- Desktop: "The Swing component within `SwingPanel` will always be layered above the Compose Multiplatform component". The experimental interop
  blending flag (`-Dcompose.interop.blending=true`) "cannot overlay another DirectX component" on Windows, which rules out mpv's and VLC's
  Direct3D output. Sources: https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html ,
  https://github.com/JetBrains/compose-multiplatform-core/pull/915
- Web: `HtmlElementView` (`WebElementView` in 1.9 and 1.10) places the HTML element over the canvas, where it takes all input
  (KDoc in `compose/ui/ui/src/webMain/.../InteropView.web.kt`). Compose for web is Beta.
  Sources: https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md , https://kotlinlang.org/docs/multiplatform/supported-platforms.html

**Desktop engine candidates** (Windows first), in the order to try them:
1. libmpv drawn into Compose as a GPU texture, as MediaMP does (https://github.com/open-ani/mediamp, Apache-2.0, pre-1.0, v0.5.2): mpv's
   D3D11 render API shares frames with Skia's D3D12 device without CPU copies. MediaMP reaches Skiko internals by reflection, and the
   output is 8-bit SDR. Checked 2026-10-19 (M1 spike, ADR 0010):
   - The render API (https://github.com/mpv-player/mpv/pull/17764) was merged into mpv master on 2026-10-15; the latest release is still
     0.41.0, so MediaMP ships its own patched libmpv. Its design notes (`mediamp-mpv/mpv-d3d11-rendering.md`, in Chinese) describe
     `RGBA_8888` shared textures and reflection on Skiko's `Direct3DRedrawer`; its player forces tone mapping of HDR to SDR
     (`MpvMediampPlayer.jvm.kt`). MediaMP 0.5.2 is built against CMP 1.12.0 and resolves to Skiko 0.150.1 with CMP 1.12.1.
   - In the spike it played 4K60 HEVC with 2-3 dropped frames at start and none after, so performance is not the problem; HDR10 output is.
2. libmpv in a native child window (`wid`) with our controls in a separate transparent window: the only route that keeps HDR; the controls
   window must follow the video window (vlcj's docs report lag when dragging or resizing with this pattern:
   https://capricasoftware.co.uk/tutorials/vlcj/4/overlay). Chosen in ADR 0010; checked 2026-10-19 in the spike:
   - With `vo=gpu-next`, `gpu-api=d3d11` and `target-colorspace-hint=yes`, mpv switched its swap chain to `bt.2020/pq` on an HDR display
     with Windows HDR on, and to `bt.709/gamma2.2` (tone-mapped, 203 nits) when the window moved to an SDR monitor on another GPU, without
     restarting. Read the result from the `video-target-params` property.
   - Compose's `WindowPlacement.Fullscreen` is AWT's full-screen exclusive mode on Windows and Linux: Skiko 0.150.1's `PlatformOperations`
     calls `GraphicsDevice.setFullScreenWindow` (macOS uses its native fullscreen). Leaving it at 150 % display scale, the frame came back
     with its logical size applied as physical pixels while AWT kept the logical layout, so the child canvas was clipped. A borderless
     fullscreen on the same HWND (`SetWindowLongPtr` without the frame styles, `SetWindowPos` to the monitor rectangle) avoided it.
   - mpv rebuilds its swap chain on every size change: a few dropped frames per resize or fullscreen switch, none in steady playback.
   - The controls window must follow the frame's content area (AWT resizes it at once; the canvas follows only after Compose's next
     layout, and came out 2 px taller at 150 %), call `validate()` after `setBounds` (otherwise the Compose content kept its old size
     during a drag-resize), and leave a maximized frame through AWT (restyling a maximized HWND directly left AWT with stale bounds).
   - Hiding a window makes Windows send a mouse move to the window below although the cursor did not move; idle detection must compare
     positions. Compose's `detectTapGestures` with `onDoubleTap` delays every single tap by the double-tap timeout.
3. vlcj 4 + LibVLC 3: mature, but vlcj is GPLv3+ or paid, and rendering into Compose goes through CPU copies (LibVLC calls callback
   rendering "considerably less efficient").
- Not suitable: Compose Media Player on Windows (Media Foundation: no MKV/WebM, first audio stream only), JavaFX Media (no MKV, AC-3, DTS),
  GStreamer's Java bindings (last release 2021), JavaCV (a decoder, not a player).

**Licences**
- mpv: GPLv2+ by default, LGPL when built with `-Dgpl=false` (https://github.com/mpv-player/mpv/blob/master/Copyright). An app under any
  licence may load an LGPL `libmpv-2.dll` if it stays a separate, replaceable DLL and we ship its licence and source. MediaMP's own Linux
  runtime is GPL.
- Prebuilt LGPL libmpv for Windows is LGPLv3, not 2.1 (checked 2026-10-19): https://github.com/zhongfly/mpv-winbuild's `mpv-dev-lgpl`
  "statically links to ffmpeg under LGPLv3" (README), and mpv's rolling builds (https://github.com/mpv-player/mpv/releases/tag/git-release)
  say "LGPL builds are LGPLv3". MediaMP's `mediamp-mpv-runtime-windows-x64` 0.5.2 jar is LGPL-2.1+ (POM: Apache-2.0 and
  LGPL-2.1-or-later; read from the DLLs: libmpv `-Dgpl=false`, FFmpeg "LGPL version 2.1 or later" without `--enable-gpl`,
  `--enable-version3` or `--enable-nonfree`), but its FFmpeg is configured with `--disable-everything` and a short list (51 decoders; no
  VP8, MPEG-1, Theora, MJPEG, WavPack, APE, DVB subtitles, no MPEG-PS/VOB, FLV or WAV demuxers) and its libmpv has `-Dwin32-smtc=disabled`.
  A shipped build is therefore our own (ADR 0009, ADR 0010).
- vlcj: GPLv3+ or commercial (https://github.com/caprica/vlcj); LibVLC itself is LGPLv2+. Shipping vlcj makes the desktop build GPLv3.
- FFmpeg: LGPL unless GPL parts are enabled. Patent licensing for HEVC, AAC and AC-3 decoders is a separate question (https://ffmpeg.org/legal.html).

**Windows media keys.** System Media Transport Controls are a WinRT API
(https://learn.microsoft.com/en-us/windows/apps/develop/media-playback/system-media-transport-controls). libmpv has them built in
(`--media-controls`, Windows only, off by default for libmpv, since 0.39: https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst).
The JVM libraries (JavaMediaTransportControls, mediasession-kt) were last released in 2024. Checked 2026-10-19: with a libmpv built
without `win32-smtc`, `media-controls=yes` is accepted but the process does not appear among the sessions of
`GlobalSystemMediaTransportControlsSessionManager`, so media keys go to another app.

**Web video**
- Candidates: (1) `<video>` (+ hls.js) behind the canvas, shown through a transparent hole drawn with `BlendMode.Clear` (Compose Media
  Player's web surface does this: https://github.com/kdroidFilter/ComposeMediaPlayer); it relies on undocumented DOM order and Skiko's
  alpha canvas. (2) Video drawn by Compose through the experimental WebGL render target (`rememberWebGLRenderTarget`, CMP 1.13.0-alpha01+,
  https://github.com/JetBrains/compose-multiplatform-core/pull/3323): CORS on every media URL, one GPU upload per frame, SDR only.
  (3) A plain DOM player screen, with controls that are not Compose.
- HLS: native in Chrome desktop 142+ and Safari, not in Edge or Firefox (caniuse). hls.js (v1.7.3, Apache-2.0) needs Media Source
  Extensions and CORS on every HLS resource; check `Hls.isSupported()` before relying on native playback (https://github.com/video-dev/hls.js).
  Kotlin/Wasm can import it as an npm module (`@JsModule`, or `js("import(...)")`: https://kotlinlang.org/docs/wasm-js-interop.html).
- Direct play in desktop browsers (caniuse; probe at runtime with `canPlayType` and `MediaSource.isTypeSupported`, as jellyfin-web does):
  MP4/WebM with H.264, VP9 or AV1 (Safari: AV1 with hardware only) and AAC, MP3, Opus or FLAC. HEVC only with hardware decoding
  (Safari always). MKV in Chromium-based browsers and in Firefox 145+ (which buffers the whole file). No AC-3, E-AC-3, DTS or TrueHD, so
  those need an audio transcode. Subtitles: WebVTT only; ASS and PGS need burn-in or a JS renderer.
- CORS: a plain `<video src>` plays cross-origin media without it; fetch (Ktor, image loading), hls.js, WebGL textures and cross-origin
  `<track>` need it (https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS).
- Media Session API (web): metadata, action handlers, hardware media keys; MDN marks it "Limited availability", not Baseline.
  Source: https://developer.mozilla.org/en-US/docs/Web/API/Media_Session_API

## Web UI options (checked 2026-10-19; input for the M1 web UI spike)
- **Compose for web draws on a canvas** (Kotlin/Wasm; Beta, while Android, iOS and desktop are Stable:
  https://kotlinlang.org/docs/multiplatform/supported-platforms.html). What a DOM page gets for free is missing or partial:
  - Autofill and password managers: not supported; https://youtrack.jetbrains.com/issue/CMP-9861 is open, targeted at 1.15.0. The text-input
    epic https://youtrack.jetbrains.com/issue/CMP-10164 lists about 40 open input bugs (IME, mobile keyboards, RTL).
  - Real links (middle-click, open in a new tab, copy link): https://youtrack.jetbrains.com/issue/CMP-9997 is open without a target version;
    the offered alternative, `LocalUriHandler.openUri`, opens a URL and nothing more.
  - Find-in-page: https://youtrack.jetbrains.com/issue/CMP-10215 was closed as answered (2026-08-06); the workaround is an in-app search on
    Ctrl/Cmd+F. Browser translate: no tracking issue found; assume it does not work.
  - Text selection: `SelectionContainer` works, with open web bugs (e.g. https://youtrack.jetbrains.com/issue/CMP-8461).
  - Accessibility: a hidden accessibility DOM kept in sync with the canvas since 1.9
    (https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html); the web accessibility epic
    https://youtrack.jetbrains.com/issue/CMP-9678 is still open.
  - HTML elements can be embedded (`HtmlElementView`), but they sit over the canvas and take its input, so Compose controls over `<video>`
    need the transparent-hole workaround (see "Desktop and web playback").
- **Compose HTML** (`org.jetbrains.compose.html`): the Compose runtime over real DOM elements, with a CSS-in-Kotlin DSL; no Material or
  other ready-made components, and composables cannot be shared with Compose Multiplatform. Released with CMP (html-core 1.12.2 on
  2026-10-15, Maven Central), Kotlin/JS only (the README calls it "not a multiplatform library"). Wasm support is in the backlog
  (https://youtrack.jetbrains.com/issue/CMP-4461); JetBrains has said it wants to make it multiplatform, possibly source- but not
  binary-compatible, with no timeline. `lifecycle-viewmodel` (2.11.0) has JS artifacts and `collectAsState()` comes from the Compose
  runtime, so pages can collect shared `StateFlow`s directly; providing ViewModels in Compose HTML is untested.
- **Kotlin/JS core under a TypeScript UI**: Kotlin/JS is Stable. `@JsExport` exports classes and objects with generated `.d.ts`
  (https://kotlinlang.org/docs/js-project-setup.html); exporting suspend functions is Experimental since 2.3.0
  (`-Xenable-suspend-function-exporting`, https://kotlinlang.org/docs/whatsnew23.html); `Flow`/`StateFlow` cannot be exported, so each
  ViewModel needs a small subscribe/unsubscribe adapter; `List`/`Map` arrive as `KtList`/`KtMap` (`asJsReadonlyArrayView()`,
  https://kotlinlang.org/docs/js-to-kotlin-interop.html); `Long` as BigInt is Experimental. Kotlin/Wasm exports are too limited for this
  (top-level functions with primitives, strings and opaque references only: https://kotlinlang.org/docs/wasm-js-interop.html).
- **Libraries with JS artifacts** (Maven Central, 2026-10-19): Ktor client 3.6.0 (`ktor-client-js` engine), kotlinx.serialization,
  kotlinx.coroutines 1.11.0, Koin 4.2.2, JetBrains lifecycle-viewmodel 2.11.0; all also publish wasm-js.
- **Other Kotlin DOM toolkits**: Kobweb (Apache-2.0, on Compose HTML, pre-1.0: https://github.com/varabyte/kobweb), KVision (MIT, JS only:
  https://github.com/rjaros/kvision).

## Licences, stores and patents for player engines (checked 2026-10-18)
- What a shipped library asks of us: permissive (MIT, BSD, ISC, Apache-2.0) only notices (Apache-2.0 also its NOTICE file); MPL-2.0 source of
  its own files only; LGPL-2.1/3.0 the library's source and our changes, and users must be able to relink (a replaceable dynamic library, or
  our app as objects or source when linked statically), with no terms that forbid modifying or debugging it; GPL makes the whole app GPL,
  however it links. GPLv2-only code cannot be combined with Apache-2.0 code. LGPLv3/GPLv3 "Installation Information" applies only to software
  conveyed with a consumer device, not to an app on its own. Sources: https://www.gnu.org/licenses/gpl-faq.html ,
  https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt , https://www.gnu.org/licenses/lgpl-3.0.txt , https://www.apache.org/licenses/GPL-compatibility.html
- Stores: the FSF considers the App Store's usage rules incompatible with the GPL, so Apple builds carry no GPL code. LGPL engines ship there in
  practice (VLCKit, MPVKit's non-GPL products). Google Play, the Microsoft Store (its policies cover open-source licences) and direct downloads
  accept GPL and LGPL. F-Droid needs FLOSS built from source. All three stores forbid pornographic apps; a client for the user's own
  Stash server is nonetheless listed in the US App Store (16+), so the adult extension is decided per store (ADR 0009). Sources: https://developer.apple.com/app-store/review/guidelines/ , https://learn.microsoft.com/en-us/windows/apps/publish/store-policies ,
  https://f-droid.org/docs/Inclusion_Policy/
- Apple fallback candidates: MPVKit (LGPLv3 by default, GPL only in its `-GPL` products; static libraries, so relinking means publishing our
  app as objects or source), VLCKit (LGPL-2.1+), an FFmpeg + VideoToolbox + Metal engine of our own (LGPL). KSPlayer is GPL-3.0 (LGPL only with a
  paid licence). Sources: https://github.com/mpvkit/MPVKit , https://github.com/videolan/vlckit , https://github.com/kingslay/KSPlayer
- Android: Media3 is Apache-2.0; its FFmpeg extension decodes audio only (AC-3, E-AC-3, DTS, TrueHD among others); AV1 through dav1d (BSD-2).
  mpv-android builds GPLv3, so an LGPL libmpv would need our own build. Source: https://github.com/androidx/media/tree/release/libraries
- Shared pieces: libass (ISC), libplacebo (LGPL-2.1+, can convert Dolby Vision profile 5 and compose profile 7). Without a Dolby licence the
  safe route is the HDR10/SDR base layer. Sources: https://github.com/libass/libass , https://github.com/haasn/libplacebo
- Patents are separate from copyright: through OS decoders (VideoToolbox, MediaCodec, Media Foundation) the OS or device maker is the licensee;
  shipping FFmpeg's own HEVC or AAC decoders makes us the distributor of a decoder, so prefer OS and hardware decoders for those
  (https://ffmpeg.org/legal.html , https://accessadvance.com/licensing-programs/hevc-advance/ , https://www.via-la.com/licensing-programs/aac/).
  No Dolby or DTS logos or claims without a licence; neutral codec names (E-AC-3, DTS-HD MA) in the playback info overlay.

## Jellyfin 12.x (checked 2026-10-18 against a 12.2 server, its OpenAPI and the v12.2 source)
- Versioning: the release after 10.11 is 12.0 (2026-09-07); tags are `v12.0`..`v12.2`, servers report `12.2.0`.
  Sources: https://jellyfin.org/posts/jellyfin-release-12.0/ , https://github.com/jellyfin/jellyfin/releases/tag/v12.2
- Spec: https://api.jellyfin.org/openapi/stable/jellyfin-openapi-12.2.json ; every server serves its own at `/api-docs/openapi.json`.
  Its security scheme does not describe the `MediaBrowser` header format, so generated clients get auth wrong.
- Auth: only `Authorization: MediaBrowser Client="..", Device="..", DeviceId="..", Version="..", Token=".."` (key names are case-sensitive,
  values quoted and URL-encoded) and the `ApiKey` query parameter. `X-Emby-Token`, `X-MediaBrowser-Token`, `X-Emby-Authorization` and `api_key`
  are off by default since 12.0 (`EnableLegacyAuthorization`, https://github.com/jellyfin/jellyfin/pull/15559); live they return 401.
- Sign-in: `POST /Users/AuthenticateByName` `{Username, Pw}` with the header minus `Token` (400 if Client, Device, DeviceId or Version is
  missing) returns `AccessToken`, `User.Id`, `ServerId`. Signing in again with the same DeviceId and user revokes the previous token, so keep
  one random DeviceId per install. Sign-out: `POST /Sessions/Logout`.
- Quick Connect: `GET /QuickConnect/Enabled`; `POST /QuickConnect/Initiate` returns a 6-digit code (valid 10 minutes) and a secret; poll
  `GET /QuickConnect/Connect?secret=`; finish with `POST /Users/AuthenticateWithQuickConnect {Secret}`. A signed-in client can approve a
  code with `POST /QuickConnect/Authorize?code=`.
- Browsing (the user comes from the token; `userId` is optional): `/UserViews`, `/Items`, `/Items/Latest` (bare array), `/UserItems/Resume`,
  `/Shows/NextUp`, `/Shows/{id}/Seasons`, `/Shows/{id}/Episodes`, `/Items/{id}`, `/Items/{id}/Similar`, `/Genres`, `/Search/Hints`.
  The `/Users/{userId}/Items...` routes left the spec in 10.9; they still answer as hidden routes, do not use them.
  List parameters are comma-separated except `genres`, `tags`, `studios` (`|`); values the server cannot parse are dropped silently;
  pass `recursive` explicitly (12.x changed its default). Played/favorite: `POST|DELETE /UserPlayedItems/{id}`, `/UserFavoriteItems/{id}`.
- Images: `/Items/{id}/Images/{type}` needs no auth; with `tag=` the response is cached as immutable. Blurhashes: `ImageBlurHashes[type][tag]`.
- Provider ids: `ProviderIds["Tmdb"]` is a string and may be missing (most movies on the test server have none), so joins with Seerr cannot rely on it alone.
- JSON: PascalCase by default, camelCase with `Accept: application/json; profile="CamelCase"`. Ids are 32 hex characters without dashes.
  Nulls are usually omitted; enums gain values between versions (parse leniently). Ticks are 10,000,000 per second; `0001-01-01` means unset.
- Playback: `POST /Items/{id}/PlaybackInfo` needs a DeviceProfile (without one there is no `TranscodingUrl`), and `MediaSourceId` for audio and
  subtitle choices to apply. `SupportsDirectStream` equals `SupportsDirectPlay` in practice. Direct play:
  `/Videos/{id}/stream.{container}?static=true&mediaSourceId=..&deviceId=..&ApiKey=..` (byte ranges). HLS: use `TranscodingUrl` as given
  (relative, already contains `ApiKey`; the HLS routes are hidden from the spec since 12.0). Media URLs authenticate with `ApiKey`;
  trickplay tiles need it, item images do not.
- Reporting: `POST /Sessions/Playing`, `/Sessions/Playing/Progress` about every 10 s, `/Sessions/Playing/Stopped`, and
  `/Sessions/Playing/Ping?playSessionId=` while paused. The server stops an HLS transcode after 60 s without progress or ping.
  `Stopped` without `PositionTicks` marks the item fully watched.
- Subtitles: delivery `Encode`, `Embed`, `External`, `Hls`, `Drop`; external files at
  `/Videos/{id}/{mediaSourceId}/Subtitles/{index}[/{startTicks}]/Stream.{vtt|srt|ass|ssa|ttml|json}`.
- Intro/outro: `/MediaSegments/{id}`, but core ships no segment provider (a plugin must supply them), so expect none.
  Trickplay: the `Trickplay` field, `/Videos/{id}/Trickplay/{width}/tiles.m3u8` and `{index}.jpg`.
- CORS: `ServerConfiguration.CorsHosts` (default `["*"]`, not in the UI or docs) allows any origin without credentials. Live preflights return
  204 with `access-control-allow-origin: *` and echo the requested methods and headers, so a browser app on another origin works with header auth.
  Source: https://jellyfin.org/docs/general/post-install/networking/ (does not mention CORS); v12.2 source.

## Seerr (checked 2026-10-18 against upstream https://github.com/seerr-team/seerr (develop) and a live server)
- Spec: https://raw.githubusercontent.com/seerr-team/seerr/refs/heads/develop/seerr-api.yml . Reference: https://docs.seerr.dev/api/seerr-api/ .
- Requests are validated against the spec before any route runs (`express-openapi-validator` in `server/index.ts`): unknown query parameters
  get 400, unknown paths 404, no session 401. Send only documented parameters.
- Session: cookie `connect.sid`, 30 days from sign-in, HttpOnly, SameSite Lax (Strict when CSRF protection is on), `secure: auto` (Secure only
  when Seerr itself sees HTTPS, see its trust-proxy setting). Source: `server/index.ts`.
- Sign-in (`server/routes/auth.ts`): `POST /api/v1/auth/jellyfin {username, password}`, `POST /api/v1/auth/local {email, password}`, or Jellyfin
  Quick Connect through Seerr (`POST /auth/jellyfin/quickconnect/initiate` returns code + secret, `GET .../check?secret=`,
  `POST .../authenticate {secret}`). Login responses are partial: call `GET /auth/me` after. `POST /auth/logout`.
  A client already signed in to Jellyfin can approve Seerr's Quick Connect code itself (Jellyfin `POST /QuickConnect/Authorize?code=`), so it
  never stores a password and can renew the session silently. Not tried end to end yet; needs Quick Connect enabled on Jellyfin.
- No per-user API tokens. The global API key (`X-Api-Key`) acts as admin and, with `X-API-User: <id>`, as any user
  (`server/middleware/auth.ts`): never put it in a client.
- `permissions` is a JSON number bitmask: use `Long`.
- CORS: none, and OPTIONS preflights fail (405 from the validator). A browser app must be served on the same origin as Seerr's API
  (reverse proxy) or Seerr needs CORS support (server change). TMDB's image CDN sends `Access-Control-Allow-Origin: *`; `/imageproxy` needs no auth.
- Shapes: catalogue lists `{page, totalPages, totalResults, results}`; database lists `{pageInfo: {pages, pageSize, results, page}, results}`
  with `take`/`skip`. Media records carry `jellyfinMediaId` / `jellyfinMediaId4k`; `mediaUrl` is a web link, not a stream (`server/entity/Media.ts`).
  TMDB image paths are relative: prefix `https://image.tmdb.org/t/p/<size>`.
- Status values (`server/constants/media.ts`): request 1 pending, 2 approved, 3 declined, 4 failed, 5 completed; media 1 unknown, 2 pending,
  3 processing, 4 partially available, 5 available, 6 blocklisted, 7 deleted.
- `GET /api/v1/settings/discover` (the configured Discover rows) is readable by any signed-in user (`server/routes/index.ts`).
- Gotchas: error bodies come in several shapes (`{message}`, `{message, errors}`, `{status, error}`, `{error}`); `POST /request` can answer
  202 `{message}` instead of a request; `POST /user/{id}/settings/main` clears fields left out (send the whole object); push is Web Push only.

## Stash v0.31.1 (checked 2026-10-18 against a live server, introspection and the v0.31.1 source, https://github.com/stashapp/stash/tree/v0.31.1)
- Auth: `ApiKey` header or `?apikey=`. One key per server: full admin, no scopes, no expiry, no users. Without credentials, extensionless
  media paths redirect (302) to `/login` and `/graphql` or paths with an extension return 401. HEAD returns 405. Docs: https://docs.stashapp.cc/api/
- **The server copies its API key into every stream URL it returns** (`paths.stream`, `sceneStreams[].url`), however the caller authenticated
  (`internal/api/urlbuilders/scene.go`). A client that holds Stash stream URLs holds the admin key; they end up in player logs and history.
- Returned URLs are built from request headers (`X-Forwarded-*`) or `external_host`: rebase them onto the configured server origin.
- Scenes: `findScene(id)` returns null for an unknown id; for batches use aliased `findScene` (one missing id fails `findScenes(ids:)`).
  `files[0]` is the primary file every stream uses; choose routes from its `format`, `video_codec`, `audio_codec`, `width`, `height`, never from
  `sceneStreams[].mime_type` ("Direct stream" and "MKV" always say `video/mp4`). `date` may be partial (`YYYY`, `YYYY-MM`); `captions` is null
  when empty; `files[].size` is Int64.
- Streams: `/scene/{id}/stream` serves the raw primary file with byte ranges. Transcodes: `stream.m3u8` (H.264 + AAC in MPEG-TS, 2 s segments,
  seekable), `stream.mp4` and `stream.webm` (no byte ranges, seek with `?start=`), `stream.mpd`, each with
  `?resolution=ORIGINAL|FOUR_K|FULL_HD|STANDARD_HD|STANDARD|LOW`; `stream.mkv` (video copied, audio to Opus) for Matroska sources only.
- Lookup by stash-box id: `scene_filter: {stash_id_endpoint: {endpoint, stash_id, modifier: EQUALS}}` (the endpoint must match exactly);
  `stash_ids_endpoint` for batches.
- Resume and plays, as the web player does (`ui/v2.5/src/components/ScenePlayer/track-activity.ts`): `sceneSaveActivity(id, resume_time,
  playDuration)` every 10 s and on pause/stop (`playDuration` is added, so send seconds since the last save), `sceneAddPlay(id)` once per session.
- Seek thumbnails: `paths.vtt` + `paths.sprite`; chapters: `scene_markers` or `/scene/{id}/vtt/chapter`; captions: `paths.caption` + `?lang=&type=` (WebVTT).
- CORS: allow-all, hard-coded (`access-control-allow-origin: *`, methods POST, headers `Apikey, Content-Type`, no credentials); no setting.
- GraphQL: send `Accept: application/json`; validation errors are HTTP 422.

## Not verified (research before depending on it)
- For the video spikes: desktop playback on hybrid-GPU laptops and lower-end GPUs (ADR 0010 measured one desktop with an RTX 5090), tone-mapped output
  with Windows HDR off, libmpv's `win32-smtc` from an unpackaged JVM app (needs a build with it), the `wid` route on macOS and Linux desktops,
  interop blending over an OpenGL-rendered video, Compose popups as separate windows (`compose.layers.type=WINDOW`) above a native video window,
  the web hole surviving page CSS and CMP updates, HDR on web, patent licensing for HEVC/AAC/AC-3 decoders, the Ktor engine for wasmJs.
- Whether a browser app on another origin can sign in to Seerr through a reverse proxy without Seerr changes (cookie scope, CSRF), and mixed content (HTTPS page to HTTP server).
- Sign-in flows end to end: no POST was sent to Jellyfin or Seerr while checking (AuthenticateByName, Quick Connect, PlaybackInfo, reporting).
- Which DeviceProfile each engine needs (Media3, AVPlayer, desktop engine, HTML video), and how they cope with Stash's 302-to-login and mislabelled mime types.
- Compose Multiplatform web accessibility (screen reader support); the accessibility docs page does not cover web.
- Media3 capabilities for the Android fallback (e.g. FFmpeg extension) and licences of any custom Apple fallback engine library.
- Exact CMP/Kotlin/AGP/Gradle compatibility of our pinned set (JVM tests and desktop compile pass on Linux; web, Android and Apple builds not verified yet).
