# Compose side review (2026-10-21)

Scope: `core/designsystem`, `composeUi`, `shared`, `androidApp` and `desktopApp`, read at `origin/main` 6fb1957. This is review only, and
no code was changed. Gradle was not run because Maven Central answered HTTP 429. Every finding below comes from reading the code. Library
behaviour is cited from source only where it was read: the Koin 4.2.2 Compose loader on GitHub. Anything else is marked as inferred.

## 1. Features in this area

| Feature | What it does | ADR | PRs | Checked on (docs/platform-notes.md, PR descriptions) |
|---|---|---|---|---|
| Design tokens and theme | `CurrentTokens` (palette, spacing, radius, sizes, type) mapped onto Material 3. Dark and light follow the system. Light colours meet WCAG AA (`ContrastTest`). | 0005, 0014 | #24, #29 | Windows 11 at 150% (#28, #29). Linux under Xvfb (#24). Android emulator, light and dark (addresses run). |
| Shared components | `Page`, `SectionTitle`, `Panel`, `Notice`, `EmptyState`, `ErrorState`, `LoadingState`, `InlineProgress`, the app's own line icons (`CurrentIcons`), `codeStyle` | 0005 | #24, #28 | as above |
| Keyboard model | `FocusVisibility`: a ring only for focus that arrives by keyboard. `focusRing`, `trackFocusVisibility`, `ListFocus` (Up and Down inside a list, items tracked by key), and `formField` (Enter submits, Tab moves on) | 0005 | #24, #28, #35, #36 | Windows by hand (#28; #35's Windows keyboard pass was "still pending"). Android emulator (#36 case 5, the addresses run). Linux Xvfb with `java.awt.Robot` (#24). |
| Sidebar | `SidebarScaffold`, `SidebarTitle`, `SidebarItems`, `SidebarItem` (a 232 dp fixed rail) | 0005 | #24 | Windows, Linux Xvfb, Android (layout broken at phone width) |
| Destination contract | `ShellDestination`, `ShellPage`, `SidebarEntry`, `shellDestination()` (bindings qualified by type), `ShellGraph` validation, shared routes (`SettingsRoute`, `AddServerRoute`, `SignInRoute`), `Navigator`, `pageViewModel` | 0014 | #24, #28 | JVM tests (`ShellGraphTest`) |
| Shell and history | `Shell`: sidebar plus `NavHost`, a Back button on inner pages, Alt+Left, focus restored on return. `ShellHistory`: one history capped at 50 steps, and `ControllerNavigator` rebuilds the back stack when trimming | 0014 | #24, #28 | Windows by hand (#28). The rebuild path and Android system Back through the history were not run (#28). |
| Home screen | No server, signed out or signed in (`HomeViewModel`), plus the Android local network notice (`HomeLocalNetworkViewModel`) | 0016, 0018, 0020 | #24, #36 | Windows, Android emulator (#36 cases 1-6) |
| Settings screen | Saved servers (sign in or out, addresses, remove with confirmation), the secret store note, "Keep sign-ins in a file", About | 0016, 0017, 0018 | #24, #28, #35 | Windows with a real Jellyfin (#28). Android emulator. |
| Add server and Add address | Probe, found card, plain-http warning, the "Nearby devices" explanation, ask, retry and timeout hint (Android) | 0016, 0019, 0020 | #24, #27, #35, #36 | Windows (#28). Pixel 8 Pro and emulator (#27, #36, #35). |
| Sign in | Password and Quick Connect, mapping of every problem, focus handling | 0018 | #24 | Windows against a real server (#28). Not re-run after #29 (#29). |
| Addresses page | Priority order, address in use, reachability, Move up and down, Remove, and focus kept in place | 0019 | #35 | Android emulator (all focus cases). Windows keyboard pass by the owner (commit message). |
| Strings | `composeUi` `strings.xml` (one language) | — | #24 onwards | Android APK resource check (#30, #36) |
| App wiring (`shared`) | `appModules`, `CurrentKoin` (one Koin graph around every window), `AppContent`, `MainWindowInput` | 0014, 0010 | #24, #28, #36 | Desktop and Android runs |
| Android shell | `MainActivity`: edge-to-edge, the local network permission launcher, a touch-mode listener and `dispatchKeyEvent` for focus rings. Manifest permissions, cleartext config | 0020 | #27, #30, #36 | Pixel 8 Pro and emulator, Android 17 |
| Desktop shell | `main.kt`: window size and minimum size, Koin around windows. `WindowsTitleBar`: DWM dark frame through JNA. MSI and DEB packaging | 0010, 0017 | #24, #28 | Windows 11 build 26200 (title bar). **The packages were never built or installed.** |

## 2. File map

| Path | What it holds | Feature(s) |
|---|---|---|
| `core/designsystem/build.gradle.kts` | Compose module, android and jvm, resources enabled | build |
| `core/designsystem/.../Tokens.kt` | `CurrentTokens`: palette, spacing, radius, sizes, type | Tokens |
| `core/designsystem/.../Theme.kt` | Dark and light `ColorScheme`, typography, `LocalPanelEdge`, `CurrentTheme`, `codeStyle` | Theme |
| `core/designsystem/.../CurrentIcons.kt` | Six stroked `ImageVector`s drawn in code | Components |
| `core/designsystem/.../Page.kt` | `LocalPageTop`, `Page`, `SectionTitle`, `Panel`, `Notice`, `NoticeKind` | Components |
| `core/designsystem/.../States.kt` | `StateAction`, loading, error and empty states, `InlineProgress` | Components |
| `core/designsystem/.../Sidebar.kt` | `SidebarScaffold`, `SidebarTitle`, `SidebarItems`, `SidebarItem` | Sidebar |
| `core/designsystem/.../Keyboard.kt` | `FocusVisibility`, `trackFocusVisibility`, `focusRing` node, `ListFocus`, `formField` | Keyboard model |
| `core/designsystem/src/commonTest/.../ContrastTest.kt` | WCAG AA checks, light theme only | Theme |
| `core/designsystem/src/commonTest/.../FocusVisibilityTest.kt` | Ring shown or hidden after key, pointer and touch-mode events | Keyboard model |
| `core/designsystem/src/commonTest/.../ListFocusTest.kt` | Neighbour lookup, moved and removed items | Keyboard model |
| `composeUi/build.gradle.kts` | Compose module: navigation, Koin, Coil, features, resources | build |
| `composeUi/.../composeResources/values/strings.xml` | Every UI string | Strings |
| `composeUi/.../navigation/ShellDestination.kt` | `ShellDestination`, `shellDestination()`, `ShellPage`, `SidebarEntry`, `ShellHistory`, `Navigator`, `ShellGraph` | Contract, history |
| `composeUi/.../navigation/Routes.kt` | `SettingsRoute`, `AddServerRoute`, `SignInRoute` | Contract |
| `composeUi/.../navigation/ViewModels.kt` | `pageViewModel` (Koin-made ViewModel per back stack entry) | Contract |
| `composeUi/.../shell/Shell.kt` | `Shell`, `BackButton`, `ControllerNavigator` | Shell and history |
| `composeUi/.../home/HomeDestination.kt` | `HomeRoute`, `HomeDestination`, `homeUiModule`, the home screen and its local network ask | Home |
| `composeUi/.../localnetwork/LocalNetworkNotice.kt` | Explain, Denied and Blocked notice with its buttons | Home, Add server |
| `composeUi/.../settings/SettingsDestination.kt` | Settings-only routes (addresses, add address), `SettingsDestination`, `settingsUiModule` | Settings |
| `composeUi/.../settings/SettingsScreen.kt` | Servers list, focus return, secret store note, About, `ServerRef.saveKey` | Settings |
| `composeUi/.../settings/AddServerScreen.kt` | Add server and Add address, local network prompt and hint | Add server |
| `composeUi/.../settings/SignInScreen.kt` | Password form, Quick Connect code | Sign in |
| `composeUi/.../settings/ServerAddressesScreen.kt` | Addresses list, reorder and remove, pending-focus machine | Addresses |
| `composeUi/.../settings/ConfirmRemoveDialog.kt` | Remove confirmation, Cancel focused | Settings, Addresses |
| `composeUi/src/jvmTest/.../ShellGraphTest.kt` | Koin contributions, graph validation, `ShellHistory` trimming | Contract, history |
| `shared/build.gradle.kts` | Compose umbrella: every core, data and feature module | App wiring |
| `shared/.../App.kt` | `appModules`, `App`, `CurrentKoin`, `MainWindowInput`, `AppContent`, `expect platformModules` | App wiring |
| `shared/src/androidMain/.../PlatformModules.android.kt` | Binds the application `Context` | App wiring |
| `shared/src/jvmMain/.../PlatformModules.jvm.kt` | No platform modules | App wiring |
| `androidApp/build.gradle.kts` | Application module: SDK 24 to 37, no minify | Android shell |
| `androidApp/proguard-rules.pro` | Wizard boilerplate only | Android shell |
| `androidApp/src/main/AndroidManifest.xml` | Permissions, backup, theme, launcher activity | Android shell |
| `androidApp/.../MainActivity.kt` | Edge-to-edge, permission launcher, touch-mode listener, key forwarding, a preview | Android shell, Keyboard model |
| `androidApp/src/main/res/values/strings.xml` | `app_name` | Android shell |
| `androidApp/src/main/res/xml/network_security_config.xml` | Cleartext allowed for every host (ADR 0020) | Android shell |
| `androidApp/src/main/res/{mipmap-*,drawable*}` | Launcher icons (wizard default) | Android shell |
| `desktopApp/build.gradle.kts` | JVM app, JNA, MSI and DEB targets | Desktop shell |
| `desktopApp/.../main.kt` | Window, minimum size, title bar theme, `CurrentKoin` | Desktop shell |
| `desktopApp/.../WindowsTitleBar.kt` | `DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)` through JNA | Desktop shell |

## 3. Unverified parts, per feature

- **Desktop packaging (MSI, DEB).** No PR ever ran `packageMsi`, `packageDeb` or `createDistributable`. CI and `check.sh` do not either.
  Never checked:
  - which JDK modules jlink includes (see finding C1);
  - the installed app's name, icon and Start menu entry;
  - upgrade from one MSI to the next;
  - a DEB installing on a real Debian or Ubuntu.
- **Linux desktop.** It ran only under Xvfb with `java.awt.Robot` (#24). Never checked:
  - a real window manager (GNOME or KDE), HiDPI or fractional scaling;
  - dark theme detection: CMP's system theme polling is off on Linux (platform-notes "Windows title bar in dark mode"), so whether `isSystemInDarkTheme()` follows GNOME's `color-scheme` is unverified;
  - the Secret Service string on a real desktop session (#28).
- **Windows.**
  - The dark title bar on Windows 10 (#28, platform notes).
  - Text scaling (Settings > Accessibility > Text size) and contrast themes. Never mentioned in any PR.
  - Narrator or any screen reader. The Java Access Bridge route (see section 7) was never tried.
  - The Addresses page by keyboard: #35's checklist was "still pending" at merge. The commit message says the owner later checked it on Windows by keyboard, but the steps are not recorded in platform-notes.
- **Shell and history.**
  - The 50-step trim and rebuild (`ControllerNavigator.rebuild`), and focus restore on a rebuilt page. Never run (#28). No test covers `ControllerNavigator`: `ShellGraphTest` tests only `ShellHistory.keptBeforeOpening`, the pure list function.
  - Android system Back and a remote's Back through the history (#28). Only the Addresses page's Back was run on the emulator.
  - Esc as Back is not in the app's code (`Shell.kt` handles only Alt+Left). It went back on Windows (#28) and does not on Android (addresses run, step 6). Where desktop's Esc handling comes from was not traced.
- **Sign in.** Not re-run after the light-contrast change (#29). Quick Connect on Android was never run: platform-notes lists only desktop and web runs.
- **Android.**
  - Phone layout: unusable at phone width (115 dp of content, platform-notes "Server addresses page"). Tablets, foldables and multi-window: never run.
  - Status-bar and navigation-bar insets: not applied anywhere. The sidebar title and scrolled content sit under the status bar (#27, #36).
  - Predictive back animation with targetSdk 37: never checked.
  - TalkBack: never run, although #35 adds content descriptions for screen readers.
  - Font scale: only 1.5 in landscape on Home (#36 case 6). 2.0 never.
  - Android TV: nothing.
  - From #36: the dismissed permission dialog, **Try again** and **Open settings** from the Blocked notice, and the Blocked notice on Add server.
  - Android backup of the settings and device id (#27: "not excluded from Android backups yet").
- **Theme.** No test checks the dark theme's contrast. Dark mode was judged by eye only (#28, the addresses run). Section 4 has two failures.
- **App wiring.**
  - No test checks that `appModules` resolves every binding the screens `get()`. A missing binding fails only when that page opens.
  - `shared/commonTest` exists with no tests.
- **Missing tests (UI-agnostic logic that could be unit-tested):**
  - `ControllerNavigator`'s route bookkeeping and rebuild;
  - `formField` key handling;
  - `FocusRingNode` geometry;
  - the `ServerSetupProblem` and `SignInProblem` to string maps (an exhaustive `when` already guards them).

## 4. Code review

Severity is must-fix, should-fix or nit. Confidence is high when read in this repo's code, and medium when it depends on library or
platform behaviour that was not run.

### Correctness and platform

**C1. The packaged desktop app probably lacks JDK modules it needs.** `desktopApp/build.gradle.kts:24-28` sets no `modules(...)`. The
desktop HTTP engine is `ktor-client-java` (`core/network/build.gradle.kts:47`), which needs `java.net.http`. Desktop accessibility
needs `jdk.accessibility` in packaged builds (JetBrains' Compose Desktop accessibility page). The Compose plugin's jlink runtime
includes only a default module set unless `modules(...)` or `includeAllModules` is given (from the Compose Desktop packaging docs;
not re-read today).
- Scenario: the user installs the MSI, starts Current and adds a server. Every request fails with `NoClassDefFoundError` for `java.net.http.HttpClient`, and Narrator or NVDA get nothing.
- `./gradlew :desktopApp:run` uses the full JDK, so no run so far could show it.
- Fix: run `suggestRuntimeModules` once, then list the modules, plus a CI step that builds a distributable and starts it.
- Severity must-fix (before any release). Confidence medium: inferred from docs, never packaged.

**C2. Pressing Enter in an empty address field can trigger Android's "Nearby devices" dialog.** `AddServerScreen.kt:90`:
`check = { if (state is Editing) localNetwork.beforeCheck(viewModel::check) }`. The Check button is disabled on blank input (`:118`),
but `formField { check() }` (`:112`) and `onGo` (`:111`) call `check` anyway. `beforeCheck` asks for the permission first when the
state is `Ask()` (`LocalNetworkPromptViewModel.kt:80`).
- Scenario: on Android 17 with the permission undecided, the user opens Add server and presses Enter, or the IME's Go, with the field empty. The system dialog opens for no address at all.
- A "Don't allow" there counts toward the permanent denial that platform-notes ("Runtime permission guidance") and ADR 0020 try to keep a deliberate choice. Then "That is not a server address." follows.
- Fix: `if (state is Editing && state.input.isNotBlank())`.
- Severity should-fix. Confidence high.

**C3. After an Android activity recreation, trimming the history turns lost pages into Home.** `Shell.kt:150` keeps
`ControllerNavigator.routes` in a plain map, remembered with the controller (`:74`). It is not saved. `rememberNavController()` restores
the back stack after rotation, dark/light switch, locale change or process death (the manifest declares no `configChanges`), but
`routes` starts empty. Once the history reaches 50 steps, `rebuild` maps every entry without a recorded route to `start`
(`:176`, `routes[it.id] ?: start`).
- Scenario: on a phone, rotate, then keep navigating. On the 50th step, Back walks through a run of Home pages instead of Settings, Add server and so on.
- `shownEntries` (`:72`) is also lost, so focus is not restored on the first return after a recreation. The comment admits this.
- Fix: derive the route from `entry.toRoute`/`entry.arguments` with the destination's route class instead of a side map, or save the map.
- Severity should-fix (rare, but silent). Confidence high (code), medium (the 50-step path never ran).

**C4. A state screen's button takes focus from the sidebar.** `States.kt:106`: `LaunchedEffect(Unit) { if (keyboard) focus.requestFocus() }`
fires whenever an `EmptyState` with an action first appears. `Shell.kt:117` avoids moving focus while `sidebarHasFocus`, but `StateButtons`
does not check it.
- Scenario: the user Tabs into the sidebar while Home is still loading. A restored session turns Home into the signed-out state, and "Sign in" grabs focus. The next Down or Enter acts on the page, not the sidebar.
- Seen once on Windows (#28, "takes focus from where the user was").
- Fix: expose the sidebar's focus state (for example a `LocalShellFocus`) and skip the request when the sidebar or another element holds focus.
- Severity should-fix. Confidence high.

**C5. Esc does not go back on Android, and the shell's KDoc says it does.** `Shell.kt:61` and `:63`, and docs/architecture.md, say "Alt+Left,
Esc and a system or remote Back work on every page". On Android, Esc does nothing (addresses run, step 6). On desktop Esc does go back
(#28), but not through app code. Windows' own guidance says Esc "does not close, or back navigate through, app UI" (section 7), so the
desktop behaviour itself is also off-platform.
- Fix: make the rule explicit. Esc closes transient UI (dialogs, later menus and the player overlay). Back is Alt+Left, the mouse back button, the system Back and the Back button. Either handle Esc in `Shell` deliberately per platform, or correct the KDoc and architecture.md.
- Severity should-fix (doc accuracy and platform convention). Confidence high for Android. The source of desktop's Esc handling was not traced.

**C6. Alt+Left does nothing while nothing has focus.** The handler is on `SidebarScaffold`'s `onPreviewKeyEvent` (`Shell.kt:83-87`), and
keys reach it only while something inside has focus (as `trackFocusVisibility`'s own KDoc says). The window-level hook
(`MainWindowInput.onPreviewKeyEvent`, `App.kt:61`) only feeds focus visibility.
- Scenario: click empty page space, press Alt+Left. Nothing happens.
- The mouse back button (X1) is not handled at all.
- Fix: route Back keys through `MainWindowInput` too, and handle the X1 button on the shell's pointer input.
- Severity should-fix (desktop convention). Confidence high for Alt+Left (code); the X1 claim is by absence.

**C7. Android backs up the device identity.** `AndroidManifest.xml:12`: `android:allowBackup="true"` with no `dataExtractionRules` or
`fullBackupContent`. `core:database` keeps `deviceId` in the `current-local` `SharedPreferences` file, which ADR 0017 calls machine-local.
- Scenario: the user restores to a new phone. Both phones report the same device id, and Jellyfin treats them as one device. It ties sessions to the device id, so signing in on one can end the other's session; that last part is inferred from Jellyfin's behaviour and not run.
- #27 lists this as M4. It belongs in M4's first Android PR: exclude `current-local.xml`.
- Severity should-fix. Confidence high (manifest), medium (server effect).

**C8. Dark theme contrast fails WCAG AA in two places, and no test covers dark.** Computed with the WCAG formula from `Tokens.kt`:
- `outlineDark` `#3A4247`, used as `outline` and `outlineVariant` (`Theme.kt:35-36`), is 1.85:1 on the page, 1.73:1 on the sidebar and 1.57:1 on panels. Text field and outlined button borders need 3:1 (WCAG 1.4.11). The light theme was fixed for exactly this in #29.
- `danger` `#E5484D` on `surfaceRaisedDark` is 4.10:1. That is the red "Remove" label inside a server or address `Panel` (`SettingsScreen.kt:219`, `ServerAddressesScreen.kt:229`); text needs 4.5:1.
- Fix: lighten `outlineDark` to about `#6B767D`, use a lighter danger for text on panels, and run `ContrastTest` over `darkColors` too.
- Severity should-fix. Confidence high.

**C9. The sidebar item's fixed height clips large text.** `Sidebar.kt:91`: `.height(Size.sidebarItemHeight)` (44 dp), with an unbounded
label `Text` (`:103`). Android scales fonts up to 200% (Android 14+) and Windows scales text up to 225%.
- Scenario: at 200%, the 14 sp label becomes about 28 sp with a line height near 40 sp. It is clipped by the 44 dp row with its padding, and labels like "Settings" in other languages overflow the 232 dp rail.
- Also 44 dp is below Material's 48 dp touch target, which matters on Android.
- Fix: `heightIn(min = 48.dp)`.
- Severity should-fix. Confidence high for the clip mechanics. Not run at 200%.

### Error handling

- No `try`/`catch` in this area except `WindowsTitleBar.kt:27`, which catches `UnsatisfiedLinkError` (not a `CancellationException`).
  CancellationException rule: OK.
- `WindowsTitleBar.setDark` ignores the HRESULT (`:37`). That is fine, as it is purely cosmetic. Nit: log a failure once at debug level.

### Conventions (CLAUDE.md)

- Dependency direction is respected. `composeUi` reaches `core:database` only for the `SecretStorage` enum. No Compose imports appear in
  pure-Kotlin modules from this side.
- **CapabilitiesState and service checks in the UI (nit).** Three screens decide on `ServiceType.jellyfin` themselves: `HomeDestination.kt:123`,
  `SettingsScreen.kt:207` and `AddServerScreen.kt:149` ("only Jellyfin has a sign-in page"). That rule belongs in the ViewModel state,
  for example `ServerRow.canSignIn` and `HomeUiState.SignedOut.canSignIn`, so SwiftUI and React do not each copy it. Confidence high.
- **Global singleton instead of a Koin binding (nit, outside the area's code).** `MainActivity` talks to the top-level
  `localNetworkRequests` from `core:network` (`MainActivity.kt:15`, `:23`, `:36`). This is understandable for an Activity, but it is a
  second wiring path next to Koin, and `androidApp` depends on `core:network` directly (`androidApp/build.gradle.kts:15`).
- `commonMain` first: yes. Only `platformModules` is `expect`/`actual`.

### API design, naming, duplication, dead code

- **D1 (should-fix).** Three identical route shapes: `SignInRoute` (`Routes.kt:20`), `ServerAddressesRoute` (`SettingsDestination.kt:23`)
  and `AddServerAddressRoute` (`:31`). Each has `(service, key, name)`, a secondary constructor and `server`. A shared `ServerArgs` (or a
  helper interface) removes the copy.
- **D1, continued.** `ServerRef.saveKey` lives in `SettingsScreen.kt:226` but is used by three files. `SignInScreen.kt:64` re-spells it as
  `"${route.service}/${route.key}"`. Use `route.server.saveKey` and move `saveKey` next to the routes.
- **D2 (nit).** `HomeDestination.kt:90-108` (`LocalNetworkAsk`) and `AddServerScreen.kt:158-172` (`LocalNetworkPrompt`) map two parallel
  feature states (`HomeLocalNetworkState`, `LocalNetworkPromptState`) to the same `LocalNetworkNoticeKind`. One shared state type in
  `feature` would make one mapper.
- **D3 (nit).** `Shell` KDoc (`Shell.kt:59`) and `SidebarScaffold` KDoc (`Sidebar.kt:34`) say "on wide screens", but this is the only
  layout, phones included.
- **D4 (nit, dead config).**
  - `shared/build.gradle.kts` applies `kotlinSerialization` and sets up `withDeviceTestBuilder` and an instrumentation runner with no tests.
  - It depends on `data:seerr`, `data:stash`, `feature:library`, `feature:requests` and `feature:player`, which are not in `appModules`.
  - It adds `uiToolingPreview` in both common and android.
  - `composeUi` pulls in `coil-compose` and `coil-network-ktor3` with no images yet.
  - `proguard-rules.pro` is wizard boilerplate.
  - `MainActivity.AppAndroidPreview` (`:63-67`) calls `App()`, which starts the real Koin graph inside a preview.
- **D5 (nit).** Version drift: `AppInfo.VERSION` is `0.1.0` (gradle.properties), MSI/DEB `packageVersion` is `"1.0.0"`
  (`desktopApp/build.gradle.kts:27`), and Android is `versionName "1.0"` and `versionCode 1`. The installers and the About page will
  disagree. Feed all three from `current.version`.
- **D6 (should-fix, packaging).** `packageName = "com.skeksik.current"` (`desktopApp/build.gradle.kts:26`) is also the installed app's
  display name, unless `windows { menuGroup }` or `linux { packageName }` say otherwise. There is no `iconFile`, no Windows `upgradeUuid`
  (needed for MSI upgrades), no `menu` or `shortcut`, and no DEB maintainer. Confidence medium (Compose packaging docs from memory;
  nothing was packaged).
- **D7 (nit).** `main.kt:19` hardcodes `"Current"` while the comment points to `AppInfo.NAME`. Use the constant.
- **D8 (nit).** `CurrentIcons.Back` (`CurrentIcons.kt:42`) is built without `autoMirror = true`, and the manifest declares
  `supportsRtl="true"`. In an RTL locale the Back arrow points the wrong way.
- **D9 (nit).** `ServerAddressesScreen.kt:75` mutates a remembered map during composition (`apply { keys.retainAll(...) }`), as does
  `ListFocus.keys`'s setter (`Keyboard.kt:207-210`). It works today, but side effects in composition run again on every recomposition and
  are fragile under future concurrent composition. A `SideEffect` or derived cleanup is cleaner.
- **D10 (nit).** `AddServerScreen.kt:173` and `SignInScreen.kt:116` focus the first field unconditionally, while `Shell`'s KDoc says pages
  place focus "only after keyboard input, so a touch never opens a soft keyboard". Focusing a form's first field is a reasonable choice,
  but on Android it opens the soft keyboard after a tap. Make the rule and the code agree, and document the form exception.
- **D11 (nit).** `States.kt:37`, `:46` and `:63` write `32.dp` and `40.dp` instead of tokens, against `CurrentTokens`' own KDoc ("screens
  read ... sizes from here").

### Accessibility (screen readers)

- **A1 (should-fix).** No headings. `Page`'s title (`Page.kt:51`), `SectionTitle` (`:66`) and `SidebarTitle` lack
  `semantics { heading() }`, so TalkBack users cannot jump between sections. Confidence high (grep finds no `heading()`).
- **A2 (should-fix).** Problems are not announced. `Notice` (`Page.kt:85`) has no `liveRegion`. A failed check ("No server answered.") or a
  wrong password appears silently for TalkBack users, and focus moves to the field, which reads the field rather than the error.
  Polite live regions on error notices fix it.
- **A3 (nit).** `InlineProgress` ("Signing in…") is not a live region either.
- Good: the address buttons carry per-row content descriptions (`ServerAddressesScreen.kt:201-227`), decorative icons use
  `contentDescription = null`, the sidebar is a `selectableGroup` with `Role.Tab`, and Material components set their own roles.

### Security and privacy

- Nothing in this area logs, and no secret is shown or stored in UI state beyond the password field, which is masked.
- Cleartext is allowed for every host (`network_security_config.xml`). This is deliberate per ADR 0020, with the rule enforced in code.
  Coil and later Media3 are not covered by that code rule (platform-notes: not verified). Revisit before images load from servers.
- C7 (backup) is the one privacy-relevant item.

### Tests

- `ShellGraphTest` is good: it pins the contract's invariants and the trim rule. `FocusVisibilityTest` and `ListFocusTest` cover the pure
  parts of the keyboard model well. `ContrastTest` is a strong idea, but only covers light (C8).
- Gaps:
  - `ControllerNavigator`: `routes` bookkeeping and `rebuild`. Extract the bookkeeping as a pure class and test it.
  - The Koin graph of `appModules`: use Koin's `verify()` or a test that resolves every `get()` a screen makes.
  - A dark-theme `ContrastTest`.
  - `formField`'s Tab and Enter mapping, which can be tested on a fake `FocusManager`.

## 5. Code quality summary

**Strengths.**
- The Compose side is small, consistent and carefully reasoned. KDoc explains why, not just what.
- The destination contract (ADR 0014) is clean. Features register themselves, `ShellGraph` validates the graph at start, and Koin
  qualifiers avoid silent overrides.
- The keyboard model is unusually thorough for this stage: keyboard-only rings, focus restored after Back, list arrow keys, the Android
  touch-mode edge case found in AOSP source and fixed. Each step was checked on a device or emulator, with results written down.
- Light-theme contrast is enforced by a test. Strings are all in resources.

**Main problems.**
1. The shipping path for desktop (MSI and DEB) was never exercised and is probably broken (C1, D6).
2. Android is the desktop layout squeezed onto a phone: no compact navigation, no insets, a fixed 44 dp row, Esc not mapped. This is known and planned for M4, but it is the biggest UX gap.
3. A few correctness holes on paths nobody has run yet: C2, C3 and C4.
4. Accessibility beyond contrast and keyboard: no headings, no live regions, dark-theme contrast failures, and desktop screen readers depend on packaging (C1).
5. Some duplication around server routes and service checks that SwiftUI and React will have to copy (D1, conventions).

## 6. Improvements (ranked)

| # | Title | Why | Modules | Effort | Priority |
|---|---|---|---|---|---|
| 1 | Package and smoke-test the desktop app in CI | C1, D5, D6: modules (`java.net.http`, `jdk.accessibility`, `jdk.crypto.ec` if JDK 21), `upgradeUuid`, icon, display name, version from `current.version`. A CI job runs `createDistributable` and starts it headless. | desktopApp, CI | M | P0 |
| 2 | Guard empty input before the local network ask | C2. A one-line guard plus a ViewModel-level guard in `beforeCheck`'s caller | composeUi (maybe feature:settings) | S | P1 |
| 3 | Dark theme contrast and a dark `ContrastTest` | C8 | core:designsystem | S | P1 |
| 4 | Back key model per platform | C5, C6: Esc closes transient UI only. Alt+Left, X1 and Back go back, even with nothing focused. Correct the Shell KDoc and architecture.md. | composeUi, shared, desktopApp | S | P1 |
| 5 | Durable navigator bookkeeping | C3: derive routes from back stack entries, or save them. Unit-test the rebuild. | composeUi | S | P1 |
| 6 | Don't steal focus from the sidebar | C4 | core:designsystem, composeUi | S | P2 |
| 7 | Screen-reader semantics | A1-A3: `heading()` on titles, polite live regions on notices and progress. Check TalkBack once and NVDA through Java Access Bridge once. | core:designsystem | S | P2 |
| 8 | Exclude machine-local data from Android backup | C7: `dataExtractionRules` and `fullBackupContent` excluding `current-local.xml` | androidApp | S | P2 |
| 9 | Compact layout and insets on Android (M4 start) | Window size classes: a navigation bar under 600 dp, a rail at 600-840, the current sidebar from 840. Apply `safeDrawing` insets. Material 48 dp items. | core:designsystem, composeUi | L | P2 (M4) |
| 10 | Server route and sign-in capability cleanup | D1: one `ServerArgs`, `saveKey` beside routes, `canSignIn` in feature states | composeUi, feature:settings, feature:home | S | P3 |
| 11 | Text-scale-safe sidebar | C9: `heightIn(min = 48.dp)`, let labels wrap, test at 200% | core:designsystem | S | P3 |
| 12 | Koin graph verification test | Catches a missing binding at test time rather than when a page opens | shared | S | P3 |
| 13 | Trim wizard leftovers | D4, D7, D8, D11 | shared, composeUi, androidApp | S | P3 |
| 14 | Windows window polish | Mica backdrop or a caption colour matching `surfaceDark`, a saved window size and position, an app icon | desktopApp | M | P3 |

## 7. Platform conventions and UX research

Sources were read on 2026-10-21. "Verified" means the page was fetched and the statement is on it. m3.material.io did not render
(JavaScript), so Material claims are taken from developer.android.com, which repeats them.

### Android and Material 3

| Guideline | Source | What the app does now |
|---|---|---|
| Window size classes: compact <600 dp, medium 600-840, expanded 840-1200, large 1200-1600, extra-large ≥1600 | https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes (verified) | One layout for every width |
| `NavigationSuiteScaffold`: a bar when width or height is compact, a rail otherwise, a drawer only when opted in (e.g. permanent at expanded) | https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation (verified) | A permanent 232 dp drawer-like sidebar everywhere. It leaves about 115 dp on a phone. |
| Rails suit 3-7 top-level destinations on tablet and desktop | https://developer.android.com/develop/ui/compose/components/navigation-rail (verified) | Two destinations today |
| Canonical layouts: list-detail (`ListDetailPaneScaffold`), supporting pane, feed (`LazyVerticalGrid` adaptive) | https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts (verified) | Settings to Addresses is a natural list-detail. Libraries (M1-M2) will be a feed. |
| Edge-to-edge is enforced on Android 15+ when targeting 35+. Use `WindowInsets.safeDrawing`. The opt-out is gone for target 36 on Android 16. | https://developer.android.com/develop/ui/compose/system/insets, https://developer.android.com/about/versions/16/behavior-changes-16 (verified) | `enableEdgeToEdge()` is called, but no insets are applied anywhere. The title and content sit under the status bar. |
| Predictive back is on by default for target 36+ on Android 16+. `KEYCODE_BACK` is no longer dispatched; use `OnBackPressedCallback`, `BackHandler` or `PredictiveBackHandler`. | https://developer.android.com/about/versions/16/behavior-changes-16, https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture (verified) | Back goes through NavHost's dispatcher, which is fine. Page-to-page predictive animation is not checked. `dispatchKeyEvent` will not see Back (unused, fine). |
| Focus: logical initial focus, predictable Tab and arrow traversal, focus restored after interruptions, a clear indicator | https://developer.android.com/develop/ui/compose/touch-input/focus (verified) | Strong match. The rings, restore-on-Back and `ListFocus` exceed most apps. |
| Large-screen keyboard: Tab and arrows, Enter, common shortcuts, Space for play/pause in media apps | https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens (verified) | Tab, arrows and Enter are done. No shortcuts yet. |
| Large-screen quality tiers (Adaptive ready, optimized, differentiated) | https://developer.android.com/docs/quality-guidelines/large-screen-app-quality (verified) | Tier 3 "ready" is roughly met on tablets (keyboard yes, mouse yes). Phones fail basic layout. |
| Esc as Back on Android | No official source found (unverified) | Not mapped (C5) |
| TV: `LEANBACK_LAUNCHER`, a 320×180 banner, `touchscreen required=false`, `leanback required=false` for a phone+TV app | https://developer.android.com/training/tv/get-started/create (verified) | None (M later) |
| TV: everything reachable by D-pad, something always focused, the remote's Back goes to the previous destination with no on-screen Back | https://developer.android.com/training/tv/get-started/navigation (verified) | The keyboard model fits. Initial focus is deliberately absent until a key press, which TV must change. The on-screen Back button must hide on TV. |
| TV overscan margin of about 5% (48 dp side, 27 dp top/bottom on 960×540) | https://developer.android.com/design/ui/tv/guides/styles/layouts (verified; the page is internally inconsistent) | — |
| Compose for TV: `androidx.tv:tv-material`, without mixing in `material3` | https://developer.android.com/training/tv/playback/compose (verified) | `core:designsystem` exposes `material3` as `api`. A TV module needs its own theme bridge. |

**Recommendations:**
- [Android] At M4, wrap the shell in a size-class switch: a navigation bar under 600 dp, a rail from 600 dp, and the current sidebar from 840 dp. Keep `ShellGraph` as the single source of entries.
- [Android] Apply `WindowInsets.safeDrawing` in `SidebarScaffold`: the sidebar takes start, top and bottom, and the page takes the rest. Scroll content under a translucent status bar only if intended.
- [Android] Make sidebar and navigation items at least 48 dp tall, and check at font scale 2.0.
- [Android] Use a dark-aware window theme (a `Theme.Material3.DayNight.NoActionBar` equivalent, or the core-splashscreen theme) instead of `@android:style/Theme.Material.Light.NoActionBar` (`AndroidManifest.xml:18`). In dark mode the window background and splash are white before Compose draws. This flash is inferred from the theme and was not observed.
- [Android] Keep Esc unmapped for page navigation unless it closes transient UI. No Android guideline asks for it.
- [Android TV] Add the TV manifest entries and give initial focus at start (TV has no pointer). Hide `BackButton`. Use `tv-material` components in a TV module.

### Windows 11 (Fluent)

| Guideline | Source | What the app does now |
|---|---|---|
| Title bar: 32 px, Mica by default and blending with the window, empty space drags, semi-transparent when inactive | https://learn.microsoft.com/en-us/windows/apps/design/basics/titlebar-design (verified) | Native frame, dark when the app is dark (DWM attribute 20). No Mica, so the caption colour (system dark grey) differs from the app's `#0E1113` / `#15191C`. |
| `DWMWA_CAPTION_COLOR` and `DWMWA_BORDER_COLOR` from build 22000, `DWMWA_SYSTEMBACKDROP_TYPE` from 22621 | https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute (verified) | Only attribute 20 is used |
| Mica: for long-lived windows, falls back to a solid colour, replaced by contrast themes | https://learn.microsoft.com/en-us/windows/apps/design/style/mica (verified) | Not used. Skia draws an opaque background, so a backdrop would need a transparent window (unverified with CMP). |
| NavigationView: Auto mode is LeftMinimal ≤640 px, LeftCompact 641-1007, expanded Left ≥1008. Content margin 24 px. | https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/navigationview (verified) | Always expanded, with a minimum window of 760×520. A compact (icons-only) rail below about 1000 px would match. |
| Back: top-left corner or title bar, disabled when there is no history | https://learn.microsoft.com/en-us/windows/apps/develop/ui/navigation/navigation-history-and-backwards-navigation (verified) | In-page Back above the title on inner pages. Hidden, not disabled, at the root, which is fine for a sidebar app. |
| Esc cancels transient UI and "does not close, or back navigate through, app UI" | https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-interactions (verified) | Esc goes back (#28). This conflicts. |
| Focus visual only for keyboard or gamepad. High-visibility style is two rectangles (2 px outer, 1 px inner) with a 1 px margin. | same page; https://learn.microsoft.com/en-us/windows/apps/design/input/guidelines-for-visualfeedback (verified) | Keyboard-only ring, 2 px with a 2 px gap: matches in spirit. A single accent stroke can vanish on accent-coloured neighbours; an inner contrasting stroke would match Fluent. |
| Accelerators: F5 or Ctrl+R refresh, Ctrl+F find, Ctrl+W close, Ctrl +/−/0 zoom, F6 cycles panes. Show them in tooltips. | https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-accelerators (verified) | None. F6 between sidebar and page would be cheap. |
| Access keys (Alt + letter) | https://learn.microsoft.com/en-us/windows/apps/design/input/access-keys (verified) | None (optional) |
| Contrast themes: don't hard-code colours, use system colour pairs, detect high contrast | https://learn.microsoft.com/en-us/windows/apps/design/accessibility/high-contrast-themes (verified) | Colours are fixed tokens and contrast themes are ignored. Not run. |
| Text scaling from 100% to 225%. Avoid fixed heights. | https://learn.microsoft.com/en-us/windows/apps/design/input/text-scaling (verified) | Whether Compose Desktop follows the Windows text size setting (not display scaling) is unverified. Fixed 44 dp sidebar rows (C9). |
| Alt+Left and the mouse back button as Back | No longer on Microsoft's current page (unverified) | Alt+Left works only while something has focus (C6). X1 is not handled. |
| Compose Desktop on Windows: accessible through Java Access Bridge, which is off by default. Packaged builds need `modules("jdk.accessibility")`. The page names JAWS and NVDA. | https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html (verified) | Not packaged (C1). Narrator is not mentioned by JetBrains (unverified). |

**Recommendations:**
- [Windows] Esc should only cancel (dialogs, later menus and the player overlay). Back should be Alt+Left, X1 and the Back button.
- [Windows] Set `DWMWA_CAPTION_COLOR` to the sidebar's surface colour, or try `DWMWA_SYSTEMBACKDROP_TYPE` with Mica, so the title bar and the app read as one surface. Both need build 22000 / 22621 guards.
- [Windows] Add F6 (sidebar ⇄ page), F5 (refresh, once pages load data) and Ctrl+, (Settings), with tooltips that name the keys.
- [Windows] Detect contrast themes (registry `HighContrast` flag or AWT `win.highContrast.on`; unverified for CMP) and switch to a high-contrast token set.
- [Windows] Ship `jdk.accessibility` and document enabling Java Access Bridge. Test with NVDA once.
- [Windows] Remember the window size, position and maximised state between runs.

### Linux (GNOME HIG, for a non-GTK app)

| Guideline | Source | What the app does now |
|---|---|---|
| Shortcuts: Ctrl+Q quit, Ctrl+W close window, F10 primary menu, Ctrl+, preferences, Ctrl+? shortcuts, F1 help, Alt+← back, Ctrl+F find | https://developer.gnome.org/hig/reference/keyboard.html (verified) | Only Alt+← (when focused) |
| Adaptive: support 1024×600, scale down to 360×294, use breakpoints with a max content width | https://developer.gnome.org/hig/guidelines/adaptive.html (verified) | Max content width yes. Minimum window 760×520, no breakpoints. |
| Light and dark: offer light, dark and follow-system in the app | https://developer.gnome.org/hig/guidelines/ui-styling.html (verified) | Follows the system only. No in-app choice. |
| `org.freedesktop.appearance` portal: `color-scheme` (0 none, 1 dark, 2 light), `accent-color`, `contrast` | https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Settings.html (verified) | Not read. CMP does not poll the system theme on Linux (platform-notes), so dark mode on GNOME is likely not detected (unverified). |
| Header bars: actions start, title centre, menus end, room to drag | https://developer.gnome.org/hig/patterns/containers/header-bars.html (verified) | The window manager draws its own frame (server-side decorations), which is acceptable for a non-GTK app |
| Accessibility checks: keyboard only, high contrast, large text, screen reader | https://developer.gnome.org/hig/guidelines/accessibility.html (verified) | Compose Desktop accessibility on Linux is "Not supported" (JetBrains page, verified). No screen reader can read the app on Linux. |

**Recommendations:**
- [Linux] Read `color-scheme` from the settings portal over D-Bus (JNA or a small D-Bus call) and feed `CurrentTheme(dark = ...)`. Fall back to the system default.
- [Linux] Add Ctrl+Q and Ctrl+W (quit and close the main window) and Ctrl+, (Settings).
- [Linux] Add a theme setting (system, light, dark) in Settings for all platforms. It also covers GNOME's guidance.
- [Linux] State in the README that screen readers are not supported on Linux until Compose Desktop gains it.

### Accessibility in Compose (Android and desktop)

| Guideline | Source | What the app does now |
|---|---|---|
| `heading()`, `stateDescription`, `mergeDescendants`, `liveRegion` (Polite in most cases), roles on custom controls | https://developer.android.com/develop/ui/compose/accessibility/semantics (verified) | Roles come from Material. No headings and no live regions (A1-A3). |
| Traversal groups and `traversalIndex` | https://developer.android.com/develop/ui/compose/accessibility/traversal (verified) | Default order is fine for the current pages |
| 48 dp touch targets (smaller is fine for precise pointers) | https://developer.android.com/develop/ui/compose/accessibility/api-defaults (verified) | Material buttons are 48 dp. The sidebar row is 44 dp (C9). |
| Font scale to 200% on Android 14+, use `sp` | https://developer.android.com/about/versions/14/features (verified) | `sp` everywhere. Fixed heights clip (C9). |
| Contrast 4.5:1 text, 3:1 large text and UI parts | https://developer.android.com/guide/topics/ui/accessibility/apps (verified) | Light passes (tested). Dark fails for outlines and for red on panels (C8). |

### Media-app conventions (for M1-M2 playback, compared with plans)

| Convention | Source | Plan |
|---|---|---|
| Android system media controls come from a Media3 `MediaSession`. The notification is automatic with `MediaSessionService`. | https://developer.android.com/media/implement/surfaces/mobile (verified) | ADR 0007 / roadmap M4 "media session". Matches. |
| PiP: `supportsPictureInPicture`, `setAutoEnterEnabled` (12+), source rect hint, only the video in PiP, hide UI while transitioning (15+) | https://developer.android.com/develop/ui/views/picture-in-picture, https://developer.android.com/develop/ui/compose/system/picture-in-picture (verified) | Planned (M4) |
| Windows SMTC: any engine other than `MediaPlayer` must drive SMTC itself. Set `DisplayProperties.Type`, which also holds off the screen saver. | https://learn.microsoft.com/en-us/windows/apps/develop/media-playback/integrate-with-systemmediatransportcontrols (verified) | libmpv's `win32-smtc` from an unpackaged JVM app is unverified (platform-notes). Treat SMTC as app work. |
| Linux MPRIS 2.2: bus name `org.mpris.MediaPlayer2.*`, `/org/mpris/MediaPlayer2` with `MediaPlayer2` and `.Player` | https://specifications.freedesktop.org/mpris/latest/ (verified) | Not planned yet. Add it to the desktop player work. |
| Space for play/pause | Android large-screen page above (verified) | Planned custom controls. F for fullscreen and arrow keys for seeking are common in most media apps, but no official source was found (unverified). |

**Recommendations:**
- [Android] Plan PiP and the media session together with the player's Compose surface. Hide controls when `isTransitioningToPip()`.
- [Windows] Budget SMTC integration in the desktop player (JNA or WinRT through a helper). Keep the screen awake during playback.
- [Linux] Add MPRIS to the desktop player milestone.
- [All Compose] Player keys: Space play/pause, Left/Right seek, F fullscreen, Esc to leave fullscreen (transient UI, so this also fits Fluent), M mute.

## Seen outside my area

- `core:network` exposes `localNetworkRequests` as a top-level singleton used by `MainActivity`, beside Koin. Consider an interface bound in Koin, with the Activity attaching through it.
- `core:database` `DeviceIdentity.load` hardcodes `"Unknown device"` (`DeviceIdentity.kt:17`), an English string outside resources. It is sent to servers, so it may be acceptable as is.
- `feature:settings` `ServerRow.address` is "the first address in priority order", not the one in use. Settings may show an address that is not the one connected. That is a design choice, and should be checked against the Addresses page's "In use".
- `feature:home` / `feature:settings`: `HomeLocalNetworkState` and `LocalNetworkPromptState` are parallel types (D2).
