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
- Apple (checked 2026-10-19): apps using standard SwiftUI, UIKit or AppKit components get the new look on the latest releases of iOS,
  iPadOS, macOS, tvOS and watchOS when built with the latest SDKs; "On older devices, your app maintains its current appearance". On tvOS,
  standard buttons and controls take on Liquid Glass when focused, so adopt the standard focus APIs.
  Source: https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass

## Apple app project (checked 2026-10-19 with Xcode 27.1 on macOS 26.6.2)
- Xcode 27.1 templates: the multiplatform App template is one target with `SUPPORTED_PLATFORMS = iphoneos iphonesimulator macosx xros
  xrsimulator`; the multiplatform Game template adds tvOS as a separate target ("Include tvOS Application"). One target that also lists
  `appletvos appletvsimulator` builds and runs on a tvOS simulator, and `xcodebuild -showdestinations` lists tvOS destinations for it. Source: the template files
  under `Xcode.app/Contents/Developer/Library/Xcode/Templates/Project Templates/MultiPlatform/`, and our build.
- `embedAndSignAppleFrameworkForXcode` (Kotlin 2.4.20) picks the framework from the Xcode SDK: `iphonesimulator` -> `IosSimulatorArm64`,
  `macosx` -> `MacosArm64`, `appletvsimulator` -> `TvosSimulatorArm64`, and puts it in `apple/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`.
  For a static framework the Xcode target needs that folder in `FRAMEWORK_SEARCH_PATHS` and `-framework Shared` in `OTHER_LDFLAGS`.
- A framework module with no source file of its own is not linked: `link*Framework*` reports NO-SOURCE even when it exports other
  modules, so no framework is produced. `apple/` keeps one source file for that reason. Observed in our build.
- `xcodebuild -sdk appletvsimulator` (by SDK, without `-destination`) builds without a tvOS simulator runtime installed; running on a
  simulator needs the runtime (`xcodebuild -downloadPlatform tvOS`, about 3.8 GB for tvOS 27.0). Observed in our build.
- With no development team, a macOS Debug build is signed ad hoc and runs locally, with the sandbox and outgoing-network entitlements
  from `ENABLE_APP_SANDBOX` and `ENABLE_OUTGOING_NETWORK_CONNECTIONS`. iOS and tvOS simulator builds need no team. Observed in our build.
- tvOS app icons are a layered "App Icon & Top Shelf Image" brand asset; an iOS-style `AppIcon` set gives a build warning on tvOS.

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
- Checked 2026-10-19 in the web UI spike (ADR 0011; Playwright's Chromium 156 and Edge 155 on Windows):
  - Candidate (1) works with CMP 1.12.1, but the canvas is painted white where nothing is drawn. The player clears the *root* canvas with
    `drawBehind { drawRect(..., blendMode = BlendMode.Clear) }`; an offscreen `graphicsLayer` only clears its own layer. A `<video>` in a
    fixed layer under the Compose viewport then shows through, and the Compose controls on top get the input.
  - Probes:
    - `canPlayType('video/x-matroska; codecs="avc1.640029, mp4a.40.2"')` is "probably" in both browsers; MSE `isTypeSupported` for MKV is
      false.
    - HEVC (Main and Main10) through MSE is true with hardware (headed browser; false in headless Chromium).
    - Edge reports AC-3 and E-AC-3 as "probably" (Windows decoders); Chromium does not.
    - `application/vnd.apple.mpegurl` is "maybe" in Chromium 156.
    - Playwright launches Chromium with `--force-color-profile=srgb` by default, so `(dynamic-range: high)` is false even on an HDR
      display; pass `ignoreDefaultArgs: ['--force-color-profile=srgb']` to test HDR.
    - Full probe table recorded in installed Chrome 155, Edge 155 and Firefox 158 (ADR 0011, "Web playback routes"):
      - Both: MP4/WebM/MKV with H.264 (High 10 too), HEVC Main/Main10, AV1 8/10-bit, VP9 profiles 0/2, VP8; AAC, MP3, Opus, FLAC,
        Vorbis. No MOV or MPEG-TS through `src`, no DTS or TrueHD anywhere.
      - MSE in fMP4: no MP3 (`mp4a.6B`).
      - `decodingInfo` reports HDR10 (PQ) only for HEVC Main10, not AV1 10-bit or VP9 profile 2, and HLG for none.
      - `decodingInfo` reports 2160p smooth for H.264, HEVC, AV1 and VP9.
      - Edge adds AC-3 and E-AC-3 in `src` and MSE.
      - With Windows HDR on, Chrome and Edge report `(dynamic-range: high)`. Firefox does not; it has no HDR video output on Windows,
        though `decodingInfo` says it decodes HDR10 and HLG.
      - Firefox 158 plays MKV and MOV, HEVC Main/Main10, AV1 and VP9, but not 10-bit H.264, AC-3/E-AC-3, or FLAC in MKV.
      - `maxChannelCount` was 2 everywhere (stereo output).
    - Safari on macOS (recorded by the owner, 2026-10-19):
      - No MKV. MP4, WebM, MOV and MPEG-TS play (TS only with H.264 and AC-3/E-AC-3, and AAC in TS answered "").
      - HEVC answers only as `hvc1`, not `hev1`.
      - No AV1 on that Mac.
      - AC-3, E-AC-3, FLAC and ALAC play in MP4. FLAC in MSE answers only as `fLaC`, not `flac`.
      - MP3 inside MP4 answered "" under `mp4a.6B`, `mp4a.69` and `mp3`.
      - Native HLS is "maybe", and MSE is present.
      - `decodingInfo` reports HDR10 and HLG for HEVC Main10, but the display reported no HDR.
    - Raw answers of all four browsers: `docs/web-probes/`.
  - hls.js loaded with `import('hls.js')` from a `js()` function works in Kotlin/JS (webpack), Kotlin/Wasm (webpack) and Vite. It
    becomes a separate chunk of about 177 KB (gzip), loaded on the first HLS play.
  - A cross-origin `<track>` needs `crossorigin="anonymous"` on the `<video>` (Jellyfin answers with CORS); without it, Chromium refused the
    subtitle file.
  - Media Session metadata and action handlers set from Kotlin are present (`navigator.mediaSession.metadata`), and the owner saw the title
    and play/pause in the Windows media flyout.
  - hls.js gives up on a segment after 10 s without a first byte (`fragLoadTimeOut`), while Jellyfin holds a segment request until ffmpeg
    has written it. A software-only server re-encoding 4K HEVC 10-bit to H.264 had no first segment before its reverse proxy's 504 (even
    capped at 1080p, 20 Mb/s); with NVENC, segments came in 0.1–0.2 s. A web engine needs longer first-byte timeouts and error recovery
    (`startLoad()`, `recoverMediaError()`), and 4K transcodes need hardware acceleration on the server or a quality limit.
  - HDR through hls.js:
    - For an HDR source, Jellyfin's master playlist lists the copied stream (`VIDEO-RANGE=PQ`, `hvc1.2.4...`) next to tone-mapped
      `VIDEO-RANGE=SDR` variants (HEVC and H.264) at the same bandwidth. hls.js 1.7.3 picked SDR unless told otherwise.
    - `videoPreference: { preferHDR: true, allowedVideoRanges: ['SDR', 'PQ'] }` made Chrome decode HEVC Main 10 with PQ.
    - `chrome://media-internals` (`edge://media-internals`) shows the decoder's profile and colour space per player.
    - `(video-dynamic-range: high)` was false in Chrome and Edge with an HDR display, while `(dynamic-range: high)` was true.
  - Compose for web's canvas did not follow a runtime `devicePixelRatio` change (browser zoom): its backing size stayed at the old ratio,
    so zoomed text is blurry (owner; reproduced with CDP `Emulation.setDeviceMetricsOverride`, CMP 1.12.1).

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
- **Checked in the spike** (2026-10-19, ADR 0011: the same app built three ways on one Kotlin core):
  - `js()` works in the shared `webMain` source set for both `js` and `wasmJs` (Kotlin 2.4.20, with `kotlin.js.ExperimentalWasmJsInterop`),
    including Kotlin lambdas passed as callbacks and `import()`. `kotlinx-browser` 0.5.0 DOM types work in `webMain` too.
  - Compose HTML:
    - ViewModels held per page (a `ViewModelStore` cleared in `DisposableEffect`) and `collectAsState()` work.
    - Compose HTML removes attributes set from outside on recomposition (it dropped `crossorigin` from `<video>`), so declare them in
      the composable.
  - Kotlin/JS library for TypeScript:
    - `binaries.library()`, `useEsModules()`, `generateTypeScriptDefinitions()` and `outputModuleName` give `current-core.mjs` +
      `.d.mts`. Generic exported classes and function types (`() -> Unit`) export cleanly.
    - Ktor's JS client contains `import('ws')` (Node only), which Vite must alias to an empty module.
    - React's `StrictMode` double mount disposes a Kotlin ViewModel created in `useMemo`.
  - Compose for web with `HtmlElementView`:
    - Elements go to an interop layer after the canvas host, in the light DOM, so password managers and link handling see them.
    - They also take the wheel. Re-dispatching it to the canvas, which sits in the open shadow root of the host `div`, restores
      scrolling.
    - Their size comes from Compose.
    - Accessibility: `DropdownMenuItem` is exposed as a button, and a closed menu stayed in the accessibility tree. `window.find`
      matches the hidden accessibility text.
  - Sizes, load times and build times per prototype: ADR 0011.

## Kotlin/JS and Wasm build tooling (checked 2026-10-19)
- Kotlin's JS/Wasm toolchain installs npm packages with yarn and pins Karma as `github:Kotlin/karma#6.4.5`, resolved to a codeload tarball at
  commit `239a8fc984584f0d96b1dd750e7a5e2c79da93a6` (`kotlin-js-store/yarn.lock`).
  - On a Windows machine with direct network access, the tarball downloaded without trouble.
  - Cloud sessions get HTTP 403 for codeload tarballs from their GitHub proxy. The suggested workarounds are not tried yet: cloning
    Kotlin/karma at that commit and pointing yarn at the local copy, or an offline npm option of the Kotlin Gradle plugin.
- With a committed `kotlin-js-store/yarn.lock`, adding a JS module fails `kotlinStoreYarnLock` ("Lock file was changed") until
  `kotlinUpgradeYarnLock` runs (`kotlinWasmUpgradeYarnLock` for `kotlin-js-store/wasm/yarn.lock`).
- Development and production Wasm builds of one module share `build/wasm/packages/<module>/kotlin`: running both in one Gradle
  invocation fails task validation, and a production distribution built after a development one shipped the 25 MB development Wasm
  until `wasmJsProductionExecutableCompileSync --rerun`.
- `wasmJsBrowserDevelopmentRun --continuous` once failed with `OutOfMemoryError` in the Kotlin daemon (`kotlin.daemon.jvmargs=-Xmx3072M`)
  after a few incremental builds; `gradlew --stop` and a restart fixed it.
- Seen while building the web shell (2026-10-19, Kotlin 2.4.20, Vite 8.3.4, npm 10.9, Node 22.17 on Windows 11):
  - A library distribution is assembled from the root build's `build/js/packages/<outputModuleName>`, which is shared by every module
    with that `outputModuleName`. The spike's `current-core` files were still in it and were copied next to the new module's.
    Not tried yet: deleting that generated folder. CI starts clean, and Vite only loads what `current-core.mjs` imports.
  - Vite's dev server refuses files outside its root unless `server.fs.allow` lists them (the `:web` library is in `web/build`).
    `appType: 'spa'` (the default) serves `index.html` for unknown paths in both `vite` and `vite preview`, so History API URLs reload.
  - `tsc` over `vite.config.ts` needs `@types/node` for `node:url`. It is left out of the type check rather than adding the package.
  - `npm query .prod` lists the root package itself (location `""`) besides the production dependencies.
  - Node 22 runs a `.bat` file through `spawn` only with `shell: true`, given by its full path.
  - JetBrains `lifecycle-viewmodel` 2.11.0's `viewModelScope` needs a main dispatcher, which plain JVM tests lack ("Module with the
    Main dispatcher is missing"), so ViewModels take an optional scope for tests instead of adding `kotlinx-coroutines-test`.
  - The browser pane's Chromium answered "false" to both Dolby Vision probes (`dvh1.05.06`, `dvh1.08.06`) through `decodingInfo`.
    Chromium 152 also gave an empty `canPlayType` and a false `MediaSource.isTypeSupported` for `dvh1.05.06`, `dvh1.08.06`,
    `dvhe.05.06` and `dav1.10.06`, so the `canPlayType` spelling of the Dolby Vision probes cannot turn Dolby Vision on in Chrome.
- Jellyfin 12.2's range types (source at tag v12.2: `MediaStream.GetVideoColorRange`, `ConditionProcessor`):
  - Dolby Vision is labelled by profile and base layer:
    - profile 5 → `DOVI`;
    - profile 8 → `DOVIWithHDR10`, `DOVIWithSDR` or `DOVIWithHLG`, and `DOVIWithHDR10Plus` when the HDR10 base layer carries HDR10+;
    - profile 7 → `DOVIWithEL` or `DOVIWithELHDR10Plus`;
    - profile 10 (AV1) → `DOVI` or the same `DOVIWith*` types;
    - out-of-spec files → `DOVIInvalid`;
    - profiles 4 and 9, and anything else, count as plain SDR.
  - Condition matching accepts `HDR10Plus` wherever `HDR10` is allowed, and no other substitution, so each `DOVIWith*` type must be
    listed to be accepted.
  - Jellyfin's own web client probes Dolby Vision with `canPlayType` (`dvh1.05.06`, `dvh1.08.06`, `dav1.10.06`). It advertises base-layer
    playback (`DOVIWithHDR10` and the like) without a Dolby Vision decoder only on Tizen, VIDAA and webOS TVs, and profile 7 only on webOS.
  - `VideoRanges.rangeTypes` in `core:playback` applies this for every engine, from per-codec facts each engine fills in.

## Libraries per ADR 0002 (checked 2026-10-19 against Kotlin 2.4.20, CMP 1.12.1, AGP 9.1.1)
Newest versions on Maven Central that day. Targets come from each artifact's Gradle module metadata (`.module`), licences from its POM,
and resolved versions from `:desktopApp:dependencies`.
- **Ktor client 3.6.0** (`core:network`), Apache-2.0. Built with Kotlin 2.3.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.11.0.
  - `ktor-client-core`, `-content-negotiation`, `ktor-serialization-kotlinx-json` and `ktor-client-mock` publish jvm, js, wasm-js,
    iosArm64, iosSimulatorArm64, macosArm64, tvosArm64 and tvosSimulatorArm64. Android uses the jvm artifacts.
  - Engines: `ktor-client-android` (HttpURLConnection) and `ktor-client-java` (the JDK's `HttpClient`) are jvm only. `ktor-client-darwin`
    covers iOS, macOS and tvOS. `ktor-client-js` covers js and wasm-js. Unlike OkHttp, none of them adds a third-party library.
  - On the JVM it pulls `slf4j-api` 2.0.19 (MIT).
  - Exceptions (checked 2026-10-20 in the 3.6.0 jvm classes and our tests): `HttpRequestTimeoutException` extends `IOException`,
    `ConnectTimeoutException` extends `ConnectException`, and `ResponseException` (thrown with `expectSuccess`) extends
    `IllegalStateException`. Their messages contain the full request URL. A refused connection on the Java engine is an `IOException`.
  - `ktor-client-logging` 3.6.0 (checked 2026-10-20 in `Logging.kt` at the 3.6.0 tag): `sanitizeHeader` masks header values only; every
    format writes the request URL unchanged, query included; body logging is filtered only by a `bodyFilter`. Not used (ADR 0015).
- **kotlinx.serialization 1.12.0** (`core:network`), Apache-2.0. Released 2026-10-19 and "based on Kotlin 2.4.10"; every target.
  The compiler plugin is `org.jetbrains.kotlin.plugin.serialization` at Kotlin's own version. Ktor asks for 1.11.0 and Gradle takes 1.12.0.
  Behaviour change in 1.12.0: every exception thrown while decoding is wrapped in a `JsonException`.
  Source: https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.12.0
- **Koin 4.2.2** (`core:network` defines modules, `shared` starts them), Apache-2.0. Built with Kotlin 2.3.20.
  - `koin-core` publishes jvm, js, wasm-js, iOS, macosArm64 and tvOS; Android uses the jvm artifact.
  - `koin-compose` publishes android, jvm, js, wasm-js, iOS and macOS, but not tvOS. That is fine, because it is only used on the Compose side.
  - `koin-compose` asks for CMP 1.10.2, and `koin-compose-viewmodel` for lifecycle 2.9.6. They resolve to 1.12.1 and 2.11.0.
  - Compose takes `KoinApplication(configuration = koinConfiguration { ... })`. `onClose` callbacks go through
    `withOptions { onClose { } }`, since `onClose` applies to a `BeanDefinition`.
- **JetBrains Navigation Compose 2.10.0-rc01** (`shared`), Apache-2.0.
  - CMP 1.12.1's release notes pair it with Navigation 2.10.0-beta01 (Jetpack Navigation 2.10.0), Navigation3 1.1.2, lifecycle 2.11.0 and
    savedstate 1.4.0. rc01 (2026-10-06) is the newest build of that line, and there is no stable 2.10.0 yet. The latest stable, 2.9.2,
    is built against Compose 1.9.
  - It publishes android, jvm (desktop), js, wasm-js, iosArm64, iosSimulatorArm64 and macosArm64, and asks for Compose 1.11.0 and Kotlin 2.2.20.
  - On Android and desktop it depends on Google's `androidx.navigation:navigation-compose` 2.10.0. That raises androidx savedstate to
    1.5.0, where CMP 1.12.1 has 1.4.0.
  - Source: https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1
- **Coil 3.6.3** (`composeUi`), Apache-2.0. Built with Kotlin 2.4.10.
  - `coil-compose` publishes android, jvm, js, wasm-js, iosArm64, iosSimulatorArm64 and macosArm64, and asks for CMP foundation 1.12.0.
  - `coil-network-ktor3` asks for Ktor 3.1.0 or newer (it resolves to 3.6.0) and loads images through Ktor. Unless it is given our
    client, it makes its own default `HttpClient()`. That client should find the one engine `core:network` puts on each platform; this
    is untried, because no image is loaded yet.
  - It pulls Okio 3.18.1 (Apache-2.0).
- **JNA 5.19.1** (`core:database`, JVM only; added 2026-10-20 for ADR 0017), `jna` and `jna-platform`, both LGPL-2.1-or-later or
  Apache-2.0 (POM). The desktop video spike used the same version (ADR 0010).
  - `jna-platform` wraps DPAPI (`Crypt32Util.cryptProtectData`) but not Credential Manager: its `Advapi32` has no `CredRead`/`CredWrite`
    (checked with `javap` in the 5.19.1 jar), so `WindowsCredentials` maps them itself.
- The web (`web` umbrella, Kotlin/JS) gets Ktor, kotlinx.serialization and Koin through `core:network`. It uses neither Navigation (it
  has the core's History API router) nor Coil (it uses plain `<img>`).
  - Kotlin's yarn lockfile (`kotlin-js-store/yarn.lock`, upgraded with `kotlinUpgradeYarnLock`) gains `ws` 8.20.1 (MIT) for
    `ktor-client-js` and `@js-joda/core` 3.2.0 (BSD-3-Clause). The second comes from `kotlinx-datetime` 0.8.0, which
    `ktor-serialization-kotlinx-json` pulls in.
  - Since 2026-10-20, a yarn resolution in the root `build.gradle.kts` lifts `ws` to 8.21.3 (8.20.1 has a memory exhaustion DoS, fixed
    in 8.21.0). After changing a resolution, `kotlinUpgradeYarnLock` reused the old generated `package.json` until it ran with
    `--rerun-tasks`.
  - The Vite bundle has none of them yet, because the facade does not call `core:network` and Kotlin/JS drops the unused code. Once it
    does, the `ws` stub in `webApp/vite.config.ts` applies (ADR 0011), and `@js-joda/core` has to be checked again: it may need to be
    in `webApp/package.json` and in the npm licence check.

## Desktop settings storage (checked 2026-10-20 on Windows 11 with JDK 21; ADR 0016)
- `java.util.prefs` on Windows writes under `HKCU\Software\JavaSoft\Prefs`, one key shared by every Java app (`WindowsPreferences`
  holds the string `Software\JavaSoft\Prefs`; the key on this machine already held other vendors' nodes). Uppercase letters are
  escaped with `/` (`/Jet/Brains.`). Limits: `MAX_KEY_LENGTH` 80, `MAX_VALUE_LENGTH` 8192, `MAX_NAME_LENGTH` 80 (`javap -constants`).
- Not verified here (no Linux machine): that the Linux implementation writes to `~/.java/.userPrefs` and syncs on a timer. ADR 0016 uses
  a JSON file instead, so nothing depends on it.
- The desktop app keeps settings in `%APPDATA%\Current` on Windows and `$XDG_CONFIG_HOME/current` on Linux, falling back to
  `~/.config/current` when the variable is unset or not an absolute path, as the XDG Base Directory specification asks
  (https://specifications.freedesktop.org/basedir-spec/latest/).
- A start of the desktop app with the new Koin modules created no folder: the store reads only, and writes on the first change.
- Not verified: whether an MSIX (Microsoft Store) package redirects `%APPDATA%` writes to a per-package folder (M8, Store edition).

## Secrets on the desktop (checked 2026-10-20; ADR 0017)
- Windows Credential Manager through JNA (`CredWriteW`, `CredReadW`, `CredDeleteW`, `CredFree`; generic credentials, persisted on the
  local machine): a round trip with a non-ASCII value, overwrite, delete and a missing key (`ERROR_NOT_FOUND`, 1168) passed on Windows 11
  with JDK 21, and `cmdkey /list` showed no entry left. A value over 2560 bytes is refused before the call.
- Linux, checked in WSL (Ubuntu 24.04, OpenJDK 21.0.12, `libsecret-1-0`, `gnome-keyring`), with the test classes built on Windows:
  - GNOME Keyring unlocked in a `dbus-run-session` (`echo -n <password> | gnome-keyring-daemon --unlock --components=secrets`):
    `secret_password_store_sync`, `lookup` and `clear` with our schema round-trip a value.
  - **A lookup is no availability check.** With no session bus at all, `secret_password_lookup_sync` returned NULL without a `GError`.
    So the app treats the Secret Service as available when a session bus exists (`DBUS_SESSION_BUS_ADDRESS`, or `$XDG_RUNTIME_DIR/bus`)
    and `libsecret` loads.
  - **Calls can block on the user.** On a bus without an unlocked keyring, D-Bus activation started `gnome-keyring-daemon`, which asked
    `gcr-prompter` to create a keyring; under WSLg that prompt can open a window. `secret_password_store_sync` then waits until the
    prompt is answered. The app runs each call on a worker thread with a 2-minute limit and falls back to memory; with no display for
    the prompt, the store failed within the test's 10-second limit.
  - Without a keyring, the opt-in secrets file and its folder got `rw-------` and `rwx------`.
- WSL (Ubuntu) runs a per-user session bus at `/run/user/<uid>/bus` and sets `DBUS_SESSION_BUS_ADDRESS` to it.
- Not verified: KWallet's Secret Service interface (KDE Plasma), a real desktop session's unlock prompt, Flatpak or Snap sandboxes.

## Credential storage in other apps (checked 2026-10-20; ADR 0017's alternatives)
Read from public documentation of a widely used app framework's secure-storage API, a widely used Git credential helper, and a widely
used command-line tool; named in the conversation, not here (CLAUDE.md).
- Windows: the framework encrypts with DPAPI and leaves the encrypted data to the app; the credential helper defaults to Credential
  Manager (and offers DPAPI-protected files), noting that Credential Manager does not persist over a network or SSH session.
- Linux: all three use the Secret Service (libsecret, GNOME Keyring, KWallet) first. Without one: the framework encrypts with a
  hard-coded password ("basic_text"); the command-line tool silently writes a plain-text file (with a flag to force it); the credential
  helper has no default store on Linux and offers Secret Service, GPG/pass, an in-memory cache, or plain-text files it warns against.
- macOS: the Keychain everywhere.

## Dependency graph and Dependabot (checked 2026-10-20)
- GitHub's automatic dependency submission ran a dynamic "Automatic Dependency Submission" workflow and reported every Gradle
  configuration under `settings.gradle.kts`. All 52 Maven alerts on 2026-10-20 were build tooling: Netty from the Android Gradle
  plugin's test-device tooling (`_internal-unified-test-platform-*`); Bouncy Castle, jdom2, jose4j, Apache httpclient, commons-lang3
  and OpenTelemetry on the plugin classpath. None was in `:desktopApp` `runtimeClasspath` or `:androidApp` `releaseRuntimeClasspath`.
- CI now submits instead (`gradle/actions/dependency-submission@v6`, job `dependency-graph`), limited to the shipped configurations of
  `:androidApp`, `:desktopApp`, `:web` and `:apple`. The plugin matches its project and configuration regexes against the whole
  project path and configuration name (`Regex.matches`). Automatic dependency submission must stay off (Settings, Advanced Security,
  Dependency graph), or it reports the build tooling again.
- The 4 npm alerts were in `kotlin-js-store/yarn.lock` (Kotlin/JS tooling: karma, mocha, chokidar, and `ws`); `npm audit` on `webApp`
  found none.
  Sources: https://github.com/gradle/actions/blob/main/docs/dependency-submission.md ,
  https://github.com/gradle/github-dependency-graph-gradle-plugin

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
  Stash server is nonetheless listed in the US App Store (16+), so adult content is judged per store at review (ADR 0014). Sources: https://developer.apple.com/app-store/review/guidelines/ , https://learn.microsoft.com/en-us/windows/apps/publish/store-policies ,
  https://f-droid.org/docs/Inclusion_Policy/
- Apple fallback candidates: MPVKit (LGPLv3 by default, GPL only in its `-GPL` products; static libraries, so relinking means publishing our
  app as objects or source), VLCKit (LGPL-2.1+), an FFmpeg + VideoToolbox + Metal engine of our own (LGPL). KSPlayer is GPL-3.0 (LGPL only with a
  paid licence). Sources: https://github.com/mpvkit/MPVKit , https://github.com/videolan/vlckit , https://github.com/kingslay/KSPlayer
- Android: Media3 is Apache-2.0; its FFmpeg extension decodes audio only (AC-3, E-AC-3, DTS, TrueHD among others); AV1 through dav1d (BSD-2).
  mpv-android builds GPLv3, so an LGPL libmpv would need our own build. Source: https://github.com/androidx/media/tree/release/libraries
- Shared pieces: libass (ISC), libplacebo (LGPL-2.1+, can convert Dolby Vision profile 5; composes profile 7 only in its unreleased master,
  see "HDR and Dolby Vision: desktop"). Without a Dolby licence the safe route is the HDR10/SDR base layer.
  Sources: https://github.com/libass/libass , https://github.com/haasn/libplacebo
- Patents are separate from copyright: through OS decoders (VideoToolbox, MediaCodec, Media Foundation) the OS or device maker is the licensee;
  shipping FFmpeg's own HEVC or AAC decoders makes us the distributor of a decoder, so prefer OS and hardware decoders for those
  (https://ffmpeg.org/legal.html , https://accessadvance.com/licensing-programs/hevc-advance/ , https://www.via-la.com/licensing-programs/aac/).
  No Dolby or DTS logos or claims without a licence; neutral codec names (E-AC-3, DTS-HD MA) in the playback info overlay.

## HDR and Dolby Vision: Apple, AVPlayer (checked 2026-10-19, documentation only, no device)
Input for the shared `VideoRangeType` decision (ADR 0007, ADR 0011 "HDR"): what each engine can detect and play.
- **Device and display.**
  - `AVPlayer.eligibleForHDRPlayback` (class property; iOS, iPadOS, tvOS 13.4+, macOS 10.15+): "whether the current device can present
    content to an HDR display". It is one Boolean with no per-format answer. It is not key-value observable; watch
    `eligibleForHDRPlaybackDidChangeNotification` (posted when a display is connected or disconnected, among other changes).
  - `AVPlayer.availableHDRModes` (bitfield `hlg`, `hdr10`, `dolbyVision`; iOS, tvOS 11.2+, never on macOS) is deprecated in 26.0: "Use
    eligibleForHDRPlayback instead". Its page also says Mac Catalyst apps play no HDR on Intel Macs (HLS picks the SDR variant, files are
    tone-mapped to SDR).
  - Screen headroom: `UIScreen.potentialEDRHeadroom` / `currentEDRHeadroom` (iOS, tvOS 16+). On macOS,
    `NSScreen.maximumPotentialExtendedDynamicRangeColorComponentValue` (10.15+; above 1.0 means EDR, fixed when the `NSScreen` is created)
    and `maximumExtendedDynamicRangeColorComponentValue` (current value; changes post `didChangeScreenParametersNotification`).
  - tvOS mode switching:
    - A custom player sets the window's `AVDisplayManager.preferredDisplayCriteria` from the asset's `preferredDisplayCriteria`. Load it
      asynchronously; the synchronous property is deprecated in tvOS 16.
    - `AVPlayerViewController` does this itself (`appliesPreferredDisplayCriteriaAutomatically`).
    - The TV switches only when the user has turned on Match Content (`isDisplayCriteriaMatchingEnabled`, tvOS 11.3+).
    - Sample-buffer players build `AVDisplayCriteria(refreshRate:formatDescription:)` (tvOS 17+).
  - Decoders: `VTIsHardwareDecodeSupported(_:)` (iOS, tvOS 11+, macOS 10.13+) per codec type, e.g. `kCMVideoCodecType_HEVC`,
    `kCMVideoCodecType_DolbyVisionHEVC`, `kCMVideoCodecType_AV1`.
  - Media: `AVURLAsset.isPlayableExtendedMIMEType(_:)` takes a MIME type with codecs. `AVMediaCharacteristic.containsHDRVideo` (iOS, tvOS
    14+, macOS 11+) marks HDR tracks. HLS variants expose `AVAssetVariant.VideoAttributes.videoRange` (`AVVideoRange` `sdr`, `hlg`, `pq`)
    and `codecTypes` (iOS, tvOS 15+, macOS 12+).
- **What the HLS Authoring Specification requires** (revision 2025-06-24):
  - HEVC and AV1 only in fMP4 (rules 1.5, 1.39). HDR HEVC must be HDR10, HLG or Dolby Vision (1.7). `VIDEO-RANGE` is required unless
    everything is SDR (9.16).
  - Dolby Vision: profile 5 up to level 7 (1.9). The iOS amendment allows profile 5 or 10 up to level 9 (1.9a); visionOS allows profile 5,
    and profile 20 (MV-HEVC) for stereo. Profiles 7 and 8 are not mentioned. AirPlay 2 TVs: offer both Dolby Vision and HDR10 (9.24).
  - Sample entries with parameter sets out of band SHOULD be used: `hvc1` or `dvh1`, not `hev1` or `dvhe` (1.10).
- **Profile 8 in HLS:** Apple's HLS 2nd edition draft (`draft-pantos-hls-rfc8216bis-22`, May 2026) gives this example for Dolby Vision
  8.4: `CODECS` with the base layer `hvc1.2.4.L153.b0` and `SUPPLEMENTAL-CODECS` `dvh1.08.07/db4h` (codec, then a compatibility brand).
- **Profile 8.4 files:** Apple's Dolby Vision guide (2021, iOS 14.1+, written for iPhone camera video in `.mov`):
  - `AVPlayer` with `AVPlayerLayer`, and `AVSampleBufferDisplayLayer`, play profile 8.4 with no extra code when `eligibleForHDRPlayback`
    is true.
  - A `VTDecompressionSession` must keep `kVTDecompressionPropertyKey_PropagatePerFrameHDRDisplayMetadata` true and use 10-bit buffers
    (`x420`).
  - `AVPlayerItem.appliesPerFrameHDRDisplayMetadata` (default true; iOS, tvOS 14+, macOS 11+) turns the per-frame metadata off.
- **HDR10+:** no Apple document mentions it. AVFoundation, VideoToolbox and the HLS spec name HDR10, HLG and Dolby Vision only.
- **Containers:** the types AVFoundation opens come from `AVURLAsset.audiovisualContentTypes` (`audiovisualTypes()` is deprecated in
  27.0). Safari on macOS played no MKV (ADR 0011). macOS 15+ (not iOS or tvOS) has the MediaExtension framework: format readers and
  decoders embedded in a host app, available to apps that opt in. Dolby Vision from MKV therefore reaches AVPlayer only through a server
  remux to fMP4 HLS (see "Jellyfin 12.2").
- Sources: https://developer.apple.com/documentation/avfoundation/avplayer/eligibleforhdrplayback ,
  https://developer.apple.com/documentation/avfoundation/avplayer/availablehdrmodes ,
  https://developer.apple.com/documentation/avfoundation/avdisplaycriteria , https://developer.apple.com/documentation/avkit/avdisplaymanager ,
  https://developer.apple.com/documentation/uikit/uiscreen/potentialedrheadroom ,
  https://developer.apple.com/documentation/appkit/nsscreen/maximumpotentialextendeddynamicrangecolorcomponentvalue ,
  https://developer.apple.com/documentation/videotoolbox/vtishardwaredecodesupported(_:) ,
  https://developer.apple.com/documentation/avfoundation/avplayeritem/appliesperframehdrdisplaymetadata ,
  https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices ,
  https://datatracker.ietf.org/doc/draft-pantos-hls-rfc8216bis/ ,
  https://developer.apple.com/av-foundation/Incorporating-HDR-video-with-Dolby-Vision-into-your-apps.pdf ,
  https://developer.apple.com/documentation/mediaextension

## HDR and Dolby Vision: Android, Media3 (checked 2026-10-19 against the Android reference and the Media3 1.11.1 source)
- **Display:**
  - `Display.getHdrCapabilities().getSupportedHdrTypes()` (API 24) is deprecated in API 34 in favour of `Display.getMode()` and
    `Mode.getSupportedHdrTypes()` (API 34).
  - Types: `HDR_TYPE_DOLBY_VISION` (1), `HDR_TYPE_HDR10` (2), `HDR_TYPE_HLG` (3) since API 24, `HDR_TYPE_HDR10_PLUS` (4) since API 29,
    `HDR_TYPE_HLG_PLUS` (6) since API 37.
  - `Display.isHdr()` (API 26) answers "any HDR type". `getHdrSdrRatio()` (API 34) gives the HDR/SDR luminance ratio when
    `isHdrSdrRatioAvailable()`.
  - `DisplayManager.getHdrConversionMode()` (API 34) returns the user's HDR conversion setting.
- **Decoders:** `MediaCodecInfo.CodecCapabilities.profileLevels` per MIME type. `CodecProfileLevel` constants:
  - Dolby Vision: `DolbyVisionProfileDvheStn` (profile 5), `DvheDtb` (7), `DvheSt` (8, API 27), `DvavSe` (9, API 27), `Dvav110` (10, API 30).
  - HDR: `HEVCProfileMain10HDR10` (API 24), `HEVCProfileMain10HDR10Plus`, `AV1ProfileMain10HDR10`, `AV1ProfileMain10HDR10Plus`,
    `VP9Profile2HDR10Plus` (API 29), `VP9Profile2HDR` (API 24).
  - Dolby Vision decoders are `video/dolby-vision`. Media3 also maps two vendor names: `OMX.MS.HEVCDV.Decoder` as `video/hevcdv`, and
    Realtek's decoders as `video/dv_hevc`.
- **Media3 1.11.1 (2026-09-10):**
  - Extractors:
    - MP4 reads `dvav`, `dva1`, `dvhe`, `dvh1` and `dav1` sample entries and `dvcC`/`dvvC`/`dvwC` boxes.
    - Matroska reads `dvcC`/`dvvC` from `BlockAdditionMapping`. Both give `video/dolby-vision` with codecs `dvhe.PP.LL`, `dvav...` or
      `dav1...`, so Dolby Vision in MKV is recognised.
    - `DolbyVisionConfig` keeps profile and level only, not the base-layer compatibility id.
    - HLS `SUPPLEMENTAL-CODECS` and DASH `scte214:supplementalCodecs` are parsed to detect Dolby Vision.
  - Fallback without Dolby Vision (API 26+): `MediaCodecVideoRenderer` checks the *default* display (`isHdr()` and
    `HDR_TYPE_DOLBY_VISION` through the deprecated `getSupportedHdrTypes()`). If it lacks Dolby Vision, the renderer first tries
    `MediaCodecUtil.getAlternativeCodecMimeType`:
    - profiles 4 and 8 → HEVC decoder;
    - profile 9 → H.264;
    - profile 10 → AV1, unless the track's colour info is PQ with full range (taken as 10.0, no fallback);
    - profiles 5 and 7 → no fallback ("not backward compatible" and "deprecated and is not always backward compatible").

    A fallback reports `DECODER_SUPPORT_FALLBACK_MIMETYPE`. The 8.x sub-profiles (8.1, 8.2, 8.4) are not told apart, so the HEVC decoder
    gets the HDR10, SDR or HLG base layer as is.
  - Track selection prefers other tracks to Dolby Vision when the display does not support it (release notes, ExoPlayer #8944).
  - Without a fallback, the renderer uses whatever `video/dolby-vision` decoder the device has. With none, profiles 5, 7 and 10.0 need the
    server. What a Dolby Vision decoder outputs to a display without Dolby Vision is not documented.
  - HDR10+:
    - In-band SEI stays in the HEVC bitstream for the decoder.
    - Out-of-band metadata (ITU-T T.35 in supplemental data, e.g. WebM) goes to `MediaCodec` as `KEY_HDR10_PLUS_INFO` when the decoder
      reports out-of-band HDR10+ support (API 29+).
    - What the screen does with it is up to the device.
- Sources: https://developer.android.com/reference/android/view/Display.HdrCapabilities ,
  https://developer.android.com/reference/android/view/Display.Mode , https://developer.android.com/reference/android/view/Display ,
  https://developer.android.com/reference/android/media/MediaCodecInfo.CodecProfileLevel ,
  https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java ,
  https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java ,
  https://github.com/androidx/media/blob/1.11.1/libraries/container/src/main/java/androidx/media3/container/DolbyVisionConfig.java ,
  https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md

## HDR and Dolby Vision: desktop, libmpv with `vo=gpu-next` (checked 2026-10-19 in the source; nothing played)
Releases at the time: mpv 0.41.0 (2025-12-21), libplacebo v7.360.1 (2026-03-13), FFmpeg 8.1.3 (2026-09-21). Master of mpv and libplacebo
as of 2026-10-18.
- **Decoding:** FFmpeg's HEVC decoder parses the RPU (NAL type 62) and attaches `AV_FRAME_DATA_DOVI_METADATA`. It does so in
  `hevc_frame_start` whether or not a hwaccel such as `d3d11va` decodes the slices. The native AV1 decoder and libdav1d do the same for
  profile 10. Jellyfin's `EncodingHelper` relies on the same split: "Only native SW decoder, HW accelerator and hevc_rkmpp decoder can
  parse dovi rpu".
- **Rendering:** mpv hands the metadata to libplacebo (`pl_map_avdovi_metadata`), which reshapes Dolby Vision in its own shaders
  (`PL_COLOR_SYSTEM_DOLBYVISION`).
  - Profile 5 comes out as BT.2020 PQ, or tone-mapped SDR.
  - For 8.1, 8.4 and 10, the RPU reshaping is applied over the base layer.
  - The display never receives Dolby Vision: output is HDR10 (`target-colorspace-hint`) or SDR.
  - `--target-colorspace-hint-mode=source-dynamic` (experimental) sends per-scene luminance as HDR10 metadata and "does not send full
    HDR10+ or Dolby Vision metadata".
- **Profile 7 in the releases:**
  - mpv 0.41.0 maps Dolby Vision metadata only when the RPU's `disable_residual_flag` is set. libplacebo 7.360.1's `pl_map_avframe` has
    the same rule ("Only automatically map DoVi RPUs that don't require an EL").
  - Profile 7 therefore plays as its HDR10 base layer: EL and RPU are ignored.
- **Profile 7 in master (unreleased):**
  - libplacebo API 364 maps MEL-only RPUs. APIs 366-370 compose FEL through `pl_frame.enhancement_layer` (commit 801b681, "renderer: add
    support for Dolby Vision FEL merging", authored 2026-05-16, merged 2026-07-01).
  - mpv master (2026-07-01) splits an EL interleaved as NAL type 63 into a virtual track with FFmpeg's new `dovi_split` bitstream filter.
    That filter is in FFmpeg master since 2026-05-31; it is not in 8.1.3, nor in jellyfin-ffmpeg 8.1.3-1.
  - mpv decodes that track with a second decoder and passes the frames as the enhancement layer. It also pairs separate BL and EL tracks
    (libavformat `AV_STREAM_GROUP_PARAMS_DOLBY_VISION`, 62.19.100+, and `demux_mkv`).
  - `vf=format:enhancement-layer=no` turns FEL off. `vf=format:dolbyvision=no` strips Dolby Vision metadata, leaving the base layer, and
    is in 0.41.0. `vf=format:hdr10plus=no` strips HDR10+.
- **HDR10+:** mpv reads `AV_FRAME_DATA_DYNAMIC_HDR_PLUS` into libplacebo's per-scene HDR metadata. It drives tone mapping (e.g.
  `tone-mapping=st2094-40`) and, with `source-dynamic`, per-scene HDR10 values. The HDR10+ metadata itself never reaches the display.
- **Build and licence:**
  - libplacebo's `dovi` meson feature (default `auto`) enables reshaping and needs FFmpeg's `libavutil/dovi_meta.h` (libavutil
    57.16.100+).
  - The optional `libdovi` dependency (1.6.7+; the library of dovi_tool, MIT) adds `pl_hdr_metadata_from_dovi_rpu`: RPU brightness
    metadata for tone mapping.
  - Neither needs a Dolby SDK, key or licence file. libplacebo is LGPL-2.1+.
  - Whether a distributed build that applies Dolby Vision reshaping needs a Dolby licence is a legal question these sources do not answer.
    ADR 0014 keeps full processing to the Private edition; the Store edition plays the base layer (`dolbyvision=no`).
  - Profiles 5 and 10.0 have no base layer to fall back on (Jellyfin's `DOVI` range), so without reshaping they need the server.
- Sources: https://github.com/mpv-player/mpv/blob/v0.41.0/video/mp_image.c , https://github.com/mpv-player/mpv/blob/master/demux/dovi_split.h ,
  https://github.com/mpv-player/mpv/blob/master/DOCS/man/vf.rst , https://github.com/mpv-player/mpv/blob/master/DOCS/man/options.rst ,
  https://github.com/haasn/libplacebo/blob/v7.360.1/src/include/libplacebo/utils/libav_internal.h ,
  https://github.com/haasn/libplacebo/commit/801b6812fb3fcadc7a44ab5f8585d3a1b5302a5f , https://github.com/haasn/libplacebo/blob/master/meson.build ,
  https://github.com/FFmpeg/FFmpeg/blob/master/doc/bitstream_filters.texi , https://github.com/FFmpeg/FFmpeg/blob/n8.1.3/libavcodec/hevc/hevcdec.c ,
  https://github.com/quietvoid/dovi_tool

## HDR and Dolby Vision: Jellyfin 12.2 HLS remux (checked 2026-10-19 in the v12.2 source and jellyfin-ffmpeg 8.1.3-1 patches)
- **Classification** (`MediaStream.cs`, from the stream's Dolby Vision configuration):
  - profile 5 → `DOVI`;
  - 8 with compatibility id 1, 4 or 2 → `DOVIWithHDR10`, `DOVIWithHLG` or `DOVIWithSDR` (any other id → `DOVIInvalid`);
  - 7 → `DOVIWithEL`;
  - 10 with id 0, 1, 2 or 4 → `DOVI`, `DOVIWithHDR10`, `DOVIWithSDR` or `DOVIWithHLG`.
  - HDR10+ metadata turns them into `DOVIWithHDR10Plus`, `DOVIWithELHDR10Plus` or `HDR10Plus`.
  - A base layer whose colour tags do not match its id → `DOVIInvalid`.
- **Copy or transcode** (`EncodingHelper`, the range types the profile lists for the source codec):
  - `DOVI` sources are copied only if `DOVI` is listed.
  - `DOVIWith{HDR10,HLG,SDR}` are copied when the base layer's range is listed, and `HDR10Plus` when `HDR10` is.
  - Anything else is copied only when the metadata removal it needs is possible.
- **The Dolby Vision sample entry and configuration box need `DOVI` in the client's list for the output codec** (`DynamicHlsController`,
  "Only enable Dolby Vision remuxing if the client explicitly declares support for profiles without fallbacks"):
  - Sample entry: HEVC is copied with `-tag:v dvh1 -strict -2`, except profile 8, which gets `hvc1`; AV1 gets `dav1`. Without `DOVI`, HEVC
    is tagged `hvc1` and no `-strict` is added for the video.
  - Configuration box: FFmpeg's mov muxer writes `dvcC`/`dvvC`/`dvwC` only at `-strict unofficial` or lower ("Requires -strict
    unofficial"). jellyfin-ffmpeg patch 0027 copies the configuration into the HLS segment muxers and validates it (profile against codec,
    compatibility id against colour tags) before writing the box.
- **Master playlist** (`DynamicHlsHelper`, video copied):
  - With `DOVI` listed, profile 5 and 10.0 (`DOVI`) get an extra first variant `CODECS="dvh1.PP.LL"` (or `dav1.PP.LL`), `VIDEO-RANGE=PQ`,
    before the `hvc1` variant.
  - Whenever Dolby Vision is copied and not removed, 8.1, 8.4 and 10.x with a base layer get `SUPPLEMENTAL-CODECS="dvh1.PP.LL/db1p"`
    (HDR10) or `/db4h` (HLG) next to the base-layer `CODECS`. This does not depend on `DOVI` being listed.
  - SDR-based, profile 5 and profile 7 sources get no supplemental codec.
  - HDR10+ kept in a copy, without Dolby Vision, gets `SUPPLEMENTAL-CODECS="<codec>/cdm4"`.
  - Transcodes are always `VIDEO-RANGE=SDR`.
- **Profile 7: stripped in a remux, not converted.**
  - For a client that lists `HDR10` but not `DOVIWithEL`, `ShouldRemoveDynamicHdrMetadata` chooses `RemoveDovi`, and the video is still
    copied. The filter is `hevc_metadata=remove_dovi=1`, or FFmpeg's `dovi_rpu=strip=1` when the patched one is missing.
  - jellyfin-ffmpeg patch 0061 removes the access unit's last NAL if it is an RPU (62), then the new last NAL if it is EL (63), and
    drops the configuration record. Upstream `dovi_rpu=strip` deletes the last unit if it is 62 or 63, plus the record.
  - The result is an HDR10 stream. There is no profile 7 → 8.1 conversion (keep the RPU, drop the EL) in 12.2.
  - Other removals:
    - `DOVIInvalid` to a `DOVI` client: Dolby Vision is removed.
    - `DOVIWithHDR10Plus` to a `DOVI` client: HDR10+ is removed (`hevc_metadata=remove_hdr10plus=1`).
    - AV1 has matching `av1_metadata` options.
- **Tone mapping in transcodes:** `DOVI` sources (profile 5) are reshaped only by the software decoder or by NVDEC, VAAPI, D3D11VA,
  VideoToolbox and RKMPP (FFmpeg 7.1.1+) decoders. VideoToolbox tone mapping is not used for profile 5 ("does not produce correct mapping
  results").
- Sources: https://github.com/jellyfin/jellyfin/blob/v12.2/MediaBrowser.Model/Entities/MediaStream.cs ,
  https://github.com/jellyfin/jellyfin/blob/v12.2/MediaBrowser.Controller/MediaEncoding/EncodingHelper.cs ,
  https://github.com/jellyfin/jellyfin/blob/v12.2/Jellyfin.Api/Controllers/DynamicHlsController.cs ,
  https://github.com/jellyfin/jellyfin/blob/v12.2/Jellyfin.Api/Helpers/DynamicHlsHelper.cs ,
  https://github.com/jellyfin/jellyfin-ffmpeg/blob/jellyfin/debian/patches/0061-add-remove-dovi-hdr10plus-bsf.patch ,
  https://github.com/jellyfin/jellyfin-ffmpeg/blob/jellyfin/debian/patches/0027-pass-dovi-sidedata-to-hlsenc-and-mpegtsenc.patch ,
  https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c

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
- PlaybackInfo with a device profile (checked live 2026-10-19, read-only with an API key; ADR 0011):
  - An API key works as `Token` in the `MediaBrowser` header. `/UserViews`, `/Items` and `PlaybackInfo` then need the user passed
    (`userId`, `UserId`).
  - A non-default `AudioStreamIndex` keeps `SupportsDirectPlay: true`, although a browser plays only a file's default track, so a web
    client turns direct play off itself to switch audio.
  - The transcode reasons are in the `TranscodingUrl` query (`TranscodeReasons=`); the media source JSON had none.
  - HLS profile:
    - With an fMP4 HLS transcoding profile (`Container: mp4`) whose `VideoCodec` lists `h264,hevc`, HEVC sources that need only an audio
      transcode keep their video (`TranscodeReasons=AudioCodecNotSupported`).
    - With MPEG-TS and H.264 only, they were re-encoded.
  - Device profile conditions (checked live 2026-10-19):
    - A `CodecProfile` condition `VideoRangeType EqualsAny SDR|...` sends HDR10 sources to a tone-mapped transcode
      (`VideoRangeTypeNotSupported`).
    - `Width LessThanEqual 1920` caps the transcode (`MaxWidth=1920`, `VideoResolutionNotSupported`). Codec conditions also travel
      in the `TranscodingUrl` (`hevc-rangetype=`, `hevc-videobitdepth=`).
  - HLS codec lists:
    - In the fMP4 HLS profile, the first video and audio codec is what the server encodes to; any listed codec is copied when the
      source has it. E-AC-3 was copied with `aac,ac3,eac3,...`.
    - A source with more channels than `MaxAudioChannels` is transcoded anyway (`AudioChannelsNotSupported`).
  - `AudioStreamIndex` is ignored without `MediaSourceId` (the server then uses the default track).
  - Subtitles:
    - ASS, with a profile listing ASS as `Encode`, came back with `DeliveryMethod: Encode` and `SubtitleMethod=Encode` in the URL
      (burn-in).
    - SubRip, with only `vtt` as `External`, came back as an external `.vtt` `DeliveryUrl` (carrying `ApiKey`), under direct play too.
    - The test server had no PGS file, so PGS burn-in was not seen live.
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
  never stores a password and can renew the session silently: see "Seerr 3.5.0: sign-in with the app's Jellyfin session".
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

## Jellyfin 12.2: sign-in shapes (checked 2026-10-20 in the 12.2 OpenAPI spec, https://api.jellyfin.org/openapi/stable/jellyfin-openapi-12.2.json, and live)
- Live, 2026-10-20, against a 12.2.0 server behind HTTPS (`LiveJellyfinSmokeTest`, Quick Connect approved by hand in the web UI):
  - The address typed without a scheme resolved to `https`, and `/System/Info/Public` answered without credentials.
  - `GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate` (with the `MediaBrowser` header, no token), then `GET /QuickConnect/Connect`
    polled every 3 s until approved, then `POST /Users/AuthenticateWithQuickConnect`: signed in, and the answer's `ServerId` matched
    the probed `Id`.
  - `GET /Users/Me` with the token answered 200, again from a restored session. `POST /Sessions/Logout` answered 204, after which the same
    token got 401 on `/Users/Me`: logout ends the session on the server.
  - The request log showed paths with `secret=***` and the server key, never the host or a token.
  - Password sign-in, run by the owner with their own credentials: `POST /Users/AuthenticateByName` answered 200 with a token, then the
    same session checks and sign-out as above passed.
- `POST /Users/AuthenticateByName`, body `AuthenticateUserByName {Username, Pw}` (both nullable strings), answers 200
  `AuthenticationResult {User: UserDto, SessionInfo: SessionInfoDto, AccessToken, ServerId}` (all nullable) or 503. No security
  scheme in the spec; the `MediaBrowser` header without `Token` carries the client and device (see "Jellyfin 12.x").
- `UserDto`: `Id` (string, not nullable), `Name`, `ServerId`, `PrimaryImageTag`, `HasPassword`, `Policy: UserPolicy` (nullable).
  `UserPolicy.IsAdministrator` is a boolean; the spec marks `AuthenticationProviderId` and `PasswordResetProviderId` required.
- Quick Connect:
  - `GET /QuickConnect/Enabled` answers a JSON boolean.
  - `POST /QuickConnect/Initiate` answers `QuickConnectResult` (or 401 `ProblemDetails`).
  - `GET /QuickConnect/Connect?secret=` (required) answers `QuickConnectResult`, or 404 for an unknown secret.
  - `QuickConnectResult`: `Authenticated` (boolean), `Secret`, `Code`, `DeviceId`, `DeviceName`, `AppName`, `AppVersion`, `DateAdded`.
  - `POST /Users/AuthenticateWithQuickConnect`, body `QuickConnectDto {Secret}` (required), answers `AuthenticationResult` or 400.
  - `POST /QuickConnect/Authorize?code=&userId=` (`userId` optional) needs a signed-in session and answers a boolean, or 401/403.
- `GET /Users/Me` answers `UserDto`, or 400, 401, 403. `POST /Sessions/Logout` answers 204, or 401, 403. Both need a signed-in session.

## Jellyfin 12.2: local discovery and server identity (checked 2026-10-19 in the v12.2 source and docs; no live server queried)
Tag `v12.2` (https://github.com/jellyfin/jellyfin/releases/tag/v12.2). Files below are under `https://github.com/jellyfin/jellyfin/blob/v12.2/`.
- **Discovery is a UDP listener**, `src/Jellyfin.Networking/AutoDiscoveryHost.cs` (it left `Emby.Server.Implementations/Udp` earlier).
  - Transport: plain UDP, no multicast group. `new UdpClient(new IPEndPoint(IPAddress.Any, 7359))`: IPv4 only, all interfaces, port
    hard-coded. Docs list `7359 UDP, Configurable: No, Client Discovery`, and say it does not work outside the local subnet
    (https://jellyfin.org/docs/general/post-install/networking/).
  - Request: the datagram text (UTF-8) must contain `who is JellyfinServer?`; the match is case-insensitive and a substring (`text.Contains`).
  - Reply: unicast to the sender's source address and port, JSON from `MediaBrowser.Model/ApiClient/ServerDiscoveryInfo.cs` with default
    serializer options, so PascalCase and null emitted: `{"Address":"http://192.168.1.10:8096","Id":"<32 hex>","Name":"<server name>","EndpointAddress":null}`.
    `EndpointAddress` is always null from the server. `Id` is `SystemId`, `Name` the server's friendly name.
  - `Address` is `GetSmartApiUrl(sender IP)` (`Emby.Server.Implementations/ApplicationHost.cs`): the `PublishedServerUrl` startup setting if
    set; otherwise `PublishedServerUriBySubnet` entries matched against the sender's address (`NetworkManager.GetBindAddress`), else the
    server interface in the sender's subnet. It is a full URL: scheme, host, port, and the `BaseUrl` path. Containers or a reverse proxy
    can make it an address the client cannot reach, so treat it as a candidate and probe it. If the server cannot work out an address it
    logs a warning and sends no reply.
  - Off switch: `NetworkConfiguration.AutoDiscovery` (`MediaBrowser.Common/Net/NetworkConfiguration.cs`), default `true`;
    `AutoDiscoveryHost` returns without listening when it is false. The admin UI has an `AutoDiscovery` checkbox in jellyfin-web `master`
    (`src/apps/dashboard/routes/networking/index.tsx`); not checked at the 12.2 tag. There is no `AutoDiscoveryTracing` key.
  - DLNA/SSDP (UDP 1900) is a separate first-party plugin since 10.9, not in the base install
    (https://jellyfin.org/docs/general/post-install/networking/dlna/). I found no evidence that the server advertises `_jellyfin._tcp` or any
    other mDNS service; the csproj files I read list no mDNS package. Not exhaustive (see Not verified).
  - Official clients (read through a summarizer, not re-read):
    - Kotlin SDK `LocalServerDiscovery.kt`: the same message to `255.255.255.255:7359` only, an unbound `DatagramSocket`, 500 ms receive
      timeout, 1024-byte buffer, at most about 15 servers, duplicates removed by `Id`, `endpointAddress` set from the packet's source address.
    - Swift SDK `ServerDiscovery.swift`: sends to the broadcast address of each IPv4 interface plus `255.255.255.255`, listens 5 s, reads
      `Id`, `Name`, `Address`, skips loopback, IPv6 and interfaces with no broadcast address.
    - Kotlin SDK `RecommendedServerDiscovery`: probes candidates with `GET /System/Info/Public`, accepts only HTTP 200, 3.5 s timeout, no
      redirects, three at a time, preferring HTTPS and port 8096.
- **Public endpoints** (`Jellyfin.Api/Controllers/SystemController.cs`; no `[Authorize]` on them and no fallback policy is set in
  `Jellyfin.Server/Extensions/ApiServiceCollectionExtensions.cs`):
  - `GET /System/Info/Public` returns `PublicSystemInfo` (`MediaBrowser.Model/System/PublicSystemInfo.cs`): `LocalAddress`, `ServerName`,
    `Version`, `ProductName`, `OperatingSystem` (obsolete, empty), `Id`, `StartupWizardCompleted` (nullable). Filled in
    `Emby.Server.Implementations/SystemManager.cs`.
  - `LocalAddress` is built per request: the `PublishedServerUrl` setting, else (with `EnablePublishedServerUriByRequest`) the request's
    own host, scheme and port, else the subnet logic above. Behind a reverse proxy it may be an internal address. Do not use it as the connect URL.
  - `Id` is the server id: a random GUID in `N` format (32 hex, no dashes) created once and saved as `device.txt` in the data directory
    (`Emby.Server.Implementations/Devices/DeviceId.cs`). It survives restarts and upgrades and changes only when the data directory is lost
    (a container without a persistent volume). It is the same value as the discovery `Id` and, per `SessionManager`, as the `ServerId` that
    `AuthenticateByName` returns (read through a summarizer).
  - `GET`/`POST /System/Ping` is anonymous and returns the product name as a string. `GET /System/Info` needs a policy
    (`FirstTimeSetupOrIgnoreParentalControl`), so it answers unauthenticated during first-run setup at most (inferred from the policy name).
    `GET /health` (`MapHealthChecks`) appears open. `GET /Users/Public` is anonymous and lists the users who are not hidden or disabled
    (all users until the startup wizard is complete): never call it for identity.
  - Base URL: everything is mounted under `BaseUrl` (for example `/jellyfin/System/Info/Public`). A request outside the prefix is redirected
    to it by `BaseUrlRedirectionMiddleware`, so a probe that does not follow redirects sees a redirect, not JSON.
  - `RequireHttps` with a certificate adds `UseHttpsRedirection`, so an `http` probe can be redirected; the status code is not verified.
    `PublicSystemInfo` has no field that says so.
  - `IpBasedAccessValidationMiddleware` answers 503 to clients that remote-access settings reject, on every path including
    `/System/Info/Public`. I found no rate limiter and no setting to turn the endpoint off.

## Server identity without credentials: Seerr 3.5.0 and Stash v0.31.1 (checked 2026-10-19 in the v3.5.0 and v0.31.1 source; no live server queried)
Input for "send credentials only to an address that reports the server id saved at setup". Jellyfin is in its own section above.
- **Seerr 3.5.0** (https://github.com/seerr-team/seerr/tree/v3.5.0, `server/routes/index.ts`, `server/lib/settings/index.ts`):
  - `GET /api/v1/settings/public` needs no session (no `isAuthenticated`; the spec has `security: []`). It returns `plexClientIdentifier`,
    which is `settings.clientId`: `randomUUID()` on first start, saved in `settings.json`. The name is misleading (it is Seerr's own
    instance id, also sent as the Plex OAuth client identifier), but it is the only stable per-instance id a client can read without signing in.
  - Weaknesses: a restored or cloned `settings.json` shares the id; wiping the config volume makes a new one. Whether an admin can change it
    is not verified.
  - The spec types the response as `{initialized, plexClientIdentifier}`, both required, but the code returns about 30 more fields
    (`applicationTitle`, `applicationUrl`, `mediaServerType`, `localLogin`, `mediaServerLogin`, `jellyfinExternalHost`, `vapidPublic`, ...).
    Responses are not validated (`validateResponses` is not set), so read only `initialized` and `plexClientIdentifier` as dependable.
  - `GET /api/v1/status` is public too (`security: []`): `{version, commitTag, restartRequired}` plus `updateAvailable` and `commitsBehind`
    when the version check is on. With it on Seerr calls GitHub, so send `?checkUpdateAvailable=false`. It carries no id.
  - No Jellyfin `serverId` is public: it sits in `settings.jellyfin`, behind `GET /settings/jellyfin` (admin). `GET /auth/me` and the other
    `/settings/*` routes need a session; without one the validator answers 401, as in "Seerr" above.
  - No base path: "Base URLs cannot be configured in Seerr. With this limitation, only subdomain configurations are supported."
    Source: https://docs.seerr.dev/extending-seerr/reverse-proxy/
- **Stash v0.31.1** (https://github.com/stashapp/stash/tree/v0.31.1, `internal/api/server.go`, `internal/api/authentication.go`): no stable
  instance id is readable, with or without credentials.
  - Middleware order in `server.go`: `middleware.Heartbeat("/healthz")`, then CORS, then `authenticateHandler()`. So `GET /healthz` answers
    before authentication (200 with a one-character body, from chi's middleware; it proves a Go service is there, not that it is Stash).
  - Paths that skip authentication (`allowUnauthenticated`): `/login*`, `/logout`, `/css`, `/assets*`. `/graphql`, `/playground`, `/`,
    `/manifest.json` and the media routes need the key once a username and password are set (302 to `/login` for extensionless paths and
    `.html`, 401 for `/graphql` and other extensions, as in "Stash" above). `version` and `systemStatus` are GraphQL queries, so they are behind the key.
  - Per `pkg/session/session.go` (read through a summarizer, not re-read): any non-empty `ApiKey` that differs from the configured key gives
    401 on every path except `/healthz`, even on servers with no credentials. A client that always sends the header must not do so while probing.
  - Per the same source, a server with no username and password is open to local-network clients only (private, loopback, link-local and
    100.64.0.0/10 count as local; others get 403), so a client on the same network can read `version` and `systemStatus` unauthenticated there.
  - The schema has `version {version hash build_time}` and `systemStatus {databasePath configPath appSchema databaseSchema ...}`. None is
    unique per install (`hash` is per build); the paths are semi-stable but leak the file layout. The generated secrets (`jwt_secret_key`,
    `session_store_key`, `api_key`) are not ids and are never public.
  - Base path: no setting; the `X-Forwarded-Prefix` request header is honoured for redirects and `<base href>`
    (`getProxyPrefix`), and `external_host` overrides scheme and host in generated URLs.
  - So for Stash the id can only be checked after the key is sent, or against something the user confirms at setup (for example the
    database path from `systemStatus`, or the server's configured name). Not verified: the `systemStatus` resolver, the open `/graphql`
    response, `/css` and `/assets` bodies.
- Implication (inference): Jellyfin (`Id`) and Seerr (`plexClientIdentifier`) can be verified before any credential leaves the app. Stash
  cannot, so a Stash address needs a different rule: for example send the key only to addresses the user entered or confirmed, and treat a
  new discovered address as untrusted until the user accepts it.

## Seerr 3.5.0: sign-in with the app's Jellyfin session (checked 2026-10-20 in the Seerr v3.5.0 and Jellyfin v12.2 source and specs; nothing sent to a live server)
Input for the M3 item "Seerr sign-in without storing a password". Seerr files are under
`https://github.com/seerr-team/seerr/blob/v3.5.0/` (spec `seerr-api.yml`), Jellyfin files under
`https://github.com/jellyfin/jellyfin/blob/v12.2/` (spec https://api.jellyfin.org/openapi/stable/jellyfin-openapi-12.2.json). The route
code, the Jellyfin client headers, the session options and `QuickConnectManager.cs` were re-read by hand; the rest came from a
research agent reading the same files.
- **Seerr's three routes** (`server/routes/auth.ts`), all under `/api/v1/auth`, with no `isAuthenticated` and `security: []` in the spec:
  - `POST /jellyfin/quickconnect/initiate`, no body: 200 `{code, secret}` (Jellyfin's `Code` and `Secret`, passed through).
    403 `{message: "Quick Connect is only supported by Jellyfin."}` when `mediaServerType` is not Jellyfin. Any Jellyfin error,
    Quick Connect being off included, becomes 500 `{message: "Failed to initiate quick connect."}`. The spec lists 200 and 500 only.
  - `GET /jellyfin/quickconnect/check?secret=`: 200 `{authenticated: boolean}`. 400 `Invalid secret format` (zod: 8 to 128 hex
    characters), 403 as above; any Jellyfin error passes its status through (404 for an unknown or expired secret, 401 when Quick Connect
    is off) with `{message: "Failed to check Quick Connect status"}`, a Jellyfin that does not answer becomes 502. The spec lists 200 and 404.
  - `POST /jellyfin/quickconnect/authenticate`, body `{secret}`: 200 with the user (`User.filter()`: no `email`, tokens or device id, but
    `jellyfinUserId`, `permissions`, `userType`). In order: 400 `Secret required`; 403 `Quick Connect is not available during initial setup.`
    while `mediaServerType` is not configured or Seerr has no user yet; 403 Jellyfin only; 403 `Access denied.` (unimported user, below);
    otherwise Jellyfin's status with `{message: "INVALID_CREDENTIALS"}` (404 for a secret that is unknown, expired or not yet
    approved; 500 if there is none). The spec lists 200, 403 and 500 and no hex pattern.
  - Error bodies go through the error handler in `server/index.ts`: `{message, errors}` with the given status.
- **Seerr only proxies.** It keeps no state for the code or secret (the client holds the secret) and calls the Jellyfin server in its
  settings: `getHostname()` (`server/utils/getHostname.ts`) builds `http(s)://<ip>:<port><urlBase>` from `settings.jellyfin`, never from
  `externalHostname`. It sends `POST /QuickConnect/Initiate`, `GET /QuickConnect/Connect?secret=`, `POST /Users/AuthenticateWithQuickConnect
  {Secret}` with `MediaBrowser Client="Seerr", Device="Seerr", DeviceId="Qk9UX3NlZXJy", Version="<Seerr version>"` (`server/api/jellyfin.ts`):
  the Quick Connect routes pass no device id, so it is the fallback, base64 of `BOT_seerr`, the same for every user.
- **What Seerr does on success:** finds the Seerr user by `jellyfinUserId == User.Id` from Jellyfin's answer. There is no server id check
  on this path (the password route only writes `settings.jellyfin.serverId`). If none is found and `settings.main.newPlexLogin` (shown
  as the setting for new media server sign-ins; default `true`, `server/lib/settings/index.ts`) is off: 403 `Access denied.`; if on,
  it creates a Jellyfin user (`userType` 3) with `settings.main.defaultPermissions` (default `REQUEST`, 32). It stores Jellyfin's
  `AccessToken` as `jellyfinAuthToken` and `base64("BOT_seerr_" + name)` as `jellyfinDeviceId` (both never returned to clients), then sets
  `req.session.userId`. `settings.main.mediaServerLogin` (the admin switch for Jellyfin sign-in) is checked only by the password route,
  not by Quick Connect.
- **Jellyfin's side of the flow** (`Emby.Server.Implementations/QuickConnect/QuickConnectManager.cs`, `Jellyfin.Api/Controllers/QuickConnectController.cs`):
  - Quick Connect is on by default (`ServerConfiguration.QuickConnectAvailable = true`). `GET /QuickConnect/Enabled` and
    `GET /QuickConnect/Connect?secret=` are anonymous. Codes are 6 digits; secrets are 64 hex characters (32 random bytes).
  - State is in the server's memory and expires lazily (on the next Quick Connect call): a request lives 10 minutes from `Initiate`; after
    approval its entry lives 1 more minute plus 10 (so `Connect` still shows `Authenticated: true`) and the stored sign-in result 10
    minutes. A secret is not single-use: `AuthenticateWithQuickConnect` returns the same result and token until it expires. A code can be
    approved once. Pending requests are not capped.
  - Approval creates the token for the device that initiated, so for Seerr's `Qk9UX3NlZXJy` "Seerr" device, not ours. Creating it first
    logs out any device with the same device id and user (`SessionManager`), so each Quick Connect sign-in through Seerr replaces that
    user's previous Seerr Quick Connect token; our app's own Jellyfin token is untouched. Device tokens do not expire.
  - Seerr's sign-out (`POST /auth/logout`) deletes the Jellyfin device `jellyfinDeviceId` with Seerr's admin key, which is
    `base64("BOT_seerr_<name>")`, not `Qk9UX3NlZXJy`, so (inference) the Quick Connect device stays on Jellyfin after a Seerr sign-out.
- **Approving the code from our side:** Jellyfin `POST /QuickConnect/Authorize?code=&userId=` with our user's token.
  - `[Authorize]` with the default policy: any signed-in user, no admin needed; API keys also pass (we never use one). The usual caller
    checks apply: a disabled account, remote access without `EnableRemoteAccess`, a parental schedule.
  - `userId` (`RequestHelpers.GetUserId`): left out means the caller; another user's id needs an administrator, else 403. Always leave it out.
  - 200 with the JSON boolean `true`. Errors, through `ExceptionMiddleware` (outside Development the body is the plain text
    `Error processing request.`, so go by status): Quick Connect off 401 (the action catches every `AuthenticationException` as
    `Quick connect is disabled`); unknown or expired code 404; code already approved 500 (`InvalidOperationException`, unmapped); the target
    user not allowed on Seerr's device or at their session limit 403. The spec lists only 200, 401, 403.
  - The approved user's own policy is not checked again (no password path): `IsDisabled` and remote access are checked on the caller, which
    is the same user when `userId` is left out.
- **Same Jellyfin server on both sides:**
  - Seerr shows no Jellyfin server id without a session. `GET /api/v1/settings/public` has `mediaServerType` (1 Plex, 2 Jellyfin, 3 Emby,
    4 not configured; `server/constants/server.ts`), `mediaServerLogin`, `newPlexLogin`, `localLogin`, `jellyfinExternalHost` (an optional
    link address the admin typed, default empty), `jellyfinForgotPasswordUrl` and others. `jellyfinServerName` is declared but never set.
    `settings.jellyfin.serverId` is admin only (`GET /settings/jellyfin`). `jellyfinExternalHost` is a hint, not proof.
  - Proof before approving (inference from the code above): after Seerr's `initiate`, ask **our** Jellyfin `GET /QuickConnect/Connect?secret=`
    with Seerr's secret. Quick Connect state lives in one Jellyfin process's memory and the secret is 32 random bytes, so 200 with the same
    `Code` (and `AppName` "Seerr", `DeviceId` `Qk9UX3NlZXJy`) means the pending request is on our server; 404 means Seerr uses another
    Jellyfin, and the code must not be approved (it could match an unrelated pending request on ours).
  - Proof after sign-in: `GET /auth/me` returns `jellyfinUserId`, which must equal our Jellyfin user's `Id` (Jellyfin ids are 32 hex
    characters; compare without dashes and case).
- **The Seerr session** (`server/index.ts`, mounted on `/api`): express-session with cookie `connect.sid`, `maxAge` 30 days, `httpOnly`,
  `sameSite` Lax (Strict with CSRF protection on), `secure: 'auto'` (Secure only when Seerr sees HTTPS; `trust proxy` only with the
  `trustProxy` setting), `resave: false`, no `rolling`, TypeORM store with a 30-day ttl.
  - Per express-session and connect-typeorm (read at v1.18.1 and `master`, not the exact versions Seerr pins): each request with an
    unchanged session touches the store, so the server-side expiry slides, but `Set-Cookie` is sent only when the session changes, so the
    cookie's own `Expires` stays 30 days from sign-in. A client cookie store drops the cookie then. Not verified live.
  - `GET /api/v1/auth/me`: the user (`id`, `displayName`, `username`, `jellyfinUsername`, `jellyfinUserId`, `userType`, `permissions`, `avatar`,
    quotas, `requestCount`, `warnings`, ...) plus `settings {locale, discoverRegion, streamingRegion, originalLanguage, notificationTypes,
    watchlistSyncMovies, watchlistSyncTv}`. Without a cookie the validator answers 401; with a cookie for a missing or expired session,
    `isAuthenticated` answers 403 `{status: 403, error: "You do not have permission to access this endpoint"}` (`server/middleware/auth.ts`).
  - `permissions` (`server/lib/permissions.ts`): NONE 0, ADMIN 2, MANAGE_SETTINGS 4, MANAGE_USERS 8, MANAGE_REQUESTS 16, REQUEST 32,
    VOTE 64, AUTO_APPROVE 128, AUTO_APPROVE_MOVIE 256, AUTO_APPROVE_TV 512, REQUEST_4K 1024, REQUEST_4K_MOVIE 2048, REQUEST_4K_TV 4096,
    REQUEST_ADVANCED 8192, REQUEST_VIEW 16384, AUTO_APPROVE_4K 32768, AUTO_APPROVE_4K_MOVIE 65536, AUTO_APPROVE_4K_TV 131072,
    REQUEST_MOVIE 262144, REQUEST_TV 524288, MANAGE_ISSUES 1048576, VIEW_ISSUES 2097152, CREATE_ISSUES 4194304, AUTO_REQUEST 8388608,
    AUTO_REQUEST_MOVIE 16777216, AUTO_REQUEST_TV 33554432, RECENT_VIEW 67108864, WATCHLIST_VIEW 134217728, MANAGE_BLOCKLIST 268435456,
    VIEW_BLOCKLIST 1073741824. `hasPermission` treats ADMIN as every permission.
  - Renewal: `authenticate` only sets `req.session.userId` (no `regenerate`), so a client that still sends its `connect.sid` keeps the same
    id and gets a new `Set-Cookie` with a fresh 30 days; without one it gets a new session. Another Quick Connect round therefore renews
    the session silently. No rate limiter is applied to the auth routes (`express-rate-limit` is a dependency; where it is used was not found).
  - CSRF (`settings.network.csrfProtection`, default off): `@dr.pogodin/csurf` with an `_csrf` cookie (HttpOnly, SameSite Strict, Secure
    outside development) and a readable `XSRF-TOKEN` cookie set on every response; POSTs, Quick Connect ones included, must echo it in
    `X-XSRF-TOKEN` (or `CSRF-Token`, `XSRF-Token`, `X-CSRF-Token`, per the package README). Because those cookies are Secure, this
    presumably works only over HTTPS (inference).
  - `POST /api/v1/auth/logout`: 200 `{status: "ok"}`, with or without a session.
- **Failure cases, as the app sees them:**

  | Situation | What answers |
  |---|---|
  | Quick Connect off on Jellyfin | Our `GET /QuickConnect/Enabled` is `false`; Seerr `initiate` 500 |
  | Seerr not on Jellyfin, or not set up | `settings/public` `mediaServerType` not 2, or `initialized` false; `initiate` 403 |
  | Seerr on another Jellyfin server | Our `GET /QuickConnect/Connect?secret=` 404 (do not approve) |
  | Our user not imported and new sign-ins off | `authenticate` 403 `Access denied.` (`newPlexLogin` false in `settings/public` predicts it) |
  | Our user not allowed on Seerr's device, or at their session limit | Our `Authorize` 403 |
  | Code expired (10 minutes) | Our `Authorize` 404; Seerr `check` and `authenticate` 404 |
  | Session gone | `GET /auth/me` 403 (cookie sent) or 401 (none) |
- **Clients:**
  - Kotlin/Ktor: a cookie store per Seerr server (Ktor's `HttpCookies` plugin with our own `CookiesStorage`). `connect.sid` is a
    credential: keep it in the `SecretStore` (ADR 0017), never in settings or logs, and redact `Cookie` and `Set-Cookie` in the request
    log; Seerr requests need no other auth. With CSRF on, read `XSRF-TOKEN` from the store and send it
    in `X-XSRF-TOKEN` on every POST. Whether Ktor's storage keeps a Secure cookie for an `http` URL was not checked.
  - Browser: the browser owns the HttpOnly cookie, so the app never sees it. Seerr has no CORS and its validator answers OPTIONS
    preflights with 405 (express-openapi-validator 5.6.2, no `cors` package in `server/index.ts`), so Seerr's API must be served on the
    app's origin (reverse proxy) or Seerr needs CORS (see "Seerr" and the roadmap's M3 decision). Same origin, the cookie travels with
    `fetch` by default and SameSite does not matter. The calls to Jellyfin (`Connect`, `Authorize`) use our token header, and Jellyfin's
    CORS is open.
- **Recommended flow** (inference; to confirm end to end against live servers):
  1. Before offering it: `GET /api/v1/settings/public` shows `mediaServerType` 2 and `initialized`; the app holds a Jellyfin session; our
     Jellyfin's `GET /QuickConnect/Enabled` is `true`. Otherwise offer the fallback (password or local account, roadmap M3).
  2. Seerr `POST /api/v1/auth/jellyfin/quickconnect/initiate` → `{code, secret}`.
  3. Our Jellyfin `GET /QuickConnect/Connect?secret=<secret>` must answer 200 with the same `Code`; on 404 stop ("Seerr uses a different
     Jellyfin server").
  4. Our Jellyfin `POST /QuickConnect/Authorize?code=<code>` (no `userId`) with our token → `true`.
  5. Seerr `POST /api/v1/auth/jellyfin/quickconnect/authenticate {secret}` → 200 and `Set-Cookie: connect.sid` (no polling of `check` is
     needed, since we approved it ourselves). 403 `Access denied.` means an administrator has to import the user.
  6. Seerr `GET /api/v1/auth/me`: `jellyfinUserId` must equal our Jellyfin user id; otherwise sign out of Seerr and keep nothing. Keep the
     cookie in the `SecretStore` and the user, `permissions` (a `Long`) and the time of sign-in in settings.
  7. Renew by running steps 2 to 6 again well before 30 days from the last `Set-Cookie` (for example after 25 days, at start or when
     online), and at once when `GET /auth/me` answers 401 or 403, as long as the Jellyfin session is valid. Signing out of Jellyfin does not
     sign out of Seerr; sign out of both if the user asks.

## Local network, discovery and plain HTTP: Apple (checked 2026-10-19 in Apple's documentation, read as JSON from developer.apple.com; nothing run on a device)
- Local network privacy: TN3179 (revised 2026-10-06), https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
  - Platforms: iOS and iPadOS 14, macOS 15, visionOS 1. **tvOS: "no"** in the table. The reference page of `NSLocalNetworkUsageDescription`
    still lists tvOS 14, so set the key anyway; it is harmless. Not verified: what tvOS does with local traffic.
  - A local network is an IP network on a broadcast-capable interface (Wi-Fi, Ethernet; not cellular or VPN). `255.255.255.255` and all
    multicast addresses count as local. The check sits "deep in the networking stack", so it applies to URLSession, Network framework and BSD sockets alike.
  - Needs access (prompt on first use): outgoing TCP to a local address, sending UDP (unicast, multicast, broadcast), connecting a UDP socket,
    receiving UDP multicast and broadcast, resolving a `.local` name, all Bonjour operations. Does not: accepting TCP, **receiving a UDP unicast**.
    Traffic from WKWebView, SFSafariViewController and Safari is exempt.
  - So plain HTTP to `http://192.168.1.10:8096` triggers the prompt, and the app needs `NSLocalNetworkUsageDescription` in `Info.plist`
    (the app, not an extension). `NSBonjourServices` lists the service types to browse or register; only needed if we use Bonjour.
  - Denied access has no query API. An `NWConnection` goes to `.waiting` with `currentPath?.unsatisfiedReason == .localNetworkDenied`; an
    `NWBrowser` reports `kDNSServiceErr_PolicyDenied` (-65570). The system retries a connection by itself when the user grants access later.
    The first operation may be refused at once, before the user answers the alert, so use `NWConnection`/Network framework or URLSession
    with `waitsForConnectivity`, or retry.
  - In the background with the state undetermined, the operation is refused and no alert shows. The simulator does not support local
    network privacy: test on a device. macOS keeps the state per user account and has `defaults` keys for allowing networks without a prompt (admin use).
  - TN3179 on custom UDP broadcast discovery: use Bonjour if you can; otherwise "run your service discovery code on all broadcast-capable
    interfaces" and never hard-code `en0`.
- Multicast entitlement `com.apple.developer.networking.multicast`: "Your app must have this entitlement to send or receive IP multicast or
  broadcast on iOS", and it "requires permission from Apple" through the Multicast Networking Entitlement Request page.
  - Table in TN3179: sending a UDP broadcast, and receiving UDP broadcast or multicast, need it on iOS; **UDP unicast does not**.
    "The multicast entitlement isn't required on macOS." The entitlement's page lists iOS, iPadOS and visionOS. **tvOS is not mentioned;
    not verified.** With Xcode's automatic signing the Capabilities editor adds it once Apple has granted it.
  - Source: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.networking.multicast
  - Consequence: the iOS build can do unicast probes of saved and typed addresses without the entitlement. Broadcast discovery of the
    Jellyfin kind needs Apple's approval first. Not verified: whether Apple approves it for a media client, and how long it takes.
- macOS App Sandbox (if the Mac build is sandboxed): for UDP, `com.apple.security.network.client` allows only sending and
  `com.apple.security.network.server` only receiving, "Apps using UDP usually require both". For TCP the entitlements only limit who initiates.
  Sources: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.client and `.server`.
  Whether the Mac app will be sandboxed is not decided.
- App Transport Security applies to "all HTTP connections made with the URL Loading System—typically using the URLSession class"
  (https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity). `ktor-client-darwin`
  is built on `NSURLSession` (its sources reference it), so ATS governs our Apple HTTP calls. Network framework and BSD sockets are not
  URLSession; the page does not name them. The ATS keys' reference pages list iOS, iPadOS, macOS and visionOS, **not tvOS; not verified for tvOS**.
  - `NSAllowsLocalNetworking`: "controls whether ATS allows your app to connect to unqualified domains, `.local` domains, and IP addresses
    using IPv4 or IPv6." Since iOS 17 and macOS 14 ATS does not allow IP addresses by default; this key re-allows them.
    (https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)
    The page does not say whether "IP addresses" means only local ones. Not verified.
  - So `http://192.168.1.10:8096`, `http://nas:8096` (unqualified) and `http://media.local:8096` are allowed with the key. By the page's list,
    **`http://media.lan:8096` is not**: a dotted name that does not end in `.local` is none of the three kinds. Inferred from the text; not tried on a device.
  - `NSExceptionDomains` takes names, IPv4/IPv6 addresses and CIDR ranges (iOS 17, macOS 14), without ports or wildcards, but it is
    fixed at build time, so it cannot cover addresses a user types.
  - `NSAllowsArbitraryLoads` is "ignored—and the default value of NO used instead" when `NSAllowsLocalNetworking` (or
    `NSAllowsArbitraryLoadsInWebContent` or `...ForMedia`) is present, and it needs a justification in App Store review. So the Store edition
    can have the local-network exemption, or arbitrary loads, not both; arbitrary loads suits the Private edition (ADR 0014) only.
  - Not verified: whether AVPlayer's loads of a local `http` URL follow `NSAllowsLocalNetworking`.

## Local network, discovery and cleartext HTTP: Android (checked 2026-10-19 in the Android docs and AOSP `main`; nothing run on a device)
- Android 17 local network permission, https://developer.android.com/privacy-and-security/local-network-permission and
  https://developer.android.com/about/versions/17/behavior-changes-17
  - `ACCESS_LOCAL_NETWORK`, a runtime permission in the `NEARBY_DEVICES` group, **mandatory for apps that target API 37**. An app with
    `INTERNET` that targets 36 or lower gets it implicitly; do not request it there. Android 16 had an opt-in (`RESTRICT_LOCAL_NETWORK`
    compat flag, `NEARBY_WIFI_DEVICES`).
  - Covered, for "all networking APIs" (raw sockets, Cronet, OkHttp, `NsdManager`): outgoing TCP, accepting TCP, sending **and receiving** UDP
    unicast, multicast and broadcast, and resolving `.local` names. Exempt: DNS to a local DNS server (port 53), and apps that use the system
    picker (`DiscoveryRequest.FLAG_SHOW_PICKER` in `NsdManager`, Output Switcher). WebView inherits the host app's state.
  - Denied: TCP "will typically result in a timeout error", UDP and other denials in `EPERM`. The page does not say which address
    ranges count as the local network. Not verified.
  - So against a target-37 build, plain `http://192.168.1.10:8096` and the UDP discovery both need the permission, and the app should
    handle a denial by showing that local servers cannot be reached.
- `INTERNET` and `ACCESS_NETWORK_STATE` (for network callbacks, below). A `WifiManager.MulticastLock` ("Allows an application to receive
  Wifi Multicast packets ... can cause a noticeable battery drain") needs `CHANGE_WIFI_MULTICAST_STATE` (normal permission, `AndroidManifest.xml`
  of the framework). It is described for multicast, not broadcast or unicast replies; whether a broadcast discovery needs it is not verified.
- Cleartext, https://developer.android.com/privacy-and-security/security-config :
  - Default for apps targeting API 28+ is `cleartextTrafficPermitted="false"` in `base-config`.
  - `domain-config` matches DNS names (`includeSubdomains`); the page documents no wildcard, no CIDR and no `.local` special case. It only
    mentions IP literals for loopback (Android 17 adds an implicit cleartext allowance for localhost). Whether a `<domain>` can be a LAN
    IP literal is not documented. Not verified.
  - The XML is fixed at build time, so user-chosen addresses need `base-config cleartextTrafficPermitted="true"` (every host over HTTP)
    or the app enforcing its own rule above the platform's. A permissive base config cannot be narrowed later.
  - `android:usesCleartextTraffic` is ignored on API 24+ when a network security config exists, and "is getting deprecated and will be ignored for
    apps targeting API levels 38 and above" (https://developer.android.com/guide/topics/manifest/application-element).
  - `NetworkSecurityPolicy` is enforced "on a best effort basis" by platform stacks (HTTP and FTP, `DownloadManager`, `MediaPlayer`; WebView
    for target 26+). `java.net.Socket` does not; "Third-party libraries are strongly encouraged to honor" it. AOSP `NetworkSecurityPolicy.java`.
    The Android engine (`ktor-client-android`, `HttpURLConnection`) is a platform stack, so the setting applies. Not verified for Media3's
    data sources and Coil.
- Network changes: `ConnectivityManager.registerDefaultNetworkCallback` (`@RequiresPermission(ACCESS_NETWORK_STATE)`; at most 100 callbacks per app;
  unregister them). `NetworkCallback` has `onAvailable`, `onLosing`, `onLost`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`,
  `onBlockedStatusChanged`. AOSP `ConnectivityManager.java`. Nothing run.
  - Reading network state (https://developer.android.com/training/basics/network-ops/reading-network-state, checked 2026-10-20):
    `getActiveNetwork()` gives "the current default network". "When a new network becomes the default, the app receives a call to
    `onAvailable(Network)` for the new network." In its sample sequence, after `registerDefaultNetworkCallback()` "the default network
    callback starts receiving calls to `onAvailable()` ... for the mobile network, because the mobile network is the default network", so
    registering can report the current network at once; the page does not say so as a rule. Not verified on a device.

## Local network and discovery on desktop JVM (checked 2026-10-19 in the JDK 21 API reference; one JDK 21 install, no LAN)
- `java.net.DatagramSocket` is enough for UDP broadcast, no dependency: SO_BROADCAST is on by default "where possible" and
  `setBroadcast(boolean)` sets it, though "Some operating systems may require that the Java virtual machine be started with implementation
  specific privileges". To receive broadcasts "a DatagramSocket should be bound to the wildcard address".
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/DatagramSocket.html
- `NetworkInterface.networkInterfaces()` with `InterfaceAddress.getBroadcast()` (null for IPv6 and when there is no broadcast address) gives the
  directed broadcast address of each interface. Not verified: whether `255.255.255.255` leaves every interface on a multi-homed Windows PC
  (the per-interface address avoids the question), and what a Windows firewall prompt does to a first-run listener.
- `NetworkInterface` has no listener or event API (its methods are getters and predicates), so a change has to be found by polling and
  comparing `NetworkInterface`/`InterfaceAddress` results. OS notifications (Windows, macOS, Linux) are not verified; any native route
  would need JNA, JNI or a new dependency.

## Local network and mixed content: browsers (checked 2026-10-19; Chrome and Firefox by their notes, Safari not verified)
- No UDP: the standard web platform has no raw datagram API, so a browser app cannot do the Jellyfin broadcast discovery. The Direct Sockets
  proposal (WICG explainer) offers `UDPSocket` but "is planned to be available only in Isolated Web Apps", and states no browser support.
  Neither WebRTC nor WebTransport reaches an arbitrary server. A web build has to use saved or typed addresses, or ask a server it already knows.
- Mixed content (https://www.w3.org/TR/mixed-content/): from a secure page, blockable requests (fetch, XHR, script) to a URL that is not
  "potentially trustworthy" fail; upgradeable ones (image, audio, video) are rewritten from `http` to `https` instead (and not for IP hosts).
  https://w3c.github.io/webappsec-secure-contexts/#is-origin-trustworthy : trustworthy are `https`/`wss`, `127.0.0.0/8`, `::1/128`,
  `localhost` and `*.localhost`, `file:`. **LAN IPs and `.local` names are not.**
  So an HTTPS-hosted web app cannot fetch `http://192.168.1.10:8096` unless the browser grants an exemption.
- Chrome Local Network Access (https://developer.chrome.com/blog/local-network-access, update 2025-09-29 "launching in Chrome 142"):
  - It replaces Private Network Access ("after PNA was put on hold"): a permission prompt, only for secure contexts, for requests from a
    public page to the local network (private ranges, link-local, ULA) or loopback. Local page to local server is not covered yet; the blog
    calls it a future extension. Covered: `fetch()`, subresources, subframe navigation. Enterprise policy `LocalNetworkAccessAllowedForUrls`.
  - Mixed content is waived for permission-gated requests when Chrome knows before resolving that the target is local: a private IP
    literal, a `.local` name, or `fetch(url, {targetAddressSpace: "local"})`. `http://example.com/ping` is **not** exempt even if it resolves
    to a LAN address. A name such as `media.lan` therefore stays blocked on an HTTPS page, except for `fetch` with `targetAddressSpace`.
    Media elements (`<video src>`, `<img>`) cannot set that option (inferred); how they are treated for a `.lan` host is not verified.
  - WebSocket: "WebSockets connections to local addresses now trigger permission prompts" in Chrome 147 (stable 2026-04-07,
    https://developer.chrome.com/release-notes/147). Third-party pages claim a Chrome 145 permission split and later changes; not verified.
  - Denial behaviour and iframe `Permissions-Policy` details are not on that page. Not verified.
- Firefox 151 (2026-05-19): "Local network access restrictions are now rolling out to all users", websites must ask before connecting to
  local devices, as a progressive rollout (https://www.firefox.com/en-US/firefox/151.0/releasenotes/). Mozilla's support article could not be
  opened; whether the exemption for mixed content exists is not verified.
- Safari: TN3179 says traffic from Safari and WKWebView needs no local network access. A search found open WebKit work on a Local Network
  Access check (bugs 319907 and 319908) and no shipped prompt; not opened, so Safari's behaviour with local HTTP is not verified.
- Hosting: an app served over plain HTTP from the LAN (same network as the servers) has no mixed content to cope with, and Chrome's first
  milestone does not prompt local-to-local requests. A public HTTPS deployment is the case that needs the permission, a LAN IP or `.local` name, or HTTPS on the server.
  CORS is separate and unchanged (Jellyfin and Stash allow any origin, Seerr none; see their sections).

## Detecting network changes (checked 2026-10-19; documentation only)
- Apple: `NWPathMonitor` (iOS 12, macOS 10.14, tvOS 12): `pathUpdateHandler` receives `NWPath` values with `status`, `availableInterfaces`,
  `gateways`, `usesInterfaceType(_:)`, `supportsIPv4/IPv6`, `isExpensive`, `isConstrained`, `unsatisfiedReason` (including
  `.localNetworkDenied`). It gives no SSID. A Wi-Fi to Wi-Fi switch fires updates on macOS 26.6.2 (see "Apple path monitor and roaming
  on a Mac"); whether `gateways` can tell two networks apart is not verified.
  https://developer.apple.com/documentation/network/nwpathmonitor
- Android: `registerDefaultNetworkCallback`, see above; `onLinkPropertiesChanged` and `onCapabilitiesChanged` report address and capability changes on the same network.
- Browsers: `online`/`offline` on `window` (Baseline since July 2015) but "inherently unreliable": a LAN counts as online even without
  Internet, and on Windows the status depends on reaching a Microsoft server. `navigator.connection` and its `change` event
  (Network Information API) are Chromium only (Chrome and Edge 61+, not Firefox or Safari, per MDN browser-compat-data on 2026-10-19), and describe
  the connection type, not the network identity. https://developer.mozilla.org/en-US/docs/Web/API/Navigator/onLine
- Desktop JVM: nothing in the JDK, see above. What the JDK can do is poll `NetworkInterface` (JDK 21 reference,
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/NetworkInterface.html, checked 2026-10-20):
  `getNetworkInterfaces()` returns "all the interfaces on this machine" (at least one, possibly loopback; `SocketException` "if an I/O
  error occurs, or if the platform does not have at least one configured network interface"), with `isUp()` ("up and running"),
  `isLoopback()` and `getInterfaceAddresses()`. The reference says nothing about how often the list changes; there is no gateway or
  network identity, so two networks that give the machine the same address look the same.
- Apple, C API of the same monitor (checked 2026-10-20 in Apple's documentation JSON): `nw_path_monitor_set_update_handler` "Sets a
  handler to receive network path updates" (iOS 12, macOS 10.14, tvOS 12); `NWPathMonitor.start(queue:)` "Starts monitoring path changes,
  and sets a queue on which to deliver path events"; `cancel()` "Stops receiving network path updates". The reference does not say that
  the handler is called with the current path when monitoring starts. On macOS 26.6.2 it is, and a Wi-Fi to Wi-Fi switch fires updates
  (see "Apple path monitor and roaming on a Mac"); so on iOS 27.0.1 (see "Roaming on iOS"); tvOS not verified.
  https://developer.apple.com/documentation/network/nw_path_monitor_set_update_handler(_:_:),
  https://developer.apple.com/documentation/network/nwpathmonitor
- Implication (inference): treat every platform signal as "something may have changed" and re-probe the server addresses; none of them
  says which network the device is on.

## Apple path monitor and roaming on a Mac (checked 2026-10-20 on macOS 26.6.2, Apple silicon; ADR 0019)
- What ran: a throwaway `macosArm64Test` binary (not committed) built `networkModule`'s real `RoamingAddressSources` with the real
  `JellyfinServerProbe` and the Apple `NetworkChanges`, for one Jellyfin 12.2 server with a local address (`http://192.168.1.10:8096`,
  first) and an internet address (`https://media.example.com`). It logged, with timestamps, every probe, every `NetworkChanges` element, the
  address in use, `reachable`, and once a second where a request would go and how long `awaitCurrent` held it. A second, independent
  `nw_path_monitor` logged every raw update (status, interface types and names, `isExpensive`; gateways and IPv4/IPv6 support were not
  logged). The owner toggled the network by hand. A command-line test binary, not the app bundle: ATS and the app's `Info.plist` did not
  apply (plain `http` to an IP literal worked), so this says nothing about ATS.
- **The monitor reports the current path at start**: in all four launches the first update came 1-2 ms after `nw_path_monitor_start`,
  with the current path (satisfied, Wi-Fi). So skipping the first update is right: each launch made exactly one check (both addresses at
  once, the local one proved in 10-17 ms, requests held 9-17 ms) and no second one. The first real change after launch was not lost:
  Wi-Fi off 5.5 s after launch, and an update 13.7 s after launch, both cleared the address and started a check.
- **A Wi-Fi to Wi-Fi switch produces updates**, in both directions, when the network is picked in the Wi-Fi menu: the path goes
  unsatisfied for about 0.3 s between the two networks. A move between access points of one network (same SSID) was not tried.
- **Every transition comes as a burst of updates**, 7-11 within 3-5 s, most of them alike in what was logged (satisfied, Wi-Fi, `en0`).
  Opening the Wi-Fi menu alone also produced such updates, with nothing changed. Each update is a `NetworkChanges` element, so each
  one cleared the address in use and restarted the check (ADR 0019's rule).
- Per case, times from the first update of the transition:
  - Wi-Fi off: unsatisfied 0.25 s after the first update; the address was cleared at once, both probes failed (`Unreachable`) within
    0.1 s, requests were held 0.34 s and then failed as unreachable, `reachable` false; the next check came 15 s later, as specified.
  - Wi-Fi on: the first update was still unsatisfied but listed `en0`; the local address was proved 0.13 s later. Eight more updates
    followed in 3 s, each clearing and re-proving it (5-310 ms each); the longest hold the once-a-second sampler saw was 81 ms.
  - Home Wi-Fi to a phone hotspot (`isExpensive` true there): the internet address answered after 0.55 s, but the local address on the
    hotspot timed out (5 s) instead of being refused, and a lower address is used only once the higher ones have failed. The internet
    address was in use 9.2 s after the first update; a request was held 7.5 s. A later update on the hotspot (Wi-Fi menu opened) cleared
    it again and held a request 4.2 s, for the same local timeout.
  - Hotspot back to home Wi-Fi: the local address was in use 0.8 s after the first update (one update in between listed only a wired
    interface); 11 updates in 3.5 s, each re-proved in 8-136 ms.
  - A VPN on and off: a `utun` interface appeared and disappeared in `availableInterfaces`; interface types stayed Wi-Fi (one update
    was "satisfiable" with Wi-Fi and cellular). About 9 updates going on and 7 going off, each re-proving the local address in 5-150 ms.
  - tvOS and iOS: not run (no Apple TV at hand; the simulators share the Mac's network).
- Local network privacy, seen on the side: the binary's very first request to the local address failed as `Unreachable` within 53 ms
  while macOS showed its Local Network prompt; after Allow, the next run reached it. This matches TN3179's "first operation may be refused
  at once" (section "Local network, discovery and plain HTTP: Apple"). Inference: on an app's first launch at home, the local address can
  miss the first check and wait for the 15 s retry.
- Consequence for ADR 0019 (not changed here): counting every update as a change holds up, but bursts and no-change updates cost a check
  each, and on a network where a higher address times out, each such update holds requests for up to the probe timeout.

## Roaming on iOS (checked 2026-10-20 and 2026-10-21 on iOS 27.0.1, iPhone 15 Pro Max, development-signed; ADR 0019)
- What ran: the placeholder SwiftUI app with a throwaway harness (not committed) that started Koin with the real `databaseModule`,
  `networkModule` and `jellyfinModule`, so the real `RoamingAddressSources`, `JellyfinServerProbe` and Apple `NetworkChanges`. **The
  first two runs used the rules before "Use a proved address at once"** (wait 1 s for a burst to settle, then wait for every higher
  address); the third used the round-3 rules, with it but before round 4's changes (rate-limited request checks, no swap down
  mid-check, `NetworkChange.Resumed`), which were not run on iOS ("Third run" below). One Jellyfin 12.2 server, local address first
  (`http://192.168.1.10:8096`), internet address second (`https://media.example.com`). Logged with timestamps: every request log entry
  (it names no address; which probe is which follows from timing: the LAN answered in 9-46 ms, the internet address in 40-780 ms, and a
  cancelled probe logs "no response"), the address in use, `reachable`, a request through `awaitCurrent` every 250 ms with how long it
  was held, every raw update of a second `nw_path_monitor` (status, interfaces, `isExpensive`, `isConstrained`, IPv4, IPv6, DNS, gateway
  count) and the elements of a second `NetworkChanges`. For the runs `Info.plist` got `NSLocalNetworkUsageDescription` and
  `NSAllowsLocalNetworking`; the app has neither yet, and without the latter ATS refuses an IP address (see "Local network, discovery
  and plain HTTP: Apple").
- How: three runs, each from a fresh install, with the cable unplugged, the log written to a file, and a button tapped before each step.
  Wi-Fi was turned off and on from Control Center, so the app stayed in the foreground unless stated. With the cable in, the console
  stream and the app ended about 24 s after Wi-Fi went off; that attempt showed the same cellular behaviour as the unplugged runs.
- Not roaming, found on the way: **the host name lookup at start brings up the Local Network prompt and blocks until it is answered**,
  for at most about 35 s. `core:database`'s Apple `platformDeviceName()` reads `NSProcessInfo.hostName` when Koin builds
  `DeviceIdentity` at start. On the main thread, the first launch after install was killed by the watchdog (SIGKILL; main thread in
  `-[NSHost blockingResolveUntil:]`). With Koin started off the main thread: Koin started 6.0 s after launch when Allow was tapped about
  5 s after the prompt appeared, and 35 s after launch when it was tapped later (the lookup gave up with the prompt still up). Once
  access was allowed or denied, Koin started in 30-170 ms. Not fixed here.
- **Updates without a change are common on iOS.** Every logged field stayed the same. On home Wi-Fi they came in pairs about 5 s apart
  every 20-30 s in several stretches; on cellular, every 3 s right after Wi-Fi went off, then in pairs about 3 s apart every 10-40 s for
  at least 3 minutes; with a VPN on, every 6-10 s. Each is a `NetworkChanges` element. On Wi-Fi one costs a hold of 0.8-1.0 s (the 1 s
  settle, then the local address in 9-15 ms); on cellular see case 1.
- **At start**, the monitor reports the current path at once (satisfied, 0-2 ms after `nw_path_monitor_start`), so skipping the first
  update is right, **but in 5 of 11 launches a second, identical update came 110-200 ms after start** (4 of 5 in the second run). It
  cleared the address just proved and a second check ran: requests held 0.9-1.2 s instead of 8-25 ms, and with local network access
  denied, 1.7 s instead of 0.5 s. In the other launches there was one check (local address proved 50-100 ms after launch, once 1.1 s).
- Per case, with the earlier rules; times from the first update of the transition; where the runs differ, first run / second run:
  1. Wi-Fi to cellular: 3-4 updates in under 0.75 s (the first still Wi-Fi, then cellular only, `isExpensive` true); the path never
     went unsatisfied. The internet address answered in 40-690 ms. The local address on cellular neither answered nor failed: it hung
     until the probe's 5 s timeout, and a higher address is waited for. With updates every 3 s, most checks were cancelled after about
     2 s. **The internet address was first used 15.8 s / 14.6 s after the switch**, each time after a longer gap between updates, and
     the next update cleared it 1-4 s later. In 35 s / 66 s on cellular, one / two requests were held 11 s and failed as unreachable;
     the others were held 3-9 s. In an unmarked 3-minute stretch on cellular in the first run, requests failed after 11 s four times.
     `reachable` stayed true throughout: a cancelled check sets nothing.
  2. Back to home Wi-Fi: 5-6 updates in 1.1-1.2 s (Wi-Fi listed after cellular in the first ones); the local address was proved
     2.2 s / 2.1 s after the first update.
  3. Airplane mode on and off: this phone keeps Wi-Fi on in airplane mode, so only the cellular interface went (2 updates) and came back
     (3 updates in 0.35 s). The local address was proved 1.1-1.5 s after the first update; requests were held 1.1-1.4 s.
  4. Wi-Fi to another Wi-Fi network (not a hotspot: `isExpensive` false), once in the first run and twice in the second: 6 updates in
     3.5 s / 10-11 in under 2 s (the second run's passed briefly through cellular only), and later ones cancelled checks. The internet address answered
     in 0.5-0.6 s, but the local address timed out (5 s) as on cellular, so the internet address was in use 9.5 s / 13.0 s and 11.5 s
     after the first update. A request was held 9.3 s in the first run; in the second run one failed after 11 s each time. Every
     no-change update on that network then held requests about 5.8 s for the same timeout. This matches the hotspot case on the Mac.
     Back home: the local address 1.2 s / 2.7-2.8 s after the first update.
  5. A VPN on and off (switched in another app, then back): going on, 2-3 updates; `utun6` (type other) appears, and the local address
     still answered through it (proved 1.2-1.5 s after the first update). Going off, 1-2 updates, the local address in 1.0-1.2 s. Once,
     while the VPN reconnected, the path was "satisfiable" for two updates and the local address still answered.
  6. Local network access denied in Settings while the app ran: iOS did not terminate the app. On return, 2 no-change updates; the
     local probe then failed in 16-19 ms and the internet address was in use 1.8-1.9 s after the first update. After a relaunch the local
     probe failed in 30 ms; the internet address was in use 0.55 s after launch, or 1.7 s when a second update at start cancelled the
     first check. A denied local address fails at once; an unreachable one (cellular, another network) times out.
  7. Background and foreground: while in the background the app is suspended and logs nothing: no updates, no checks.
     - Wi-Fi off while away: on return one update came at once. First run: another 4 s later, and the internet address was in use 10.2 s
       after the return (a request held 9.9 s). Second run: 4 updates in the 6 s before the app went to the background again; the
       internet address was never used. **The request then in flight was held 31.8 s in all**: the 11 s cap does not run while the app
       is suspended, and the request failed as unreachable at the next return. The local probe in flight reported 24.9 s.
     - Wi-Fi on while away: the local address 1.2 s after the return. In the first run the first request after the return went to the
       address proved before leaving (the internet address), 3 ms before the update arrived. Inference: a stale local address on
       cellular would make such a request hang until its own timeout. Not seen.
  8. Right after launch: see "At start" above.
- First launch and the Local Network prompt: the prompt came from the host name lookup at start, before any probe (see above). First
  run, Allow tapped after the lookup gave up: the first local probe failed ("no response") in 9 ms with the prompt still up, and the
  internet address was used 53 ms after the check began. Nothing asked again until 3 no-change updates about 30 s later, likely the
  Allow; then the local address was proved. Without them the periodic check would have come after 5 min. Second run, Allow tapped
  within the lookup: the first local probe answered in 21 ms.
- **Third run, with "Use a proved address at once"** (2026-10-21, fresh install, same cases; times from the first update of the
  transition, or from the return to the foreground):
  - **Cellular now works**: leaving Wi-Fi, the internet address was in use 1.2-1.8 s after the first update (three times: Wi-Fi off in
    the foreground twice, and on return after Wi-Fi went off in the background), while the local probe hung in the background until its
    5 s timeout. No request failed in about 3 minutes on cellular. The no-change updates still came (every 2-5 s, with gaps of 10-20 s)
    and each still cleared the address: requests were held 0.9-1.1 s each (the 1 s settle, then the internet address in 40-90 ms), up
    to 3 s when two updates came within 2 s.
  - Another Wi-Fi network: the internet address in use 3.2 s after the first update (8 updates in 1.4 s), against 9.5-13 s before;
    later updates on that network held requests 1.3-3 s, and none failed. `isExpensive` was true for three of its updates.
  - Back to home Wi-Fi: the local address 4.5 s after the first update, Wi-Fi being listed after cellular for the first 3.3 s; the
    internet address served from 2.0 s until then. From another Wi-Fi network: 2.4 s. Airplane mode on and off: 1.3 s and 1.5 s. A VPN
    on and off: 1.15 s each.
  - Unchanged, as expected from the rules: the second identical update at start came in 3 of 3 relaunches (165-179 ms after start, a
    second check, requests held 0.9 s; with local network access denied 1.6-1.7 s instead of 0.5 s). **A request waiting when the app
    was suspended was held 48.8 s** and failed as unreachable at the return, in the same millisecond as the return's update.
  - Local network access denied: the internet address in use 1.6 s after the return and 1.7 s after a relaunch. On Wi-Fi each no-change
    update then held requests 1.3-1.5 s, since the internet address takes about 0.5 s there. On cellular the local probe still hung
    until its timeout: the permission does not make it fail there.
- Consequence for ADR 0019 (not changed here): with the earlier rules, on iOS cellular or another Wi-Fi network roaming seldom reached
  the internet address: no-change updates every 3-7 s, a local address that times out instead of failing, waiting for every higher
  address, and every update clearing the address meant a check only completed after about 6 s without an update. "Use a proved address
  at once" fixes that (third run). Still open: every no-change update clears the address and holds requests about 1 s (several times
  a minute on cellular), the second update at start causes a second check, and a request held when the app is suspended outlasts the
  11 s cap and fails at the return.

## Roaming on Android (checked 2026-10-21 on a Pixel 8 Pro, Android 17, SDK 37, build CP3A, security patch 2026-10-05; ADR 0019)
- What ran: a throwaway debug build of `androidApp` (target 37, not committed) that built the app's Koin graph (`databaseModule`,
  `networkModule`, `jellyfinModule`) with the real `RoamingAddressSources`, `JellyfinServerProbe` and Android `NetworkChanges`, for one
  Jellyfin server saved with a local address (`http://192.168.1.10:8096`, first) and an internet address (`https://media.example.com`).
  At home the internet name resolves to the server's LAN address (split DNS). It logged, with timestamps, every probe (the redacted request
  log), every `NetworkChanges` element (a second registration of the same callback), the address in use and `reachable`. It also made a
  request through a `ServerClient` every 2 s, at each raw callback and at each return to the foreground, and logged how long `awaitCurrent`
  held it. A second, independent `registerDefaultNetworkCallback` logged every raw callback. The owner switched networks by hand, mostly
  with the app in front. The phone has no mobile data.
- **As it stands, the app cannot reach a server on Android.** There are three gaps; the run closed all three in the throwaway build only:
  - `androidApp`'s manifest has no `INTERNET` permission (only `ACCESS_NETWORK_STATE`), so every request fails. The Android engine throws
    `java.lang.SecurityException: Permission denied (missing INTERNET permission?)`. It is not an `IOException`, so `serviceExceptionFor`
    does not map it and it passes through. The harness's own probe met it and crashed the app (an uncaught exception in a coroutine).
    Fixed in PR 23: the manifest declares `INTERNET`, and this exception maps to `Unreachable` (ADR 0019).
    `RoamingServerAddress.isSavedServer` also catches only `ServiceException`, so the first check would throw out of `AppScope` the same
    way. That is inferred, not run: the harness crashed first.
  - Cleartext: with no network security config, the `http` local address failed as `Unreachable` within 8-9 ms. This is presumably the
    default `cleartextTrafficPermitted="false"`; the exception was not logged.
  - `ACCESS_LOCAL_NETWORK` (mandatory at target 37, section "Local network, discovery and cleartext HTTP: Android"): without it, the
    internet address on home Wi-Fi (which resolves to the LAN address) timed out at 5 s, as the docs say for TCP. With `INTERNET`, a
    permissive base config and the permission granted (`adb shell pm grant`), both addresses reported the same server id.
- **Registering reports the current network at once.** With Wi-Fi up, `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`
  and `onBlockedStatusChanged` for the `activeNetwork` arrived within 4-12 ms. Because the callback is seeded from `activeNetwork`, none of
  these was a signal. One check at start proved the local address about 35 ms after the source started, and requests were held 36 ms.
  - Started offline (airplane mode), `activeNetwork` was null and nothing was reported. The retries came at 15, 30 and 60 s. The first
    connection, 2 min 42 s after start, came as an `onAvailable` and a signal, so it was not missed.
- **One check per transition, not one per callback.** Each new default network brought 5-9 raw callbacks within 0.5 s: `onAvailable`,
  1-3 `onLinkPropertiesChanged`, 1-2 `onCapabilitiesChanged` as validation finished, and `onBlockedStatusChanged`. On a steady Wi-Fi,
  `onCapabilitiesChanged` also kept arriving every 3-10 s (signal strength). Only `onAvailable` of another network and `onLost` of the
  default one are signals, so each transition made one or two signals and one check. Two signals 38 ms apart (VPN off: `onLost` of the VPN,
  `onAvailable` of the Wi-Fi) restarted the settle and made one check.
- Per case, times from the raw callback:
  - Home Wi-Fi to another Wi-Fi without the LAN, standing in for mobile data: break-before-make, with `onLost` and then `onAvailable`
    3.9 s later. The address was cleared 8 ms after `onLost`. The check after the settle found no network (both probes failed within 52 ms),
    so `reachable` went false, and the requests held meanwhile failed after 1.1 s. After `onAvailable`, the internet address answered in
    290 ms, but the local one timed out (5 s) instead of being refused. So the internet address was in use 6.06 s after `onAvailable`, and
    requests were held up to 5.2 s.
  - Back to home Wi-Fi: the same `onLost`, gap (2.2 s) and `onAvailable`. The local address was in use 1.06 s after `onAvailable` (the
    1 s settle plus a 15 ms probe).
  - Airplane mode on: the address was cleared 10 ms after `onLost`, `reachable` went false 1.05 s later, and requests then failed at
    once. Airplane mode off: the local address was in use 1.09 s after `onAvailable`, and a request was held 293 ms.
  - A VPN on and off, on home Wi-Fi. On: the VPN network (Wi-Fi and VPN transports) came as `onAvailable` with no `onLost` of the Wi-Fi
    under it (make-before-break). That was a signal, and the address was cleared within 4 ms. Neither address answered through this VPN
    (both probes timed out; not investigated), so `reachable` went false 6.07 s later, after holding requests up to 4.4 s. Off: `onLost`
    of the VPN and `onAvailable` of the Wi-Fi came 32 ms apart and made one check. The local address was in use 1.07 s after `onAvailable`.
  - Wi-Fi to and from mobile data: not run (no mobile data), so make-before-break was seen only with the VPN.
  - On home Wi-Fi with the permission, the internet probe often failed within 18-54 ms right after joining, while the local one answered.
    Not investigated.
- **In the background, Android 17 blocks the app's network but keeps delivering callbacks.** `onBlockedStatusChanged blocked=true` came
  5 s after `onStop`, and `false` at the next `onStart`.
  - A switch from home Wi-Fi to the other Wi-Fi in the background still delivered `onLost` and `onAvailable` (the latter with
    `blocked=true`). So the settled check ran while blocked: the internet probe failed in 26 ms and the local one timed out, leaving
    `reachable` false and no address.
  - **On return to the foreground nothing started a check**, for three reasons:
    - `onBlockedStatusChanged` is not a signal.
    - A request made with no address fails in `ServerClient.request` before it could call `reportUnreachable`.
    - The retry had doubled to 30 s across the switch, because a network change does not reset it.
  - So every request failed for 31.5 s after the app came back, until the timer proved the internet address. With more misses it could
    take up to `retryAtMost` (5 min). Fixed in PR 23 (ADR 0019): an unblocked default network asks again at once, a request that
    finds no address asks for a check, and a network change starts the retry waits over.
  - Once, the process was frozen for about 6.5 min, with no logs and no timers running. Requests that had been sent before then ended as
    `no response` after about 400 s when the app came back, well past the 30 s request timeout. Their failures woke a check, which proved
    the same local address at once.
- A network change does not cancel requests already sent. One request read the address 5-15 ms before the signal, went to the old address
  over the new (VPN) route, and hung 17.8 s until the VPN went off. ADR 0019 says nothing about requests in flight.
- Android 16: not run for roaming (the phone had been updated to 17).
- **Rerun at the same code**, same phone and harness, with a phone hotspot as the other network. It repeated the numbers above to about
  0.1 s: the internet address in use 6.09 s after joining the hotspot, local 1.08-1.17 s after rejoining home Wi-Fi, a VPN request in
  flight hung 17.6 s, and after a switch in the background requests failed for 18.9 s until the 15 s retry. Two more things showed:
  - A guest Wi-Fi network cut off from the LAN but handed the home DNS also resolves the internet name to the LAN address. So neither
    address answers there: correct, but split DNS makes the internet address useless on such a network.
  - A network that lasted 1.3 s on the way to the hotspot started a check that was thrown away when it went; no address came from it.
- **Second run, at the fixes for the above** (same day, branch at "Add the second iOS roaming run": `INTERNET` declared, an unblocked
  default network counted as a change, a proved address used at once, a check when a request finds no address, retries reset on a
  change). The harness still added `ACCESS_LOCAL_NETWORK` and the cleartext config. Times from the raw callback:
  - The background switch is fixed: home Wi-Fi to the hotspot in the background, the check made while blocked failed as before, and
    `blocked=false` on return started a check at once. The internet address was in use 309 ms after the app came back (was 18.9-31.5 s).
  - The cost: every return after more than 5 s in the background drops the address, even with no switch. After 6.6 s in the background
    on the hotspot, the same internet address was proved again 1.1 s after the return, and a request was held about 0.6 s.
  - A proved address used at once: the internet address on the hotspot was in use 0.36-0.56 s after start, `onAvailable` or VPN off,
    while the local probe still ran to its 5 s timeout and changed nothing. Rejoining home Wi-Fi, or after airplane mode: local in use
    110-184 ms after `onAvailable` (was about 1.07 s), because of the skipped settle below.
  - **A request that finds no address now starts a check each time**, so while nothing answers, checks follow the app's requests
    instead of the 15 s to 5 min retries. With the harness reading the address every 2 s (twice per test request), airplane mode for
    53 s made a check every 2 s, about 52 checks, against 3 before. Over the VPN, where both probes time out, checks ran back to back for
    49 s. Started offline, the same every 2 s until the network came.
  - It also skips the settle: a wake left by such a request starts the check as soon as the network changes. After airplane mode off, the
    first check ran 13 ms after `onAvailable` on a network not ready yet and missed; one woken by the next request proved local 184 ms
    after `onAvailable`.
  - **The 5 s probe timeout is not a bound on Android when a lookup hangs**: over the VPN, probes ended as `no response` after 6.0 s and,
    once, 11.0 s, while the local address (an IP literal) always ended at 5.0 s. Inferred: the internet name's DNS lookup, which the
    Android engine's blocking `HttpURLConnection` does not give up when Ktor's request timeout fires. The first check on the VPN took 12 s,
    so requests waited the full 11 s (`requestWaitsAtMost`) before failing.
  - While this ran, the branch moved on to "Rate-limit request-driven checks, resume without dropping the address, and never swap down
    mid-check", which addresses the check per request and the drop on return. Not run on Android.

## ktor-network 3.6.0 (checked 2026-10-19 from Maven Central: `.module`, `.pom`, source jars)
- Licence: Apache-2.0 (POM and source headers). Depends on kotlinx-coroutines-core, ktor-utils and kotlin-stdlib; the core already has
  Ktor client 3.6.0. It is a new dependency, so it needs approval (CLAUDE.md, ADR 0009).
- Targets published: jvm, js, wasm-js, iosArm64, iosSimulatorArm64, iosX64, macosArm64, macosX64, tvosArm64, tvosSimulatorArm64, tvosX64,
  watchOS, linux, mingw, androidNative. No android variant: Android takes the jvm artifact, as with the other Ktor modules.
- UDP: `aSocket(selector).udp().bind(...)`/`connect(...)` with `SocketOptions.UDPSocketOptions.broadcast` (`SO_BROADCAST`).
  - jvm: NIO channels, `StandardSocketOptions.SO_BROADCAST`.
  - Apple (iOS, macOS, tvOS): its own POSIX socket code (`posixMain`), `SO_BROADCAST` via `setSocketFlag`.
  - js and wasm-js: **Node.js only**. The js source calls `node:dgram` and loads it only when `process.release.name === 'node'`; otherwise
    it throws `UnsupportedOperationException("Module node:dgram is not available. Please verify that you are using Node.js")`. It
    never works in a browser. The wasm-js source jar was not opened.
- Platform permissions still apply: it is a plain socket library, so iOS needs the multicast entitlement for broadcast and the Local Network prompt;
  Android 17 needs `ACCESS_LOCAL_NETWORK`. Not verified: ktor-network's behaviour on a real device in either case.
- Versus `java.net` on the JVM: java.net is enough there and adds nothing. ktor-network would give one Kotlin API for jvm, Android, iOS,
  macOS and tvOS; the web still needs an interface with a "not available" implementation. Choosing is a design decision (ADR), not made here.

## Not verified (research before depending on it)
- Which exceptions the Android, Darwin and JS Ktor engines throw for a refused connection, an unknown host or a TLS failure. ADR 0015
  maps only `IOException`s to `Unreachable`; anything else passes through unmapped, and its message may contain the request URL.
  Seen on Android 17: a missing `INTERNET` permission throws `SecurityException` (section "Roaming on Android"); PR 23 maps that one
  to `Unreachable`.
- Whether `HttpTimeout`'s request timeout works the same on the Android, Darwin and JS engines. On Android 17 a 5 s probe once ended
  after 11.0 s over a VPN, probably in a DNS lookup (section "Roaming on Android"); the cause was not confirmed.
- Local server discovery: Jellyfin's protocol, Apple's local network permission and entitlements, and browser limits (mixed content,
  private network access) for local addresses (roadmap, M1 spike).
- For the video spikes: desktop playback on hybrid-GPU laptops and lower-end GPUs (ADR 0010 measured one desktop with an RTX 5090), tone-mapped output
  with Windows HDR off, libmpv's `win32-smtc` from an unpackaged JVM app (needs a build with it), the `wid` route on macOS and Linux desktops,
  interop blending over an OpenGL-rendered video, Compose popups as separate windows (`compose.layers.type=WINDOW`) above a native video window,
  HDR on web as seen on screen (the HDR10 stream reaches the decoder in Chrome and Edge, ADR 0011), patent licensing for HEVC/AAC/AC-3 decoders.
- Whether a browser app on another origin can sign in to Seerr through a reverse proxy without Seerr changes (cookie scope, CSRF). Mixed content
  is covered under "Local network and mixed content: browsers"; it was read, not tried in a browser.
- Playback reporting was never sent to a live server. Password and Quick Connect sign-in, the restored-session check and sign-out were
  (2026-10-20, see "Jellyfin 12.2: sign-in shapes"); `PlaybackInfo` was sent live during the web spike (see "Jellyfin 12.x").
- Seerr sign-in through the app's Jellyfin session (2026-10-20; source and specs only):
  - The whole flow against live Seerr 3.5.0 and Jellyfin 12.2, including that approving the code ourselves lets `authenticate` succeed
    without polling `check`, and the same-server check with `GET /QuickConnect/Connect?secret=`.
  - That the server-side session expiry slides while the cookie's `Expires` does not (express-session 1.19.0 and connect-typeorm 2.0.0,
    the versions Seerr pins, were not read); the csurf error status; whether `express-rate-limit` guards any route.
  - That Seerr's sign-out leaves the Quick Connect "Seerr" device on Jellyfin; the exact 401 body of Jellyfin's `Unauthorized("...")`;
    what status a disabled caller gets from `Authorize`.
  - Whether Ktor's cookie storage keeps a Secure cookie for an `http` URL.
- Dolby Vision's base layer in browsers without Dolby Vision. The web profile accepts `DOVIWithHDR10`, `DOVIWithHDR10Plus`,
  `DOVIWithSDR` and (with HLG) `DOVIWithHLG` everywhere, as ADR 0011 assumes, but no Dolby Vision file was ever played in a browser.
  Check profile 8.1 in Chrome as MKV and as MP4, the latter with a `dvh1`/`dvhe` sample entry as well as `hvc1`. If one fails, turn
  `baseLayerFallback` off for the web or add a `VideoCodecTag` condition.
- Firefox playback was checked only by hand (Playwright cannot drive a stock Firefox); the ADR 0011 hand checks are recorded there.
- Which DeviceProfile each engine needs (Media3, AVPlayer, desktop engine; HTML video: ADR 0011), and how they cope with Stash's 302-to-login and mislabelled mime types.
- Compose Multiplatform web accessibility with a real screen reader. The accessibility docs page does not cover web. The web spike saw
  the accessibility tree only through Playwright: buttons and headings present, a closed menu's items left behind (ADR 0011).
- Media3 capabilities for the Android fallback (e.g. FFmpeg extension) and licences of any custom Apple fallback engine library.
- HDR and Dolby Vision (the 2026-10-19 sections were read from documentation and source; nothing was played):
  - Apple:
    - Whether AVPlayer plays profile 8.1 (only 8.4 files and profiles 5 and 10 in HLS are documented), and how it treats 7 (presumably
      unsupported).
    - Whether HDR10+ dynamic metadata is used or dropped.
    - Whether `hev1`/`dvhe` play at all, or only `hvc1`/`dvh1`.
    - How to tell Dolby Vision from HDR10 display support once `availableHDRModes` is gone.
    - Which types `audiovisualContentTypes` lists per OS.
    - AVPlayer's variant choice with Jellyfin's master playlist (the `dvh1` variant first, then `hvc1`).
  - Android:
    - What a device's Dolby Vision decoder outputs on a display without Dolby Vision.
    - Whether every HEVC decoder ignores RPU NALs (type 62) in Media3's fallback. The release notes list a fixed failure of "some
      playbacks of Dolby Vision files ... when attempting to use a fallback AVC or HEVC codec".
    - Behaviour on external displays (Media3 checks only the default display).
    - What Android TV's HDR conversion setting does to the reported types.
  - Desktop:
    - Dolby Vision colours from mpv/libplacebo against a reference (profile 5, 8.1, 8.4, 10).
    - The cost of the second EL decoder in master, and when mpv, libplacebo and FFmpeg release the profile 7 FEL path.
    - Whether a distributed build that applies Dolby Vision reshaping needs a Dolby licence (legal question, ADR 0009).
  - Jellyfin:
    - Whether a stripped profile 7 stream plays cleanly everywhere. `hevc_metadata=remove_dovi` removes at most one trailing EL NAL per
      access unit after the RPU, so EL NALs (type 63) earlier in the unit may remain.
    - The live master playlist and init segment for each profile with and without `DOVI` in the device profile (only the code was read).
    - Whether `SUPPLEMENTAL-CODECS` without a `dvcC` box (profile 8, `DOVI` not listed) confuses any client.
- Server addresses, discovery and roaming (2026-10-19; documentation and source only, no device, no LAN, no live server):
  - Jellyfin:
    - The discovery handshake was never sent to a server; the 12.2 files were read through a summarizing fetcher and spot-checked
      (the port, the match string, the reply, `AutoDiscovery`, `PublicSystemInfo`, the id source).
    - The `UserDto` shape of `/Users/Public`; the HTTPS redirect status code; whether `GetBindAddress` returns the interface address when
      `LocalNetworkAddresses` is set; the jellyfin-web `AutoDiscovery` checkbox at the 12.2 tag; whether the server advertises mDNS
      (not found, not exhaustively searched); whether broadcasts reach a server in a bridge-networked container (expected not, untried).
  - Seerr and Stash:
    - Whether an admin can change Seerr's `clientId`; what `commitTag` holds on a release image; the default `applicationTitle`; the
      validator's 401 body.
    - Stash: the `systemStatus` resolver, the open `/graphql` response, the `/css` and `/assets` bodies, the chi `/healthz` body (taken
      from chi's source through a summarizer), the "local clients only" rule when no credentials are set.
  - Apple:
    - What tvOS does with local traffic and UDP broadcast (TN3179 lists no local network privacy for tvOS; the entitlement page does not
      mention tvOS; the ATS key pages do not list tvOS).
    - Whether Apple grants the multicast entitlement for a media client, and how fast.
    - Whether `NSAllowsLocalNetworking` admits public IP addresses, and whether AVPlayer follows it.
    - That a dotted non-`.local` name such as `media.lan` is blocked by ATS under `NSAllowsLocalNetworking` (read from the key's text, not tried).
    - Whether `NWPathMonitor` reports the current path at start and fires for a Wi-Fi to Wi-Fi switch on iOS and tvOS (it does on
      macOS 26.6.2), and for a move between access points of one network.
  - Android:
    - Which addresses count as "local network" for `ACCESS_LOCAL_NETWORK`; whether a `domain-config` can name a LAN IP literal.
    - Whether a broadcast discovery needs a `MulticastLock`; Media3's and Coil's handling of the cleartext policy.
    - Roaming between Wi-Fi and mobile data, and between access points of one network; why a probe of the internet address on home
      Wi-Fi sometimes failed fast right after joining (section "Roaming on Android").
  - Desktop: whether `255.255.255.255` leaves every interface on a multi-homed Windows PC; firewall prompts for a first-run UDP listener;
    OS-level network change notifications (Windows, macOS, Linux).
  - Web:
    - Safari's and Firefox's mixed-content exemption for LAN addresses (Mozilla's support article could not be opened; WebKit's Local
      Network Access work was not opened); whether media elements can reach a `.lan` host from an HTTPS page in Chrome.
    - Chrome 145 and 156 changes (third-party reports only); how a denied permission surfaces in `fetch`.
  - ktor-network: the wasm-js source jar was not opened; nothing was run on any target.
- Exact CMP/Kotlin/AGP/Gradle compatibility of our pinned set (JVM tests and desktop compile pass on Linux; web, Android and Apple builds not verified yet).
