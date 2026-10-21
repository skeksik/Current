# Review: the web (`web` and `webApp`), October 2026

Reviewed at `6fb1957` (main, 2026-10-21). Review only: no code changed. Sources: the code, ADRs 0004, 0011, 0017, 0018, 0020,
docs/architecture.md, docs/platform-notes.md, and the descriptions of PRs 6 and 37.

Gradle was tried twice in this environment (`:web:jsNodeTest`): both runs failed with HTTP 429 from Maven Central before compiling
anything. Nothing below was run here; every finding comes from reading the code. The TypeScript check needs the Kotlin library's
`.d.mts`, so it was not run either.

## 1. Features in this area

| # | Feature | What it does | ADR | PRs | Checked on (docs/platform-notes.md, PR descriptions) |
|---|---|---|---|---|---|
| F1 | Kotlin/JS core library `current-core` | The pure-Kotlin modules compiled to an ES module with `.d.mts`. The React app imports it through a Vite alias. | 0011 | 6 | CI on Linux; `check.sh` on Windows 11 (PR 6) |
| F2 | `Observable` bridge | Turns a `StateFlow` into `subscribe`/`getSnapshot` for `useSyncExternalStore`. The snapshot is cached per emitted value. | 0011 | 6 | Chrome 155 headless, real core (PR 37 run); the desktop app's built-in Chromium (PR 6) |
| F3 | Page lifecycle | `ViewModelHolder` + `Page.dispose()`. `usePage` creates one ViewModel per page instance in an effect, which works with StrictMode. | 0011 | 6, 37 | Same as F2 (no StrictMode errors in the console) |
| F4 | History API router and real links | Every page has a URL. A plain click navigates in the app; middle-click and Ctrl/Cmd/Shift/Alt are left to the browser. Back from the first page of a tab shows the page's parent. Back and Forward put focus back on the element that opened the page. | 0011; architecture.md "Navigation" | 6, 37 | Chrome 155 headless (49/49, PR 37); worker's mock run in headless Chromium (26/26) |
| F5 | `CurrentWeb` and Koin start | Starts `databaseModule`, `networkModule` and `jellyfinModule` in an isolated `koinApplication`. Saved servers and sessions come back from `localStorage` before the first page reads them. | 0014, 0016, 0017 | 37 | Chrome 155 headless; the owner's own browser against their own server (reload stays signed in or signed out) |
| F6 | Shell | Sidebar with Home and Settings (`aria-current`, arrow keys), skip link, Escape goes back, focus on the title, light and dark from the system, a top bar at phone width. | 0011 | 37 | Chrome 155 headless, including phone width and both themes |
| F7 | Home | Three states: no server, signed out (Sign in or Open settings), signed in. | 0018 | 37 | Chrome 155 headless |
| F8 | Settings | Server panels (signed in as, offline, Sign in/Sign out, Remove behind a modal `<dialog>`), where sign-ins are kept, the playback link, About. | 0016, 0017, 0018 | 37 | Chrome 155 headless; owner by hand |
| F9 | Add server | Address field → Check → found panel (local or internet, plain-HTTP warning) → Add. Jellyfin then replaces the page with Sign in. | 0016, 0020 | 37 | Chrome 155 headless against a loopback stand-in; owner by hand |
| F10 | Sign in | Password with autocomplete hints, and Quick Connect (the code, a wait, "Use a password instead"). Goes back once signed in. | 0018 | 37 | Chrome 155 headless against a stand-in; owner by hand (password only stated) |
| F11 | Playback support page | The browser probes (`core:playback`) as tables, plus the raw answers with a Copy button to record a fixture. | 0011 | 6 | Desktop app's built-in Chromium (PR 6); probe answers recorded in Chrome, Edge, Firefox, Safari (ADR 0011) |
| F12 | Not found | Any unknown path, and a sign-in path for a service other than Jellyfin. | 0011 | 6, 37 | Chrome 155 headless |
| F13 | Build and licence tooling | `scripts/kotlin.mjs` builds the Kotlin library before Vite. The Vite config sets the SPA fallback and the `ws` stub. `check-licences.mjs` applies ADR 0009 to shipped npm packages. | 0009, 0011 | 6 | CI Linux job and `check.sh` on Windows 11 (PR 6) |

## 2. File map

| Path | What it holds | Features |
|---|---|---|
| `web/build.gradle.kts` | The js target (`browser()`, `nodejs()` for tests), ES module library, `.d.ts`, dependencies on every pure-Kotlin module | F1 |
| `web/src/jsMain/.../CurrentWeb.kt` | `RouteView`, `CurrentWeb` (Koin, router, page factories, lazy media probes, version) | F4, F5, F7–F11 |
| `web/src/jsMain/.../Observable.kt` | `mainScope`, `Observable<T>`, `StateFlow.observe` | F2 |
| `web/src/jsMain/.../Pages.kt` | `ViewModelHolder`, `holdViewModel`, abstract `Page`, the playback support views and page | F3, F11 |
| `web/src/jsMain/.../Router.kt` | `Route` and its parser, `BackStep`/`backStep`, `Shown`, History API glue in `js()`, `BrowserRouter`, `isPlainClick` | F4 |
| `web/src/jsMain/.../ShellPages.kt` | Home, Settings, Add server and Sign in views and pages, `ServerRef.id`, `signInPath` | F7–F10 |
| `web/src/jsTest/.../RouteTest.kt` | 8 tests: parsing, parents, Back decision, plain click | F4 |
| `web/src/jsTest/.../ShellPagesTest.kt` | 6 tests: UiState → view mapping for the four shell pages | F7–F10 |
| `webApp/index.html` | Document shell: `lang`, viewport, `color-scheme`, title | F6 |
| `webApp/package.json`, `package-lock.json` | Scripts and exact versions (React 19.3.1, Vite 8.3.4, TS 7.0.2) | F13 |
| `webApp/scripts/kotlin.mjs` | Runs `:web:jsBrowser{Development,Production}LibraryDistribution` | F13 |
| `webApp/scripts/check-licences.mjs` | ADR 0009 check over `npm query .prod` | F13 |
| `webApp/tsconfig.json` | Strict TS; `paths` for `current-core` and `ws` | F1, F13 |
| `webApp/vite.config.ts` | Aliases, SPA fallback, ports, `fs.allow`, output dir | F1, F13 |
| `webApp/src/main.tsx` | One `CurrentWeb` per page load, StrictMode root | F5 |
| `webApp/src/App.tsx` | Sidebar, page switch, skip link, Escape, arrow keys, Not found | F6, F12 |
| `webApp/src/nav.tsx` | Core/route/nav contexts, focus after navigation (`focusSoon`), `Link` | F4, F6 |
| `webApp/src/hooks.ts` | `useObservable`, `usePage`, `useRerender` | F2, F3 |
| `webApp/src/components.tsx` | `Page`, Back button, `useDocumentTitle`, `Panel`, `Notice`, `Progress`, `EmptyState` | F6–F12 |
| `webApp/src/icons.tsx` | Line icons, the same paths as `CurrentIcons` | F6 |
| `webApp/src/pages/Home.tsx` | Home's three states | F7 |
| `webApp/src/pages/Settings.tsx` | Server panels, focus after sign-out and removal, Remove dialog | F8 |
| `webApp/src/pages/AddServer.tsx` | Add server form and its states | F9 |
| `webApp/src/pages/SignIn.tsx` | Sign-in form, Quick Connect code | F10 |
| `webApp/src/pages/PlaybackSupport.tsx` | Probe report, Copy button | F11 |
| `webApp/src/strings.ts` | Texts mirrored from composeUi's `strings.xml`, plus web-only ones | F6–F12 |
| `webApp/src/styles.css` | Tokens (dark default, light under `prefers-color-scheme`), layout, controls | F6–F12 |
| `webApp/src/ws-stub.ts` | Empty module for Ktor's Node-only `ws` import | F1 |
| `webApp/src/vite-env.d.ts` | Vite client types | F13 |

There are no tests in `webApp` (no test runner is installed). That matches CLAUDE.md's rule of testing ViewModels and planners, not UI.

## 3. Unverified parts, per feature

- **F1 core library**
  - `vite preview` and production hosting of deep links were never checked (PR 6). A static host needs its own SPA fallback, and none is
    written down.
  - The `ws` alias was unexercised in PR 6. The PR 37 run used the dev server only, so the production bundle with Ktor in it has never
    been loaded in a browser. CI only checks that it builds.
- **F2/F3 Observable and page lifecycle**
  - No unit test covers `Observable` (snapshot caching, `drop(1)` under `UNDISPATCHED`, unsubscribe) or `ViewModelHolder.close()`.
  - The only evidence is the Playwright run and "no StrictMode errors in the console".
- **F4 router**
  - `BrowserRouter` has no test: `navigate`, `replace`, `back`, popstate, the step counter and the opener record are all in `js()` glue.
    Only `Route.parse`, `backStep` and `isPlainClick` are tested.
  - Browsers: Chrome 155 headless only. Firefox, Safari and Edge never ran the shell (PR 37 "Not verified").
  - Untried:
    - Back/Forward across a cross-document load (another site in between);
    - `history.scrollRestoration` with async page content;
    - a reload after the browser restores a session.
  - `isPlainClick` has no test for Shift or Alt alone.
- **F5 Koin start and sessions**
  - The restore from `localStorage` ran in Chrome headless and in the owner's browser. Untried:
    - storage blocked or full (private modes, Safari ITP);
    - two tabs open at once (see finding W7).
  - The local network access prompt (Chrome 142+, Firefox 151+) was never seen from this app, because the dev page is on loopback. The
    app has never been served over https at all, so mixed content against a LAN server was not tried in the shell (platform-notes
    "Local network and mixed content: browsers").
- **F6 shell**
  - No screen reader was ever used on the React shell. The ADR 0011 spike's Narrator check was on the spike's prototype, not this shell.
  - Not tried:
    - forced colors (Windows contrast themes);
    - 200% text zoom or 400% page zoom;
    - a touch device or a real phone;
    - mobile Safari or Chrome on Android.
  - Escape-goes-back was not tried with a browser autofill dropdown open (unverified whether the page gets that keydown).
- **F7 Home**: only against a stand-in and one real server, in Chrome. Not checked: a Stash- or Seerr-only setup reaching Home in a
  browser (no such server can be added on the web yet).
- **F8 Settings**
  - The Remove dialog was checked in Chrome only. Its comment cites Safari and Firefox behaviour on macOS that was not run here.
  - Address editing does not exist on the web (known follow-up).
- **F9 Add server**
  - Never run from an https page against an `http://192.168.1.10:8096` server (mixed content / local network access).
  - Never run with a `.local` name, and never in Firefox or Safari.
- **F10 Sign in**
  - Password-manager save and fill was checked in the ADR 0011 spike, not in this shell's form. It is likely the same, since the form has
    the same `name`/`autocomplete` attributes, but it is unverified.
  - The Quick Connect UI ran only against a stand-in.
- **F11 Playback support**
  - Checked in PR 6 in the desktop app's built-in Chromium only.
  - The Copy button was never tried on a non-secure origin (see W3).
  - Dolby Vision probes: no recorded browser answers them yet (PR 6).
- **F13 tooling**
  - `kotlin.mjs` on Windows uses `shell: true` with a quoted path. That ran on the owner's Windows PC. A checkout path with characters
    special to `cmd.exe` (`&`, `^`) was not tried.
  - The licence checker has no tests.

## 4. Code review

Severity: must-fix / should-fix / nit. Confidence: high (traced in the code and the platform docs), medium (traced, with one
assumption named), low.

### Correctness and robustness

**W1. Copy button throws on a non-secure origin.** `webApp/src/pages/PlaybackSupport.tsx:74`. Should-fix, high confidence.
- What's wrong: `navigator.clipboard.writeText(...)` is called unguarded. The Clipboard API exists only in secure contexts (MDN: "All the
  methods require a secure context"). Over plain HTTP, `navigator.clipboard` is `undefined`, so the click throws a `TypeError` before any
  promise exists, and the rejection handler never runs.
- Scenario: the app is served from the LAN as `http://192.168.1.10:4173`, which platform-notes names as a hosting option that avoids
  mixed content. Clicking Copy does nothing visible and logs an uncaught error.
- Also: success is not announced to screen readers, and "Copied" never resets.
- Fix: guard with `navigator.clipboard?.writeText`, and fall back to selecting the `<pre>` text. Report the result in a `role="status"`
  element.

**W2. `role="alert"` on content that is not an error the user just caused.** `webApp/src/components.tsx:54`, used at
`webApp/src/pages/Settings.tsx:101` and `webApp/src/pages/AddServer.tsx:86`. Should-fix, high confidence.
- What's wrong: `Notice` gives every `warning` the alert role. The server row's "Offline" and the plain-HTTP warning are therefore
  assertive live regions.
- Scenario: Settings opens with two saved servers that are offline. A screen reader interrupts the page title (which just got focus)
  with "Offline", "Offline". It does so again each time a server's reachability flips during roaming checks.
- WCAG's Understanding 4.1.3 lists using `role="alert"` for content that is "not important and time-sensitive" as a failure.
- Fix: keep `alert` for form errors only. Show Offline as plain text, or as `role="status"` if it should be announced.

**W3. Escape navigates back even while the user is typing.** `webApp/src/App.tsx:33-40`. Should-fix (UX), medium confidence.
- What's wrong: the window-level handler ignores only IME composition and an open `<dialog>`. Escape in the Add server address field or
  in the Sign in fields leaves the page, and the typed input is lost: each page instance gets a new ViewModel (`<main key={route.path}>`).
- Scenario: the user types `media.example.com`, presses Escape to dismiss the browser's URL suggestions, and lands back on Settings with
  an empty form.
- This is deliberate desktop parity (PR 37 checked "Esc on Add server, with focus in its field, returns"), but it is not a browser
  convention: there, Escape closes overlays and stops loading.
- Unverified: whether Chrome and Firefox deliver that keydown to the page while their autofill dropdown is open.
- Suggestion: ignore Escape when the target is an editable field with content, or when `e.target` is inside a `<form>`.

**W4. Sign-in page for a removed server has an empty title.** `webApp/src/pages/SignIn.tsx:16`. Should-fix, high confidence.
- Known follow-up. `strings.signInTitle('')` gives "Sign in to " as the `<h1>` and "Sign in to  · Current" as the document title.
- Use a "Server not found"-style title (it needs a `strings.xml` counterpart).

**W5. Document title is stale for a frame on Home and Sign in.** `webApp/src/pages/Home.tsx:11`, `webApp/src/pages/SignIn.tsx:13`. Nit,
high confidence.
- What's wrong: both return `null` until `usePage`'s effect has run, so `useDocumentTitle` has not run yet. The previous page's title
  stays briefly.
- Scenario: a screen reader or a history entry can record the old title. Chrome stores the title on the history entry when it is
  pushed, which here is before the new title is set. As a result, the Back button's long-press menu in Chrome may list the earlier
  page's title for this entry (medium confidence; not tried).
- Fix: set the title from the route kind in `App`, before the page's ViewModel exists.

**W6. Same-page check compares against the raw pathname.** `web/src/jsMain/.../Router.kt:145`. Nit, high confidence.
- What's wrong: `if (route.path == locationPath()) return` compares the normalised route path with `location.pathname`.
- Scenario: a tab opened at `/settings/` (trailing slash) parses as Settings. Clicking the Settings sidebar entry then pushes a second
  entry for the same page, and Back stays on Settings.
- Fix: compare `Route.parse(locationPath()) == route`, or replace the URL with `route.path` on the first load.

**W7. Two tabs share `localStorage` but not state.** No `storage` listener anywhere: only `online`, `connection.change` and `popstate`
are subscribed. Should-fix, medium confidence (traced in `StoredServerRepository`; not run).
- What's wrong: the saved servers and secrets are read once per page load.
- Scenario: in tab B the user removes a server (secret and entry deleted, session ended on the server). Tab A still shows it signed in
  and keeps sending the revoked token. If tab A then adds a server, `StoredServerRepository` rewrites the index from tab A's memory
  (`ServerRepository.kt:77`), which puts the removed server's ref back in the index. Its entry is gone, so it is skipped on load, but the
  index keeps a dangling ref.
- Fix: a `storage` event listener in `core:database`'s JS store that reloads, or at least `BroadcastChannel` signals for sign-out and
  removal. This mostly belongs to core; see "Seen outside my area".

**W8. No React error boundary.** `webApp/src/main.tsx:11-15`. Nit, high confidence.
- Any exception from the facade during render unmounts the whole tree and leaves a blank page. An example is
  `requireNotNull(...)` in `CurrentWeb.signInPage` (`CurrentWeb.kt:74`); it is unreachable today, because only a parsed sign-in route
  calls it. There is no `<noscript>` either.
- A root error boundary with a "Reload" message is cheap.

**W9. Enum names cross the facade as untyped strings.** `ShellPages.kt:86-100, 136-146`; `strings.ts:41, 68, 91`. Should-fix, high
confidence.
- What's wrong: `problem`, `kind` and `secretStorage` are `string`. The maps are `Record<string, string>`, so a missing key renders an
  empty `Notice` with `role="alert"`, and TypeScript cannot catch it.
- Scenario: `SignInProblem` or `ServerSetupProblem` gains a value in `feature:settings` (the Compose side adds its string). The web shows
  an empty alert, and nothing fails at build time.
- Fix: one of these:
  - type the maps as `Record<'InvalidAddress' | ..., string>` against a hand-written union and test it in `ShellPagesTest` by iterating
    `entries`;
  - add a jsTest that every enum constant has a web string (read `strings.ts` keys from a Kotlin list);
  - at least fall back to a generic "Something went wrong" text.

### Conventions (CLAUDE.md)
- Dependency direction: correct. `web` depends on `feature`, `data` and `core`, and has no Compose. `jsMain` only (the target is
  JS-only), which is right for an umbrella module.
- ViewModels are built with `koin.get()` in the facade, exactly as `composeUi` does in `pageViewModel { ... }`. Interfaces are bound in
  the core modules, and `CapabilitiesState` is read through `HomeViewModel` and `ServersViewModel`. No finding.
- **W10.** `web/build.gradle.kts:28-34` depends on `data:seerr`, `data:stash`, `feature:library`, `feature:requests` and
  `feature:player`, which nothing in `web` uses. Nit, high confidence. They are harmless: production DCE removes unused code, and they
  mirror `apple`'s exports. But a reader cannot tell intent from leftovers, so add a comment, or add them when a page needs them.
- No `CancellationException` is caught. `web` has no try/catch at all.
- Secrets and logging: no `console.*` in `webApp`, and `web` logs nothing. `SignInView.password` crosses the facade, which a controlled
  input needs; it lives only in the ViewModel state and is cleared on sign-in. No finding.

### API design, naming, duplication, comments
- **W11.** The `focusSoon` KDoc (`webApp/src/nav.tsx:61-67`) says a page that focused something itself "has the last word". In practice
  `Page` draws the `<h1>` in the first frame, so the route effect focuses the title at once (frame 10, guard skipped). The form's own
  effect then moves focus to the field once its ViewModel exists. The outcome is right, but the guard only matters for pages that draw
  nothing until their ViewModel exists (Home, Sign in). Nit, medium confidence. Reword the comment.
- **W12.** `webApp/src/pages/PlaybackSupport.tsx:14, 23, 27-68, 75` hard-codes English text ("Probing…", table headers, "yes/no",
  "Copy"), although `strings.ts:1` says it holds "the app's texts". Nit, high confidence. Move them to `webStrings`.
- **W13.** `tsconfig.json:13-16` lists `productionLibrary` before `developmentLibrary`. During `npm run dev`, a stale production `.d.mts`
  left from an earlier `npm run build` wins, so the editor and `tsc` check against old types while Vite serves the new development
  library. Nit, high confidence. List development first, or point both at one copied folder.
- **W14.** `check-licences.mjs:13` strips parentheses before splitting on `AND`/`OR`, which changes precedence. For example,
  `(MIT AND GPL-3.0) OR Apache-2.0` is read as `MIT AND (GPL-3.0 OR Apache-2.0)`. Every case traced errs towards failing, not passing,
  so the check is safe. A `WITH` exception (`Apache-2.0 WITH LLVM-exception`) fails too. Nit, high confidence. Leave a comment saying
  it is deliberately conservative.
- Good:
  - views are flat with a `kind`, and paths come only from the router (`ShellPages.kt:16-17`);
  - `Observable`'s undispatched subscribe is explained and correct for `useSyncExternalStore`'s re-read after subscribing;
  - `RouteView` carries a `visit` through `Shown`, so every navigation is a new snapshot.

### Tests
- **W15.** The riskiest code has no tests: `BrowserRouter` (step bookkeeping, opener record, same-page no-op, replace, popstate),
  `Observable` and `ViewModelHolder`. Should-fix, high confidence.
  - The router's `js()` calls are private top-level functions, so the class cannot run under `jsNodeTest`.
  - Fix: move them behind a small `BrowserHistory` interface (pathname, state, push, replace, back, popstate listener). Bind the real one
    in `CurrentWeb`, and test `BrowserRouter` with a fake. Testing `Observable` needs only a `MutableStateFlow`.
- `ShellPagesTest` misses `AddServerView` for `Found` with `plainHttpOnInternet = true` and with `version = null`, and `SignInView` with
  `working = true`. Nit.
- `RouteTest.onlyAPlainPrimaryClickIsTheApps` covers Ctrl and Meta but not Shift or Alt. Nit.

### Security and privacy
- **W16. No Content-Security-Policy, while sessions sit in `localStorage`.** `webApp/index.html`. Must-fix before any deployment, high
  confidence.
  - ADR 0017 says: "The web app must keep foreign scripts out with a strict Content-Security-Policy". OWASP's HTML5 cheat sheet says "Do
    not store session identifiers in local storage as the data is always accessible by JavaScript". The ADR accepts that risk on the
    condition of a CSP, and none exists yet (known follow-up "for the deploy work").
  - The app has no inline script and no third-party script, so a policy is easy: `script-src 'self'`; `connect-src` and `media-src`
    open to `http:` and `https:`, since servers are user-chosen; `img-src` the same plus `data:`; `object-src 'none'`;
    `base-uri 'none'`; `frame-ancestors 'none'` (header only).
  - Vite's dev server injects inline React-refresh code, so dev needs a looser policy or none.
- **W17. No explicit Referrer-Policy.** Nit, high confidence. The browser default `strict-origin-when-cross-origin` (MDN) already sends
  only the app's origin to user servers. Setting `<meta name="referrer" content="no-referrer">` or the header states the intent, and
  keeps the app's origin out of every Jellyfin, Seerr or Stash access log.

### Accessibility and visual (checked against the CSS tokens; contrast computed with the WCAG formula)
- **W18. Dark-theme contrast fails on raised surfaces.** `webApp/src/styles.css:17, 9, 367, 402-410`. Should-fix, high confidence.
  - `--error` `#e5484d` on `--surface-raised` `#1d2226` is **4.10:1**. That is under 1.4.3's 4.5:1 for the 14 px "Remove" text button
    in every server panel and in the Remove dialog. It is 4.84:1 on the page background, so form errors pass.
  - `--outline` `#3a4247` is **1.85:1** against `--background`. That is under 1.4.11's 3:1 for the text field border, the only visual
    boundary of the address, username and password inputs. The outlined buttons have the same problem, though their coloured label
    helps.
  - The light theme passes (outline 3.06:1, error 5.65:1), as the CSS comment at line 39 says. The dark pairs were never held to the same
    rule.
- **W19. Nothing for forced colors.** Should-fix, high confidence on the CSS, medium on the effect (not run).
  - In Windows contrast themes, author backgrounds are replaced (MDN `forced-colors`). The current sidebar entry is shown only by
    background (`styles.css:171-174`), so the visual "you are here" disappears.
  - The spinner's single coloured `border-top-color` becomes the same system colour as the rest, so it shows as a spinning full ring
    that looks static.
  - Fix: add `@media (forced-colors: active)` with a border or `outline` on `[aria-current='page']` and `forced-color-adjust` care on
    the spinner.
- **W20.** The `:root` font size is `15px` (`styles.css:33`), so the browser's default font-size setting is ignored. Page zoom still
  works, so this passes 1.4.4, but users who set a larger default font get none of it. Nit. Use `font-size: 93.75%` or `rem`-based sizes.
- **W21.** The skip link sits outside every landmark (`App.tsx:54`), and the nav's `aria-label` is the app name ("Current navigation").
  APG says to put all content in landmarks and to label a nav by its purpose. Nit. A `<header>`, or the skip link inside the nav, and
  `aria-label="Main"` would do.
- **W22.** The Remove dialog relies on DOM order for initial focus. React does not render the `autofocus` attribute; it calls `focus()`
  at commit time, before `showModal()` runs, on a closed dialog. Cancel is focused only because `showModal()` picks the first focusable
  element, which happens to be Cancel. Nit, medium confidence (React's behaviour read from memory, not re-checked in its source). This is
  correct today, but swapping the buttons for a platform order would silently focus Remove. Focus Cancel explicitly after `showModal()`.

## 5. Code quality summary

The web area is small (about 2,400 lines with CSS and config), consistent and carefully commented.

Strengths:
- The facade is the thin layer ADR 0011 asked for. Flat views with a `kind`, no suspend exports, one ViewModel per page instance, and
  paths built only by the router keep React free of business logic.
- Browser conventions are taken seriously: real links that respect middle-click and modifier keys, deep links that survive a reload,
  Back from a deep link that stays inside the app without changing `history.length`, focus restored to the element that opened the page,
  per-page titles, `autocomplete` hints, and a native `<dialog>`.
- The 49-check run against the real core is unusually thorough for a shell.
- Dependencies are minimal: React only at runtime, with a licence gate.

Main problems:
1. Deployment readiness: no CSP while sessions live in `localStorage` (W16), no favicon, manifest or icons, no documented SPA fallback
   for a static host.
2. Accessibility details that the headless run could not see:
   - dark-theme contrast on panels and inputs (W18);
   - assertive alerts for passive states (W2);
   - no forced-colors support (W19);
   - no screen reader pass at all.
3. Test coverage stops at pure functions. The router glue, `Observable` and the page lifecycle are untested (W15), and only Chrome has
   ever run the shell.
4. Small robustness gaps: clipboard on http (W1), untyped enum names (W9), no cross-tab sync (W7).

## 6. Improvements (ranked)

| # | Title | Why | Modules | Effort | Priority |
|---|---|---|---|---|---|
| 1 | CSP and Referrer-Policy for the built app, plus a hosting note (SPA fallback, headers) | ADR 0017's condition for `localStorage` sessions; W16, W17; PR 6's unchecked production deep links | `webApp` (index.html or a Vite plugin for the production build only), docs | M | High (must before any deployment) |
| 2 | Dark-theme contrast fix | W18: fails WCAG 1.4.3 and 1.4.11 on every Settings visit in dark mode. Brighter `--outline` and a raised-surface error colour; mirror in `core:designsystem` Tokens and extend ContrastTest to dark | `webApp`, `core:designsystem` | S | High |
| 3 | Live regions and clipboard | W2 (alert only for errors), W1 (guarded clipboard with a status message) | `webApp` | S | High |
| 4 | Known follow-ups bundle | W4 sign-in title, favicon and icons (removes the `/favicon.ico` 404), PlaybackSupport strings (W12) | `webApp` | S | High |
| 5 | Router and Observable tests | W15: put the History API behind an interface; test the step, opener, replace, popstate and same-page rules, `Observable` caching and unsubscribe, and `ViewModelHolder.close` | `web` | M | Medium |
| 6 | Typed problem names | W9: string unions or an exhaustiveness test, so a new enum value cannot give an empty alert | `web`, `webApp` | S | Medium |
| 7 | Forced colors and font size | W19, W20 | `webApp` | S | Medium |
| 8 | Escape scope | W3: don't navigate away from a form field with content; keep Escape for dialogs and non-form pages | `webApp` | S | Medium |
| 9 | Web app manifest and installability | `name`, `start_url`, `display`, 192 and 512 px icons, `theme-color` (section 7) | `webApp` | S | Medium |
| 10 | Cross-tab consistency | W7: `storage` event or `BroadcastChannel` for sign-out and removal | `core:database`, `core:network` (JS), `web` | M | Medium |
| 11 | Browser and assistive-technology pass | Firefox, Safari, Edge, Chrome on Android, mobile Safari; NVDA and VoiceOver; 200%/400% zoom; forced colors | docs/platform-notes.md | M | Medium |
| 12 | Error boundary and `<noscript>` | W8 | `webApp` | S | Low |
| 13 | Small cleanups | W6 same-page check, W10 unused deps or a comment, W11 comment, W13 tsconfig order, W14 comment, W21 landmarks, W22 explicit dialog focus | `web`, `webApp` | S | Low |
| 14 | Address editing on the web | Known gap: desktop and Android have the addresses page (PR 35) | `web`, `webApp` | M | Low (until roaming matters on the web) |

## 7. Platform conventions and UX research

Sources read 2026-10-21 unless marked otherwise:
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
- Understanding 4.1.3 Status Messages: https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html
- WAI-ARIA APG, modal dialog: https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/
- WAI-ARIA APG, landmark regions: https://www.w3.org/WAI/ARIA/apg/practices/landmark-regions/
- MDN `forced-colors`: https://developer.mozilla.org/en-US/docs/Web/CSS/@media/forced-colors
- MDN Clipboard: https://developer.mozilla.org/en-US/docs/Web/API/Clipboard
- MDN Making PWAs installable: https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable
- MDN Content-Security-Policy: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy
- MDN Referrer-Policy: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Referrer-Policy
- OWASP HTML5 Security Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html
- Chrome Local Network Access and the mixed-content rules: as recorded in docs/platform-notes.md (read there 2026-10-19).

| Topic | Guideline | The app now | Gap |
|---|---|---|---|
| Landmarks | APG: put all perceivable content in landmarks; label navs without the word "navigation" | `<nav>` and `<main>`; skip link outside both; nav labelled "Current" | W21 (minor) |
| Modal dialog | APG: focus moves into the dialog; for irreversible actions the least destructive control may get it; Escape closes; focus returns to the invoker, or somewhere logical if it is gone | Native `<dialog>` with `showModal`; Cancel focused; Escape cancels and returns focus to Remove; after removal, focus goes to Add server | Meets it; W22 is a fragility |
| SPA focus on navigation | Move focus to the new content (common practice; WCAG 2.4.3 Focus Order) | Focus on the `<h1>` (tabindex -1), or the first field on forms, or the opener on Back/Forward | Meets it |
| Status messages (4.1.3) | `status` for progress and results; `alert` only for important, time-sensitive messages; overusing assertive is a failure | `Progress` is `role=status`; errors and **warnings** are `role=alert` | W2 |
| Contrast (1.4.3, 1.4.11) | 4.5:1 for text, 3:1 for component boundaries | Light theme passes; dark fails on panels and inputs | W18 |
| Target size (2.5.8, 24×24 px) | Minimum target size | Sidebar 44 px, buttons at least 40 px high | Meets it |
| Accessible authentication (3.3.8) | No cognitive test; allow paste and password managers | Native fields with `username` and `current-password`; Quick Connect as an alternative | Meets it (autofill unverified in this shell) |
| Back/Forward, deep links, middle-click, titles | Browser conventions | All handled; titles "Page · Current"; Back from a deep link stays in the app | W5 and W6 (minor); scroll restoration with async content untried (it will matter with M2's long library lists) |
| Responsive | Reflow at 320 CSS px (1.4.10) | Sidebar becomes a top bar under 640 px; tables at 100%; `pre` scrolls | Likely meets it; never tried on a phone or at 400% zoom |
| `prefers-color-scheme` | Respect the system | Dark default, light under the query; `<meta name="color-scheme">` | Meets it |
| `prefers-reduced-motion` | Reduce non-essential motion | Spinner slows to 3 s | Meets it (2.3.3 is AAA anyway) |
| `forced-colors` | Small fixes where the forced palette loses meaning (MDN) | None | W19 |
| Installability | Manifest with `name`/`short_name`, 192 and 512 px icons, `start_url`, `display`; https or localhost; service worker not required (MDN) | No manifest, no icons, no favicon | Section 6, #4 and #9 |
| CSP | ADR 0017 requires a strict one for `localStorage` secrets | None | W16 |
| Referrer-Policy | Default `strict-origin-when-cross-origin`; can be set with `<meta name="referrer">` (MDN) | Default | W17 |
| Token storage | OWASP: no session identifiers in `localStorage` | `localStorage` by design (ADR 0017, alternatives weighed there) | Accepted risk; the CSP is the mitigation |
| Clipboard | Secure contexts only (MDN) | Unguarded | W1 |

Recommendations, each tagged with its platform:
1. **[Web, all browsers]** Ship the CSP and Referrer-Policy as response headers from the host, with a `<meta>` fallback for hosts that
   cannot set headers. Note: `frame-ancestors` has no effect in `<meta>` per the CSP Level 3 spec; MDN's CSP page does not say so, so
   this is unverified here. Document the host's SPA fallback (every unknown path → `index.html`) next to it.
2. **[Web, all browsers]** Add `manifest.webmanifest` (`name` "Current", `short_name`, `start_url` "/", `display` "standalone",
   `background_color` and `theme_color` from the dark tokens) with 192, 512 and maskable icons, plus a favicon and an
   `apple-touch-icon`. Make it installable on Chromium and "Add to Home Screen" ready on iOS. Unverified: Safari's exact use of
   manifest icons versus `apple-touch-icon` on iOS 26.
3. **[Web, Windows]** Add a `forced-colors` block: outline the current sidebar entry, keep focus rings as `outline` (they survive), and
   make the spinner readable.
4. **[Web, all browsers]** Fix the dark tokens to meet 4.5:1 for text and 3:1 for input borders on every surface. Extend the desktop's
   ContrastTest to the dark pairs, so `styles.css` and `Tokens.kt` stay in step.
5. **[Web, all browsers]** Use `role="status"` (polite) for state changes that the user did not just cause (offline, waiting), and
   `alert` only for form errors.
6. **[Web, all browsers]** Restore scroll on Back once the page's content has drawn. This matters with M2's library grids: set
   `history.scrollRestoration = 'manual'`, store `scrollY` in the entry's state, and restore it after the first data frame. This is the
   common practice for SPAs with async content.
7. **[Web, mobile]** At phone width, consider moving the sidebar to a bottom bar with the same entries once there are more than two.
   Most media apps do this on phones. That is an observation, not a guideline, so check it against the Apple and Material guidance the
   other workers cite.
8. **[Web, all browsers]** What most media web apps do, for M2 planning (observation, not a standard):
   - posters are real links;
   - Media Session metadata and actions;
   - keyboard shortcuts only inside the player (Space, arrows, F, M);
   - a persistent sign-in;
   - a dark theme by default.

   The shell already does the first, fourth and fifth, and ADR 0011's spike did Media Session and the player keys.
9. **[Web, Chrome/Edge/Firefox]** Before a public https deployment, run the Add server flow against `http://192.168.1.10:8096`. Record
   the local network access prompt and the mixed-content outcome, which platform-notes still lists as untried.

## Seen outside my area
- `core:database` / `core:network` (JS): nothing reacts to `localStorage` changes from another tab, and `StoredServerRepository`
  rewrites the server index from memory on save (`ServerRepository.kt:77`). A second tab can put a server removed elsewhere back into
  the index (W7).
- `core:designsystem`: the dark tokens behind W18 (`--error` on raised surfaces, `--outline`) are said to mirror `Tokens.kt`. If so,
  desktop and Android have the same contrast gaps in dark mode. Not checked there.
- `feature:settings`: `SignInViewModel` asks `GET /QuickConnect/Enabled` twice, once for the button and again on start (known
  follow-up, seen in the web run).
