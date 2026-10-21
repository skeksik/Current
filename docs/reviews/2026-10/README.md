# Full code review, October 2026

A review of everything built through M1, at `main` 6fb1957 (2026-10-21). Six reviewers each read one area. They changed no code,
and Gradle was not run, so every finding comes from reading the code. The manager then checked the most important findings against
the code (marked ✓ below) and wrote this summary.

| Section | Area |
|---|---|
| [network.md](network.md) | `core:model`, `core:network`: clients, errors, the request log, saved servers, capabilities, roaming, network signals, local network access |
| [data.md](data.md) | `core:database`, `data:jellyfin`, `core:playback`, `feature:home`, `feature:settings`, the placeholder modules |
| [compose.md](compose.md) | `core:designsystem`, `composeUi`, `shared`, `androidApp`, `desktopApp` |
| [web.md](web.md) | the `web` facade and `webApp` |
| [apple.md](apple.md) | `apple`, `appleApp`, every Apple source set, the Kotlin API as SwiftUI will see it |
| [cross-cutting.md](cross-cutting.md) | build, CI, scripts, tests in every module, ADR drift, licences, security |

Each section has seven parts:
1. the features built so far, with their ADRs, PRs and the platforms they were checked on;
2. a file map: every file, what it holds and which feature it belongs to;
3. the unverified parts of each feature;
4. findings with file:line, a failure scenario and a severity;
5. a quality summary;
6. ranked improvements sized as PRs;
7. research into each platform's guidelines and conventions, with sources.

## Overall
The code is in good shape for its stage:
- Modules talk through interfaces bound in Koin, and dependencies point down.
- Pure-Kotlin modules have no Compose, and no `CancellationException` is caught anywhere.
- The roaming loop and the sign-in flow are well tested on the JVM, and the KDoc is careful and mostly accurate.
- The web facade is thin and clean.

The weak points are elsewhere:
- **Most of the code has only run on the JVM in tests.** CI runs `jvmTest` and the web facade's Node tests. `commonTest` never runs on
  JS, iOS, macOS or tvOS, and nothing Apple-specific is tested. About half the ViewModel tests use real time.
- **Release basics are missing on every platform.** The desktop MSI/DEB has never been built, the web build has no CSP, Apple has no
  privacy manifest, the version comes from four places, and there is no licences screen.
- **A handful of real bugs in sign-in and session handling** (below), and **dark-theme contrast** fails WCAG AA on both the Compose and
  web sides.
- **The docs have drifted:** nine ADRs, CLAUDE.md's Layout, architecture.md and the roadmap lag the code. platform-notes.md is 2,348
  lines with a partly stale "Not verified" list.

## Most important findings
| # | Finding | Area | Severity |
|---|---|---|---|
| 1 | ✓ A 401 forgets whichever token is stored now (`JellyfinAccounts` `onUnauthorized` → `forget(server.ref)`), so a late 401 from an old token signs out a session that was just signed in | data | should-fix, high |
| 2 | Sign-in page: password sign-in and Quick Connect can run at once, and cancel stops the wrong job. One network blip during Quick Connect polling ends the flow | data | should-fix |
| 3 | Desktop: after one keyring failure, sign-out never removes the token from the keyring, so the next start can sign in again | data | should-fix |
| 4 | Removing an offline server waits on the network inside the page's scope: leaving the page cancels it, and the server and token are kept | data | should-fix |
| 5 | ✓ The redacted request log is bound to `NetworkLogger.None` only, so it never reaches logcat or a problem report | network | should-fix |
| 6 | Two rules disagree on what is local: the Nearby devices ask uses the stored scope, which treats Tailscale as local, while roaming exempts it. Tailscale IPv6 is not exempt at all | network | should-fix |
| 7 | ✓ Android: Enter in an empty Add server field opens the Nearby devices dialog (`check` doesn't test for blank input), and a "Don't allow" there counts toward a permanent denial | compose | should-fix |
| 8 | ✓ Web: no Content-Security-Policy, although ADR 0017 makes one the condition for keeping sessions in `localStorage`. Also missing: Referrer-Policy, favicon, manifest | web | must-fix before any deploy |
| 9 | Dark theme fails WCAG AA on both UIs: outlines 1.6-1.9:1 (needs 3:1), red Remove on panels 4.1:1. `ContrastTest` only checks light | compose, web | should-fix |
| 10 | ✓ The desktop app has never been packaged: no jlink `modules(...)` (the Ktor Java engine needs `java.net.http`, screen readers `jdk.accessibility`), no `upgradeUuid`, no icon, installed name `com.skeksik.current` | compose | must-fix before release |
| 11 | The page history isn't saved, so after rotation or process death the 50-step trim rebuilds lost pages as Home. Alt+Left works only while something has focus; the mouse back button isn't handled | compose | should-fix |
| 12 | ✓ `check-no-team-id.sh` misses Xcode's camel-case `DevelopmentTeam` attribute (public repo) | apple, cross-cutting | should-fix, cheap |
| 13 | ✓ `allowBackup="true"` with no rules backs up the machine-local device id, so a restored phone shares an id with the old one | compose | should-fix |
| 14 | Stash and services without a probe never set `reachable`, so they cannot show offline. After one missed check a server shows offline while requests to the kept address still work | network | should-fix |
| 15 | Apple isn't ready for SwiftUI: nothing starts Koin, there is no ViewModel holder, and no `@Throws`, so a `ServiceException` thrown into Swift ends the app | apple | M4 prerequisite |
| 16 | Live and keyring tests skip by `return`, so they report as passed; with `CURRENT_LIVE_*` set, `check.sh` runs them against the live server | cross-cutting | should-fix |
| 17 | CI: actions pinned by tag rather than SHA, no top-level `permissions:`, no timeouts, `cancel-in-progress` also cancels runs on `main` | cross-cutting | should-fix |
| 18 | Web bugs: Copy on Playback support throws over plain http; Escape leaves a half-typed form; two tabs never sync, so a server removed in one comes back from the other | web | should-fix |

The history scan found no private IPs, hostnames or tokens in the repository.

## Decisions for the owner
1. **Esc on desktop.** The owner decided Esc stays as it is: it goes back. Windows guidance says Esc should close transient UI (dialogs,
   menus, flyouts) and not navigate. compose.md suggests: Esc closes transient UI only, while Alt+Left, the mouse back button and system Back go back.
2. **The player UI on Apple.** ADR 0007 says the player UI is always the app's own. The HIG says to use the system video player, most
   strongly on tvOS (remote commands, PiP, skip-intro actions). Decide before the M4 player spike.
3. **Commit author on `main`.** Squash merges take the author name from the GitHub profile, so they carry the account's display name
   instead of `skeksik`. History can't be rewritten without force-pushing `main`. Setting the profile name fixes future merges.
4. **Shared test fakes.** Seven `ServerRepository` fakes keep server order differently from the real repository. One faithful fake needs
   either a new `core:testing` module or test-fixture source sets.
5. **The licences screen.** ADR 0009 requires it from the first build given to testers, but the roadmap has it in M8.
6. **SKIE** (a new dependency) for the Apple facade, when M4 starts.

## Proposed fix batch
Each item is one PR. They're ordered by value for effort; the effort figures come from the sections.

| # | PR | Covers | Modules | Effort | Check |
|---|---|---|---|---|---|
| 1 | Sign-in and session robustness | findings 1-4; unreadable desktop settings file | data:jellyfin, feature:settings, core:database | S-M | cloud tests |
| 2 | Wire the request log | finding 5: an in-memory ring buffer, logcat in Android debug builds, "copy diagnostics" | core:network, shared, web | S | cloud, then Android emulator |
| 3 | Local network rules | findings 6, 7, 14; the permission bridge race | core:network, composeUi | S | Android emulator |
| 4 | CI and repo hygiene | findings 12, 16, 17; one version source; Compose ui-tooling off the release classpath | ci.yml, scripts, build files | S | CI |
| 5 | Web hardening | findings 8, 18; sign-in title, favicon and manifest, typed problem names, error boundary | webApp, web | S-M | headless browser |
| 6 | Dark contrast and screen-reader semantics, both UIs | finding 9; dark `ContrastTest`; headings and live regions; sidebar text scaling; forced colors on the web | core:designsystem, webApp | S-M | cloud, then a look |
| 7 | Compose navigation | finding 11; back keys (after decision 1); focus not stolen from the sidebar; a Koin graph test; Android backup rules (finding 13) | composeUi, shared, androidApp | S-M | desktop keyboard, Android emulator |
| 8 | Desktop packaging | finding 10: jlink modules, upgrade UUID, icon, name; a CI job that packages and starts it | desktopApp, ci.yml | M | Windows install |
| 9 | Tests everywhere | `commonTest` on JS and `macosArm64Test` in CI; ViewModel tests on virtual time; shared fakes (after decision 4) | build files, tests, ci.yml | M | CI |
| 10 | Docs drift sweep | the stale ADR lines, CLAUDE.md Layout, architecture.md, roadmap | docs | S | none |

Later, tied to milestones:
- **Before M2's native engines:** a platform-neutral `PlaybackCapabilities` in `core:playback`.
- **Before background playback:** a rule for requests while suspended (`awaitCurrent` has no bound then).
- **M4:**
  - the Apple facade with Koin start, ViewModel holders and `@Throws`, plus SKIE;
  - Keychain and Keystore secret stores;
  - Apple's `LocalNetworkAccess`;
  - compact Android layouts with insets;
  - Darwin error codes.
- **Before testers:** the licences screen and a Gradle and Kotlin/JS licence check.
- **When convenient:**
  - split platform-notes.md by topic;
  - convention plugins in `build-logic`;
  - split `RoamingServerAddress.kt`;
  - address editing on the web.
