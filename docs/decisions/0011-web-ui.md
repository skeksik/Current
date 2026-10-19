# 0011: Web UI

Status: proposed (2026-10-19), from the M1 "Web UI" spike. The owner's hand checks (Chrome, Edge, Firefox) are in; the choice is the owner's.

## Context
The web app is used every day (M2), so it must behave like a web page: password managers fill the sign-in form, posters are real links
(middle-click, open in new tab, copy link), find-in-page and browser translate work, and text can be selected. Compose for web draws on a
canvas, where all five are missing or partial (docs/platform-notes.md, "Web UI options"). The player needs our own controls over `<video>`
(ADR 0007). Logic stays shared Kotlin (ADR 0005); only the UI layer is in question.

The spike built the same small app three ways on one shared Kotlin core and compared them.

## Candidates
- **A: Compose HTML** (`org.jetbrains.compose.html:html-core` 1.12.1, Kotlin/JS): the Compose runtime over real DOM elements. Pages
  collect the ViewModels' `StateFlow`s directly.
- **B: React 19 + TypeScript + Vite 8**, over the core compiled to an ES module library with `.d.ts`. A thin `@JsExport` facade turns each
  `StateFlow` into `subscribe`/`getSnapshot` for `useSyncExternalStore`; actions are plain functions, no suspend function is exported.
- **C: Compose for web** (CMP 1.12.1, Kotlin/Wasm, canvas) with HTML patched in through `HtmlElementView`: a real `<form>` for sign-in,
  a transparent `<a href>` over each Compose-drawn card, and the `<video>` in a layer behind the canvas with a hole cleared in it.

**Shared core** (`spikes/web-core`; `js`, `wasmJs`, and `jvm` for 29 `kotlin.test` tests and an opt-in live check), used unchanged by all three:
- Jellyfin 12.x on Ktor 3.6.0 + kotlinx.serialization: `AuthenticateByName` with the `MediaBrowser` header and a random device id per
  browser, `/UserViews`, `/Items`, `/Items/{id}`, `PlaybackInfo`, `Sessions/Playing/Ping`, `DELETE /Videos/ActiveEncodings`.
- The device profile is built from runtime probes (`canPlayType`, `MediaSource.isTypeSupported`):
  - Direct play: MP4/WebM, and MKV only where its probe passes.
  - Everything else goes to the server's HLS in fMP4 (H.264, with HEVC copied where MSE takes it; AAC). AC-3, E-AC-3, DTS and TrueHD
    are transcoded; ASS/SSA/PGS/DVD subtitles are burned in; text subtitles come as external WebVTT.
- The planner chooses between the direct stream URL and `TranscodingUrl`.
- ViewModels on JetBrains `lifecycle-viewmodel` 2.11.0, one per page instance (a `ViewModelStore` cleared when the page leaves).
- `webMain`, one source set for both `js` and `wasmJs` through `js()`: `localStorage` (token, device id), the probes, History API routing,
  a headless `<video>` + hls.js 1.7.3 engine (Media Session, fullscreen of the player container), and capture-phase player keys.

## Comparison
Measured on Windows 11 with Playwright 1.64.1 and its Chromium 156 (headless), against the test server (Jellyfin 12.2.0) through an API-key
session (no password typed by the spike), 2026-10-19. "Owner:" marks the owner's hand checks in Chrome, Edge and Firefox (same day).
Prototypes at `spike/web-ui`.

| | A: Compose HTML | B: React + TypeScript | C: Compose Wasm + HTML patches |
|---|---|---|---|
| Password-manager autofill | Yes. Real `<form method=post>` with `autocomplete` url/username/current-password; owner: saves and fills | Yes, same | Yes: the same form as one HTML island over the canvas; owner: saves and fills |
| Real links | Yes: `<a href>`; middle-click and Ctrl+click opened the item URL in a new tab, a plain click stays in the app, Back works; owner: right-click menu works | Yes, same | Partly: transparent `<a>` overlays pass the automated middle-click and Ctrl+click checks (wheel events handed back to the canvas), but owner: the poster's right-click menu does not work |
| Find-in-page | Yes; owner: found and highlighted | Yes, same | No: owner: Ctrl+F finds words only in the HTML islands, and highlights nothing (`window.find` also matched the hidden accessibility copy) |
| Browser translate | Yes (owner) | Yes (owner) | No (owner) |
| Text selection | Native (triple-click selected the title); owner: drag selection works | Native; owner: works | Only in HTML islands (owner). On canvas text, `SelectionContainer` gave a double-clicked word to Ctrl+C in the automated check |
| Our controls over the video, input reaches them | Yes: DOM overlay, `elementFromPoint` at the buttons is ours; clicks, seek bar and menus work | Yes, same | Yes, after workarounds: `<video>` behind the canvas, root canvas cleared with `BlendMode.Clear`; Compose controls drawn on top get the input |
| Player behaviour (all automated) | Direct play and server HLS play; Space, ←/→ (10 s), F fullscreen of the player; audio switch re-requests `PlaybackInfo` and resumes at the same second; subtitle switch loads the WebVTT; Media Session title set | Same results | Same results |
| Keyboard (Tab from the top reaches a poster link) | Yes | Yes | Yes (the overlays are focusable, with a CSS focus ring) |
| Screen reader | `link "Title 2013"` in the accessibility tree; owner: Narrator reads the posters | Same | No: links come after all canvas content in DOM order, apart from the card's text, and a closed dropdown's items stayed in the tree; owner: Narrator reads things that are not on the page |
| Other problems the owner saw | | | Back from a fullscreen player stayed fullscreen (fixed in the core, below); text is blurry after zooming the page (reproduced, below) |
| Bundle, gzip, first load | 440 KB (JS 438 KB) | 346 KB (JS 345 KB) | 4.6 MB (Skia Wasm 3.33 MB, app Wasm 1.18 MB, JS 103 KB) |
| hls.js chunk, loaded on first HLS play | 179 KB | 177 KB | 179 KB |
| First load, sign-in page shown (cold, median of 3: local / 20 Mbit/s with 40 ms RTT) | 166 ms / 445 ms | 138 ms / 434 ms | 236 ms / 2.48 s |
| First load, library deep link until the first card (includes server calls) | 233 ms / 953 ms | 234 ms / 425 ms | 367 ms / 2.47 s |
| Clean production build (UI module + core, no build cache, warm daemon) | 29 s | 10 s (Gradle) + 1 s (Vite) | 75 s |
| Edit to served in dev | 2.4–3.5 s, then a full reload (page state lost, session kept) | TypeScript: 60–115 ms with React Fast Refresh (state kept). Kotlin: 2 s (development library) or 7 s (production), then a full reload | 13–17 s, then a full reload; the Kotlin daemon once ran out of memory (3 GB) in continuous mode |
| Lines of UI code (non-blank, non-comment, with imports and build files) | 407: Kotlin 305, CSS 60, config 42 | 534: Kotlin facade 198, TS/TSX 194, CSS 60, config 82 | 506: Kotlin 437 (71 of them HTML patches in `js()`), HTML/CSS 27, config 42 |

The shared core (1,169 lines) is not counted in any column. First loads were served by a local static server with gzip, a fresh browser
per run and the cache disabled.

## Problems hit and workarounds
**Core and server**
- `js()` works in the shared `webMain` source set for both `js` and `wasmJs` (Kotlin 2.4.20), so the engine, probes, storage and router
  are one implementation for all three UIs.
- Jellyfin keeps `SupportsDirectPlay: true` when a non-default audio track is requested; browsers play only the default track of a file. The
  planner turns direct play off for another audio track, and the server then remuxes or transcodes with it.
- With an HLS profile of MPEG-TS and H.264 only, HEVC files were re-encoded even when only the audio needed transcoding. Moving to fMP4 HLS
  with `h264,hevc` where MSE accepts HEVC made the server copy the video (`TranscodeReasons=AudioCodecNotSupported` only).
- `MediaSource.TranscodeReasons` came back empty; the reasons are in the `TranscodingUrl` query (`TranscodeReasons=`).
- The Kotlin Gradle plugin fails `kotlinStoreYarnLock` when a new JS module changes a committed `kotlin-js-store/yarn.lock`;
  `kotlinUpgradeYarnLock` (Wasm: `kotlinWasmUpgradeYarnLock`) updates it.
- A long 4K film (HEVC Main10 HDR10, TrueHD) failed after its first seconds with `hls.js: networkError / fragLoadTimeOut` (owner):
  - Jellyfin holds a segment request until ffmpeg has written that segment, and hls.js gives up after 10 s without a first byte.
  - With HEVC through MSE, the server copied the video and transcoded only the audio, at about real time on a server without hardware
    acceleration: segments were ready 4.6–6.3 s after the request for 3–10 s of video.
  - Without HEVC (Firefox), the server re-encoded 4K HEVC 10-bit to H.264 in software and had no first segment before the reverse
    proxy's 504, even when capped at 1080p and 20 Mb/s.
  - With NVENC enabled on the server, the first segment came after 1–3 s and every later one in 0.1–0.2 s on both routes.
  - The engine now waits up to 60 s for a segment's first byte, retries, and recovers from fatal network and media errors up to three
    times before reporting one. A software-only server still cannot transcode 4K in real time; that needs hardware acceleration or a
    transcode quality limit (ADR 0007, M2).
- Leaving the player while it was fullscreen kept the page fullscreen in C, whose fullscreen element (the document) outlives the player
  page. The engine now exits fullscreen when it is released; Back from fullscreen leaves fullscreen in all three (installed Chrome 155).
- Pages sometimes took seconds to load (owner, on 1 Gbit/s). It was not reproduced afterwards. Lists answered in 17–77 ms and posters in
  11–42 ms. Cold loads in installed Chrome 155 (headless) were 0.2–0.4 s for the production builds and 0.4 s (A) or 0.9 s (C) for the development
  builds the owner ran. The likely cause is the server's CPU, busy with the software 4K transcode above.
- HLS transcodes are pinged every 10 s and stopped when the player page closes inside the app. Closing the tab sends nothing; the server
  stops the job after 60 s without pings. No progress is reported (spike rule), so watch state is untouched.

**A (Compose HTML)**
- Compose HTML owns its elements' attributes: the `crossorigin` the engine set on `<video>` was removed on recomposition, and the
  cross-origin subtitle `<track>` then failed with a CORS error and no cues. Declaring it in the composable's attributes fixed it.
- No component library: menus and the like are written by hand. DOM updates land one frame after a state change.

**B (React + TypeScript)**
- Ktor's JS client contains `import('ws')` for Node; Kotlin's webpack setup hides it, Vite does not. An alias to an empty module fixed it.
- `StateFlow` cannot be exported, so a generic `Observable` caches one snapshot per emitted value (`useSyncExternalStore` needs a stable
  snapshot). Lists become arrays, and every ViewModel needs a facade class and view classes: about 200 lines for 5 ViewModels.
- React's `StrictMode` mounts twice in development, which disposed a page's Kotlin ViewModel and then reused it. It is left out here; a
  real app would create pages inside effects.

**C (Compose Wasm + HTML patches)**
- The canvas is painted white where nothing is drawn. Clearing the root canvas under the player with `BlendMode.Clear` shows the video
  behind it; an offscreen `graphicsLayer` only clears its own layer and left the white.
- HTML elements over the canvas take all its input, so a wheel over a card stopped the grid scrolling. Re-dispatching the `WheelEvent` to the
  canvas fixed it. This relies on Compose's undocumented DOM (host `div` with an open shadow root holding the canvas).
- An `HtmlElementView` gets its size from Compose: the sign-in form was clipped until its height was raised. Its HTML font (system UI)
  differs from the canvas's.
- Accessibility: dropdown items are exposed as buttons, a closed menu's items stayed in the accessibility tree, and link overlays come
  after all canvas content in DOM order.
- Text is blurry after zooming the page (owner). Reproduced by changing `devicePixelRatio` at runtime: the canvas kept its backing size
  (1280 px for 1280 CSS px at ratio 2), so the browser upscales it.
- 4.6 MB to download before the first screen; the slowest builds; an out-of-memory crash of the Kotlin daemon in continuous mode.
- Development and production Wasm builds share one sync folder in the build (`build/wasm/packages/<module>/kotlin`): running both in one
  Gradle invocation fails validation, and a production build after a development one shipped the 25 MB development Wasm until
  `wasmJsProductionExecutableCompileSync --rerun`.

## Recommendation
**B (React + TypeScript over the Kotlin core)**, pending the owner's hand checks. Reasons:
- Of the three, B has the smallest bundle and the fastest loads.
- Its dev loop is fast for UI work (instant reload with state kept), and its type-checked build takes a second.
- All five web behaviours are native.
- B brings the largest ecosystem for what comes next: accessible menus and dialogs, focus management, virtualised grids, i18n.

Its cost is a second language and toolchain, plus a facade that grows with every ViewModel (about 40 lines each). Kotlin changes need a
Gradle rebuild before Vite reloads.

**A is the close alternative** if staying in one language matters more. It has the same native web behaviour, no facade, and the core
used directly. Its bundle is somewhat larger, its reloads lose state, and it has no component library. Compose HTML is JS-only, with
Wasm support still in the backlog.

**C is not recommended for the web now.** Every page needed HTML patches for basic web behaviour, and the owner's checks failed for the
right-click menu on links, find-in-page, translate, selection of canvas text and Narrator; zoomed text is blurry. It is 13 times the download and has the slowest dev loop. Revisit C when CMP ships autofill
(CMP-9861, targeted at 1.15) and links (CMP-9997), since sharing Compose screens with Android and desktop is its real advantage.

Consequences of choosing A or B:
- The web leaves the Compose umbrella: `shared`, `composeUi` and `webApp` stop targeting the web.
- Pure-Kotlin modules need a `js()` target (they have `wasmJs` today).
- Navigation for the web is the core's History API router, not Navigation Compose. Koin can still wire the core.
- The web engine, probes, storage and router from `webMain` carry over.

## Hand checks
Done by the owner on 2026-10-19 in Chrome, Edge and Firefox, on each of A, B and C:

| Check | A | B | C |
|---|---|---|---|
| 1. Password manager saves and fills the sign-in form | yes | yes | yes (the form is HTML) |
| 2. Poster: right-click "Open in new tab" / "Copy link", middle-click | yes | yes | no right-click menu |
| 3. Ctrl+F for a word of the overview | found and highlighted | same | finds only words in HTML islands, no highlight |
| 4. Browser translate | yes | yes | no |
| 5. Drag-select part of the overview and copy it | yes | yes | only HTML text |
| 6. Player: keys, audio and subtitle menus, controls in fullscreen, media keys and flyout | yes | yes | yes |
| 7. Narrator on a poster | reads it | reads it | reads content that is not on the page |

Also reported: in C, Back from a fullscreen player stayed fullscreen, and text was blurry when zoomed. Pages sometimes loaded for a few
seconds. A long 4K film failed with `fragLoadTimeOut`. Causes and fixes: Problems hit and workarounds.

Expected per-browser differences: Firefox has no MKV probe, so MKV goes through HLS. Edge reports AC-3/E-AC-3 as playable, but the profile
still transcodes them. Chrome reports native HLS as "maybe", but hls.js is used wherever MSE exists.

## Licences
- **Shipped by a prototype**:
  - Kotlin/JS and Wasm libraries, all Apache-2.0: Kotlin stdlib, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.11.0,
    kotlinx-browser 0.5.0, Ktor 3.6.0, JetBrains lifecycle-viewmodel 2.11.0 (with AndroidX annotation/collection), Compose HTML
    html-core 1.12.1 (A), Compose Multiplatform 1.12.1 with Skiko and Material 3 (C), Coil 3.6.3 with Okio (C).
  - npm: hls.js 1.7.3 (Apache-2.0); react and react-dom 19.3.1, scheduler 0.28.0 (MIT) (B).
- **Build tools only, not shipped**:
  - Vite 8.3.4, @vitejs/plugin-react 6.1.2, @types/react(-dom) 19.3.0 (MIT); TypeScript 7.0.2 (Apache-2.0).
  - Vite's CSS minifier lightningcss is MPL-2.0. It is a build tool; ADR 0009's per-library decision applies only if it were shipped.
  - Kotlin's webpack/karma toolchain.
- **Tests and measurements only**: ktor-client-mock and ktor-client-java (Apache-2.0). Playwright 1.64.1 (Apache-2.0) ran from a scratch
  folder outside the repo.

## Prototype
`spikes/web-core`, `spikes/web-html` (A), `spikes/web-ts` (B) and `spikes/web-wasm` (C) on branch `spike/web-ui`, with `spike-` catalog
entries. They stay on the branch until the owner decides, then are removed before merging: this ADR and docs/platform-notes.md are what
remains. Run on Windows from the repository root:
- A: `.\gradlew.bat :spikes:web-html:jsBrowserDevelopmentRun --continuous`, then http://localhost:8081
- B: `.\gradlew.bat :spikes:web-ts:jsBrowserProductionLibraryDistribution`, then in `spikes\web-ts`: `npm install` and `npm run dev`;
  http://localhost:5173
- C: `.\gradlew.bat :spikes:web-wasm:wasmJsBrowserDevelopmentRun --continuous`, then http://localhost:8082

If Gradle reports "Lock file was changed", run `.\gradlew.bat kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock` once. The live check of the
core (read-only, against a test server named in `%USERPROFILE%\.current\test-server.env`) is
`.\gradlew.bat :spikes:web-core:jvmTest -Pspike.live=true`.
