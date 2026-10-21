# Review: cross-cutting parts (build, CI, scripts, tests, docs, Claude setup, licences, security)

Reviewed 2026-10-21 at `origin/main` 6fb1957 (PR 39). Review only: no code was changed.
**How it was reviewed:** by reading the code. Gradle could not resolve plugins from this cloud container: two tries, with a plugin
resolution failure for `org.jetbrains.kotlin.plugin.serialization` 2.4.20. So no task ran here, and every statement about the build
comes from reading the files and from CI's configuration.
PR bodies were read with the GitHub tools. The full history (`git log --all -p`) was scanned for private-looking values.

## 1. Features in this area

| Feature | What it does | ADR | PRs | Checked on |
|---|---|---|---|---|
| Version catalog and module build files | One catalog for all versions. 20 modules: 13 pure-Kotlin (android, iOS arm64 and simulator, macOS arm64, tvOS arm64 and simulator, jvm, js), 4 Compose (android, jvm), 3 umbrellas or shells | 0002, 0003, 0011 | #1, #6, #8 | Linux and Windows CI (`jvmTest`, desktop classes, web, Android debug); macOS CI links the Apple framework through the app build |
| Generated `AppInfo` | `core:model` writes `AppInfo.VERSION` from `current.version` in gradle.properties. Servers see it in the auth header | 0017 | #18 | JVM tests only (indirectly, through the auth header test) |
| `ws` resolution for Kotlin/JS | Root `build.gradle.kts` pins `ws` to 8.21.3 in Kotlin/JS's yarn lock, because Ktor's pin has a DoS | 0011 | #16 | CI `:web:jsNodeTest`. Karma browser tests never ran (#16 "Not verified") |
| CI workflow | Three jobs. **Linux:** tests, `:web:jsNodeTest`, web build and licences, Android debug and the APK check, Secret Service keyring. **Windows:** `jvmTest` and desktop classes. **macOS:** unsigned Apple app builds | 0009, 0012, 0017 | #1, #6, #9, #16, #18, #30, #37 | GitHub-hosted runners |
| Dependency graph submission | Reports only shipped configurations to GitHub's dependency graph and Dependabot | 0009 | #16 | Runs on pushes to `main` |
| `scripts/check.sh` | Runs CI's checks locally and skips Android or Apple when the host lacks them | n/a | #1, #6, #9, #10, #30, #37 | The owner's Windows (Git Bash) and Mac, per PR bodies |
| Team ID guard (`check-no-team-id.sh`) | Fails when a team ID is committed to `project.pbxproj` or `*.xcconfig` | 0012 | #10 | CI Linux job and check.sh |
| APK Compose resources check (`check-android-apk.sh`) | Fails when the debug APK lacks a module's compiled Compose strings, which would crash the app at start | n/a | #30, #36 | CI Linux job. Qualified `values-xx` paths are inferred, not seen (#36) |
| npm licence check | Fails the web build when a production npm package has a licence that ADR 0009 doesn't allow | 0009 | #6 | CI Linux job |
| Redacted request log | Logs method, redacted path and query, status and duration per request. Never headers, bodies or hosts | 0015 | #14, #33 | JVM tests (RedactionTest, ServerClientTest) |
| Opt-in live smoke tests | `LiveJellyfinSmokeTest` runs against a real Jellyfin when `CURRENT_LIVE_*` variables are set | 0018 | #20 | The owner's machine against a live 12.2 server (#24) |
| Recorded browser probe fixtures | `docs/web-probes/*.json`, read by `BrowserProbesTest` through a system property | 0011 | #6 | Chrome 155, Edge 155, Firefox 158, Safari (macOS) recordings |
| Claude setup | **Commands:** `/check`, `/new-feature`, `/new-module`, `/update-deps`, `/add-playback-engine`, `/manage`. **Agents:** `kmp-reviewer`, `api-researcher`. **Permissions:** `settings.json` | n/a | #1, #22, #31, #39 | Used by the cloud manager and worker sessions |
| Docs | CLAUDE.md, architecture, roadmap, 20 ADRs, platform notes (2,348 lines), GitHub setup | all | every PR | n/a |

## 2. File map

### Build

| Path | Holds | Feature |
|---|---|---|
| `settings.gradle.kts` | Repositories (Google filtered by group), foojay resolver, 20 `include`s | Build |
| `build.gradle.kts` | Plugins `apply false`, the yarn resolution for `ws` | Build, `ws` pin |
| `gradle.properties` | JVM args, configuration cache and build cache on, `current.version=0.1.0` | Build, AppInfo |
| `gradle/libs.versions.toml` | Versions, libraries, plugins | Build |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.5.1 `-bin`, `distributionSha256Sum`, `validateDistributionUrl` | Build |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Wrapper | Build |
| `kotlin-js-store/yarn.lock` | Kotlin/JS tooling's npm lock (2,147 lines; `ws` 8.21.3, `@js-joda/core` 3.2.0 and others) | `ws` pin, web |
| `core/model/build.gradle.kts` | Pure-Kotlin template plus the `generateAppInfo` task | Build, AppInfo |
| `core/{network,database,playback}/build.gradle.kts`, `data/*/build.gradle.kts`, `feature/*/build.gradle.kts` | Copies of the pure-Kotlin template. `core:playback` passes `docs/web-probes` to the JVM tests | Build, probe fixtures |
| `core/designsystem/build.gradle.kts`, `composeUi/build.gradle.kts` | Compose template (android, jvm), `androidResources { enable = true }`, `packageOfResClass` | Build, APK check |
| `shared/build.gradle.kts` | Compose umbrella. Wizard leftovers: `withHostTest`, `withDeviceTestBuilder`, ui-tooling in androidMain | Build |
| `apple/build.gradle.kts` | Static `Shared` framework for 5 Apple targets; `api` and `export` of 12 modules | Build |
| `web/build.gradle.kts` | Kotlin/JS ES module library `current-core`, `.d.ts`, `nodejs()` for tests | Build, web |
| `androidApp/build.gradle.kts` | App shell: versionCode 1, versionName "1.0", minify off | Build, release |
| `desktopApp/build.gradle.kts` | Desktop shell: MSI and DEB, `packageVersion = "1.0.0"`, `packageName = "com.skeksik.current"` | Build, release |
| `androidApp/proguard-rules.pro` | Wizard default (unused while minify is off) | Release |

### CI, scripts and PR template

| Path | Holds | Feature |
|---|---|---|
| `.github/workflows/ci.yml` | Jobs `linux`, `dependency-graph`, `windows`, `macos` | CI, dependency graph |
| `.github/pull_request_template.md` | What and why / How it was verified / Not verified | CI process |
| `scripts/check.sh` | Local CI equivalent | check.sh |
| `scripts/check-no-team-id.sh` | Team ID guard | Team ID guard |
| `scripts/check-android-apk.sh` | APK Compose resources check | APK check |
| `scripts/build-apple-app.sh` | `xcodebuild` for the iOS simulator, macOS and tvOS simulator, unsigned | CI macOS |
| `webApp/scripts/check-licences.mjs` | SPDX check of `npm query .prod` | npm licence check |
| `webApp/scripts/kotlin.mjs` | Runs Gradle's `:web:jsBrowser{Development,Production}LibraryDistribution` before Vite | Web build |

### Tests (all modules; 47 files, 382 `@Test`)

| Path (module / source set / file) | Holds | Feature |
|---|---|---|
| core/model commonTest: `CapabilitiesTest`, `MediaIdTest`, `UserTest`, `OpenValuesTest`, `ArtworkTest` | Domain model rules (31 tests) | core:model (ADR 0013, 0014) |
| core/network commonTest: `RoamingServerAddressTest` (84), `RoamingAddressSourcesTest` (19) | Address choice, timeouts, local access gating, restart | Roaming (ADR 0019, 0020) |
| core/network commonTest: `LocalNetworkAccessTest`, `DefaultNetworkTrackerTest`, `AddressScopeTest`, `FakeLocalNetworkAccess.kt` (fake) | Permission state machine, Android network callback decisions | Local network (ADR 0020) |
| core/network commonTest: `ServerAddressesTest`, `ServerRepositoryTest`, `ServerUrlCandidatesTest`, `ServerUrlTest` | Saved servers and URL parsing | Server setup (ADR 0016, 0019) |
| core/network commonTest: `RedactionTest`, `CapabilitiesStateTest`, `PlatformUnreachableTest` | Redaction, capabilities state, unreachable-message matchers | ADR 0015, 0014 |
| core/network jvmTest: `ServerClientTest`, `RequestDeadlineTest`, `HttpClientFactoryTest`, `LocalNetworkRequestTest`, `PolledInterfacesTest` | Client over MockEngine and a loopback socket, deadlines, JVM interface polling | ADR 0015, 0019 |
| core/database commonTest: `DeviceIdentityTest`; jvmTest: `DesktopSecretStoreTest`, `JsonFileKeyValueStoreTest`, `KeyringTest` (env-gated) | Device id, desktop secret and settings stores, real OS keyrings | ADR 0016, 0017 |
| core/designsystem commonTest: `ContrastTest`, `FocusVisibilityTest`, `ListFocusTest` | WCAG contrast (light theme only), focus ring and list focus logic | Desktop shell |
| core/playback commonTest: `VideoRangesTest`; jvmTest: `BrowserProbesTest` | HDR and Dolby Vision ranges; probe interpretation from `docs/web-probes` | Web probes (ADR 0011) |
| data/jellyfin jvmTest: `JellyfinAccountsTest`, `JellyfinServerProbeTest`, `KoinGraphTest`, `LiveJellyfinSmokeTest` (env-gated) | Sign-in, probe, Koin graph, live smoke | ADR 0016, 0018 |
| feature/settings jvmTest: `SignInViewModelTest` (also holds `ServersViewModelTest`), `ServerSetupViewModelTest`, `ServerAddressesViewModelTest`, `LocalNetworkPromptViewModelTest`, `PlaybackSupportViewModelTest` | Settings ViewModels | ADR 0016, 0018, 0019, 0020 |
| feature/home jvmTest: `HomeViewModelTest`, `HomeLocalNetworkViewModelTest` | Home states, local network notice | Home |
| composeUi jvmTest: `ShellGraphTest` | Destination registration, sidebar order, history limit | Desktop shell |
| web jsTest: `RouteTest`, `ShellPagesTest` | Router and view mapping | Web facade (ADR 0011) |

### Docs and Claude setup

| Path | Holds | Feature |
|---|---|---|
| `CLAUDE.md` | Project instructions | Docs |
| `README.md` | Short project intro | Docs |
| `docs/architecture.md`, `docs/roadmap.md`, `docs/github-setup.md` | Architecture, milestones, GitHub plan notes | Docs |
| `docs/decisions/0001`–`0020` | ADRs | Docs |
| `docs/platform-notes.md` | Dated research and device runs, and the "Not verified" list | Docs |
| `docs/web-probes/*.json` | Recorded browser probe answers (4 files) | Probe fixtures |
| `.claude/settings.json` | Allowed and denied tool calls | Claude setup |
| `.claude/commands/*.md` (6) | Slash commands | Claude setup |
| `.claude/agents/*.md` (2) | Subagents | Claude setup |

## 3. Unverified parts, per feature

- **Build, Apple targets:**
  - Built only in CI's macOS job, as part of the app build. **No Kotlin test ever runs on iOS, macOS or tvOS** (no `*Test` task for Apple targets in CI or check.sh).
  - The `Shared` framework's Swift surface is untested beyond one type, which the placeholder reads (#9, #12, #13).
- **Build, JS targets of the pure-Kotlin modules:**
  - Their `commonTest` is never run on JS. The modules declare `js { browser() }` without `nodejs()`, and nothing runs `jsBrowserTest`.
  - Only `:web:jsNodeTest` runs.
  - Karma with `ws` 8.21.3 was never run (#16).
- **Build, Android:**
  - No unit or instrumented tests exist. `shared` declares `withHostTest` and `withDeviceTestBuilder` with no sources, and the catalog has no AndroidX test dependencies wired.
  - Release build, minify and signing were never configured or run.
- **Generated AppInfo:** the version string reaches servers only through `X-Emby-Authorization`. It was never seen on a server dashboard: the live smoke test checks sign-in, not the version.
- **CI:**
  - **Keyring:** the Secret Service `locked` and `absent` modes of `KeyringTest` never run. KWallet, Flatpak and Snap were never tried (#18).
  - **Windows:** the job runs Credential Manager for real. The MSI was never built in CI (`packageMsi` is not run).
  - **DEB:** never built.
- **check.sh:**
  - It does not run CI's Secret Service step.
  - On Windows it needs a POSIX shell (Git Bash), which CLAUDE.md doesn't mention.
- **APK check:** that a qualified `values-xx` file compiles to `values-xx/<name>.<sourceSet>.cvr` is inferred (#36). No module has qualified strings yet, so the branch never ran.
- **npm licence check:** it never sees the npm packages that the Kotlin/JS library pulls in (`kotlin-js-store/yarn.lock`). See finding B7.
- **Redaction:**
  - Covered for query parameters and URL credentials only.
  - Path segments and headers are out of scope by design. Headers are never logged.
  - **Not verified:** that every secret-bearing parameter of Seerr and Stash is in the list. Only Jellyfin `ApiKey`/`api_key`, Stash `apikey` and Quick Connect `secret`/`code` are named.
- **Live smoke tests:** only Jellyfin; Seerr and Stash have none. A skipped run reports as passed (see C3).
- **Web probes:** no recorded browser answers the Dolby Vision probes. No Dolby Vision file was ever played in a browser (#6).
- **Tests, platform actuals:**
  - Never tested on any target:
    - core:network's `androidMain`, `appleMain` and `jsMain`: PlatformEngine, PlatformUnreachable, PlatformLocalNetworkAccess, PlatformNetworkChanges, AndroidLocalNetworkAccess.
    - core:database's SharedPreferences, UserDefaults and localStorage stores, and DeviceName on iOS, macOS and tvOS.
    - core:playback's `ProbeRunner` (jsMain).
    - `web`'s `Observable` (the `useSyncExternalStore` contract).
  - The dark theme has no contrast test. `ContrastTest:12` checks `lightColors` only.
- **Release readiness, never done on any platform:**
  - signing (Android upload key, Apple distribution, Windows code signing);
  - Apple privacy manifest;
  - Play Data safety;
  - App Store privacy labels;
  - export compliance key;
  - open-source licences screen;
  - MSI upgrade code.

  Most are planned in roadmap M8; the privacy manifest and Data safety are not on the roadmap.

## 4. Code review

Severity: must-fix / should-fix / nit. Confidence: high = read in the code; medium = read, but depends on tool behaviour not run here.

### A. Build

**A1. `shared` ships Compose ui-tooling in the release Android app.**
- **Where:** `shared/build.gradle.kts:36-39` and `:73-75`. `androidMain` has `implementation(libs.compose.uiTooling)`, and `dependencies { androidRuntimeClasspath(libs.compose.uiTooling) }` adds it again.
- **What's wrong:** `androidApp` correctly uses `debugImplementation`, but the umbrella puts ui-tooling (preview and inspector code) on every Android variant's runtime classpath.
- **Scenario:** a release APK or AAB carries the tooling classes. They add size and preview-only code, and with minify off (A4) nothing strips them. The dependency graph job also reports them as shipped.
- **Severity:** should-fix. **Confidence:** high, from the build file. The release classpath was not printed here.

**A2. Unused catalog entries and wizard leftovers.**
- **Unused entries** (no `build.gradle.kts` references them; grep): `androidx-appcompat`, `androidx-core-ktx`, `androidx-espresso-core`, `androidx-testExt-junit`, `junit`, `kotlin-testJunit`.
- **Leftovers in `shared/build.gradle.kts:25-32`:** `withHostTest` and `withDeviceTestBuilder { instrumentationRunner = AndroidJUnitRunner }`, with no test sources and no runner dependency.
- **Unused plugin:** `shared` applies `kotlinSerialization` (`shared/build.gradle.kts:8`) but has no `@Serializable` class.
- **Scenario:** `/update-deps` keeps bumping dead entries, and someone reads the host and device test wiring as working.
- **Severity:** nit. **Confidence:** high.

**A3. Four version numbers, none from one source.**
- **Where:**
  - `gradle.properties:7`: `current.version=0.1.0` (sent to servers)
  - `androidApp/build.gradle.kts:31-32`: versionCode 1, versionName "1.0"
  - `desktopApp/build.gradle.kts:27`: `packageVersion = "1.0.0"`
  - `appleApp/Configuration/Config.xcconfig`: `MARKETING_VERSION=1.0`, `CURRENT_PROJECT_VERSION=1`
- **Scenario:** a server dashboard shows "Current 0.1.0", while the Android About page and the MSI show 1.0 or 1.0.0. A release bump must touch four files.
- **Apple's rule:** its docs say `CFBundleShortVersionString` is three period-separated integers, so `1.0` doesn't match the documented form.
- **Severity:** should-fix before any build is handed out. **Confidence:** high.

**A4. Android release config is the wizard's.**
- **Where:** `androidApp/build.gradle.kts:39-47`: `isMinifyEnabled = false`, no `isShrinkResources`, no signing config.
- **Also:** `AndroidManifest.xml` has `android:allowBackup="true"` and no `dataExtractionRules`. The machine-local store (device id) is therefore included in backups. The code comment and ADR 0017 already defer this to M4.
- **Scenario:** a phone restored from backup reuses the device id. Jellyfin revokes the old token when the same device id signs in, and that is exactly what `DeviceIdentity`'s KDoc warns about.
- **Severity:** should-fix, before testers get APKs. **Confidence:** high (the backup scope follows Android's default rules).

**A5. Desktop packaging is not ready for an installer.**
- **Where:** `desktopApp/build.gradle.kts:24-28`. `packageName = "com.skeksik.current"` is the user-visible app name in the installer and Start menu. There is no `windows { upgradeUuid }`, no menu or shortcut flags, no icon, no vendor.
- **Scenario:** the MSI installs as "com.skeksik.current". Without a fixed upgrade UUID, the next version's MSI may install beside the old one instead of upgrading it.
- **Severity:** should-fix (M8). **Confidence:** medium. The upgrade behaviour comes from the Compose Desktop packaging docs; it was not run here and is unverified.

**A6. The pure-Kotlin build file is copied 13 times.**
- **What's copied:** the five Apple targets, `jvm()`, `js { browser() }`, the Android block with `JvmTarget.JVM_11`, compileSdk and minSdk. Only namespaces and dependencies differ.
- **Context:** CLAUDE.md asks for copies on purpose. Gradle's best practices recommend convention plugins in a `build-logic` included build.
- **Scenario:** adding `nodejs()` to run `commonTest` on JS (see C1), or a new Apple target, needs 13 identical edits. One forgotten module silently differs.
- **Severity:** should-fix, as a separate PR with the owner's agreement, since it changes a stated convention. **Confidence:** high.

**A7. No Java toolchain; the foojay resolver is applied but unused.**
- **Where:** `settings.gradle.kts:31` applies the foojay resolver, but no module calls `jvmToolchain` or `java.toolchain`.
- **Scenario:** bytecode targets JVM 11 everywhere. The JDK that compiles, and the one that runs desktop tests, is whatever runs Gradle (21 in CI, possibly 17 or 25 locally). jpackage for the MSI uses that JDK too, so the bundled runtime depends on the developer's machine.
- **Severity:** nit, should-fix before release builds. **Confidence:** high.

**A8. Platform source files with the same name.**
- **Where:** `PlatformUnreachable.kt`, `PlatformNetworkChanges.kt` and `PlatformEngine.kt` each exist in 4 source sets; `DeviceName.kt` in 3. Only 2 files in the repo use the `.jvm.kt`/`.android.kt` suffixes that the Kotlin coding conventions recommend for platform files.
- **Scenario:** none today, since it builds. The suffix makes the target visible in editor tabs and stack traces, and avoids clashing file facades.
- **Severity:** nit. **Confidence:** high.

### B. CI and scripts

**B1. Actions are pinned by tag, and three jobs run with the default token permissions.**
- **Where:** `.github/workflows/ci.yml:18,20,24,25,56,57,61,72,73,77,84,85,89`: `@v4` and `@v6` tags. There is no top-level `permissions:`; only `dependency-graph` sets one (`contents: write`).
- **Scenario:** a moved tag on a third-party action (e.g. `gradle/actions`) runs new code in CI with whatever the repository's default token permissions are. GitHub's secure-use reference recommends full commit SHAs and a read-only token by default.
- **Severity:** should-fix. **Confidence:** high for the file. The repository's default token setting was not checked.

**B2. `cancel-in-progress` also cancels runs on `main`.**
- **Where:** `ci.yml:9-11`: group `ci-${{ github.ref }}`, `cancel-in-progress: true`.
- **Scenario:** two PRs merge a minute apart. The first `main` run, including its `dependency-graph` submission, is cancelled. `main` then has no green run for the first merge commit, and a breakage that only the first commit caused is attributed to the second.
- **Fix:** `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. Also add `github.workflow` to the group, as GitHub's docs show.
- **Severity:** should-fix. **Confidence:** high.

**B3. No `timeout-minutes`.**
- **Where:** all jobs in `ci.yml`.
- **Scenario:** a hung Gradle daemon, a keyring prompt, or a `runBlocking` test without a timeout (C2) holds a runner for GitHub's 360-minute default. On a private repo that burns paid macOS minutes (`docs/github-setup.md`).
- **Severity:** should-fix. **Confidence:** high.

**B4. Mixed `gradle/actions` majors.**
- **Where:** `ci.yml:24,77,89` use `setup-gradle@v4`, while `ci.yml:61` uses `dependency-submission@v6`.
- **Context:** current gradle/actions docs show `@v6` examples.
- **Scenario:** two different cache and wrapper-validation implementations in one workflow, and Dependabot for actions (not configured, B5) would bump them separately.
- **Severity:** nit. **Confidence:** high.

**B5. No Dependabot configuration for actions, Gradle or npm.**
- **Where:** there is no `.github/dependabot.yml`. The dependency graph job feeds alerts (`docs/platform-notes.md` "Dependency graph and Dependabot"), but nothing opens update PRs. That may be intended, since CLAUDE.md wants `/update-deps`.
- **Scenario:** a vulnerable action version goes unnoticed. Gradle and npm alerts do arrive.
- **Severity:** nit. **Confidence:** high.

**B6. The team ID guard misses Xcode's `DevelopmentTeam` attribute.**
- **Where:** `scripts/check-no-team-id.sh:13-14` matches `DEVELOPMENT_TEAM` only, case-sensitively.
- **Scenario:** when a team is picked under Signing & Capabilities, Xcode can also write `DevelopmentTeam = <id>;` under `TargetAttributes` in `project.pbxproj`. A commit with only that line passes the check and publishes the team ID.
- **Severity:** should-fix. **Confidence:** medium. Whether the current Xcode (project format 77) still writes `TargetAttributes.DevelopmentTeam` was not verified on a Mac; it is cheap to add `DevelopmentTeam` to the pattern.

**B7. The licence check covers only `webApp`'s own npm tree.**
- **Where:** `webApp/scripts/check-licences.mjs:18` runs `npm query .prod` in `webApp/`.
- **What it misses:**
  1. npm packages that Kotlin/JS resolves into `kotlin-js-store/yarn.lock` and that end up in the Vite bundle through `current-core` (`@js-joda/core` is in that lock; whether it is bundled was not checked: PR #8 flagged it as open).
  2. Every Gradle dependency shipped in the Android, desktop and Apple builds: Ktor, Koin, Coil, Compose and AndroidX (Apache-2.0); JNA (LGPL-2.1 or Apache-2.0).

  Nothing checks those licences automatically. ADR 0009 states the rule for every distributed build.
- **Scenario:** a Kotlin dependency bump pulls in an LGPL-3.0 or GPL transitive artifact, and CI stays green.
- **Severity:** should-fix. **Confidence:** high.

**B8. The SPDX parser drops parentheses, so precedence is lost.**
- **Where:** `check-licences.mjs:11-15`.
- **Scenario:** `(GPL-2.0 AND MIT) OR Apache-2.0` is a valid choice of Apache-2.0, but it is split as `GPL-2.0` AND `MIT OR Apache-2.0` and fails. `WITH` exceptions and the legacy `licenses` array fail too.
- **Impact:** it fails closed (rejects too much), so no unsafe package passes.
- **Severity:** nit. **Confidence:** high.

**B9. `check.sh` differs from CI.**
- **Where:** `scripts/check.sh`.
- **Differences:**
  - It skips the Secret Service step (`ci.yml:39-44`).
  - It always runs `npm ci`, which wipes `node_modules`.
  - With `CURRENT_LIVE_*` set in the shell, it runs the live smoke tests against the owner's server, because they are not excluded from plain `jvmTest` (C3).
- **Scenario:** check.sh passes locally, but CI's keyring step fails. Or a local check signs in to the real server and leaves a session if a test aborts.
- **Severity:** nit for the first two, should-fix for the live tests. **Confidence:** high.

**B10. `check-android-apk.sh` derives the module from the path.**
- **Where:** `scripts/check-android-apk.sh:16`, `module=${file%%/src/*}`. The script also reads `packageOfResClass` with `sed` from the module's build file (`:27`).
- **Scenario:** if the resource package is ever set through a variable or a convention plugin (A6), the script reports "sets no packageOfResClass" and fails. That is safe but brittle; worth knowing before A6.
- **Severity:** nit. **Confidence:** high.

### C. Tests

**C1. `commonTest` runs only on the JVM.** No JS, iOS, macOS or tvOS test task runs anywhere (`scripts/check.sh:10`, `ci.yml:30,78,92`).
- **Scenario:** a `commonMain` change that behaves differently on Kotlin/JS passes CI. Kotlin/JS `Long`, regex and string formatting differ, and `Redaction`'s `decodeURLQueryComponent` and URL parsing are prime suspects. The web app then breaks. The same holds for Kotlin/Native.
- **Fix:** add `nodejs()` to the pure-Kotlin template and run `jsNodeTest`; run `macosArm64Test` (or `iosSimulatorArm64Test`) in the macOS job.
- **Severity:** should-fix. **Confidence:** high.

**C2. ViewModel and repository tests use `runBlocking` and wall-clock time.**
- **Where:**
  - `SignInViewModelTest`, `ServerSetupViewModelTest`, `PlaybackSupportViewModelTest`, `HomeViewModelTest`: `runBlocking` with `withTimeout(5_000)` polling.
  - `JellyfinAccountsTest:71,83,174`: 10 ms polling and 100 ms / 2 s Quick Connect windows on the real clock.
  - `JellyfinAccountsTest:228`: a `yield()` spin.
  - `ServerClientTest:63`: a real 200 ms timeout.
  - `RequestDeadlineTest:42-99`: real deadlines, asserted `< 2 s`.
- **Context:** the roaming tests (`RoamingServerAddressTest:92-108`) show the right pattern: `runTest`, `testTimeSource` and `backgroundScope`. Some of the others (RequestDeadline, ServerClient) need real time because they test real engine threads.
- **Scenario:** on a loaded Windows runner a 100 ms Quick Connect window or the `< 2 s` bound is missed, and CI goes red with no code change. A probe that never completes (`ServerSetupViewModelTest:164`) hangs the job, since there is no overall test timeout (and no job timeout, B3).
- **Also:** these tests sit in `jvmTest` only because of `runBlocking`. Moved to `commonTest` with `runTest`, they would run on other targets once C1 is done.
- **Severity:** should-fix. **Confidence:** high for the code; the flake is a risk, not observed.

**C3. Gated tests pass instead of being skipped.**
- **Where:** `LiveJellyfinSmokeTest:53,96,109-124` and `KeyringTest:36-72` `return` early when their environment variable is missing, without `assumeTrue` or `@Ignore`.
- **Scenario:** CI reports `LiveJellyfinSmokeTest` 3/3 passed when nothing ran. The keyring step in CI works around this by grepping for a printed marker (`ci.yml:44`). The other way round: a developer with `CURRENT_LIVE_JELLYFIN_URL` in their shell runs the live tests on every `./gradlew jvmTest`.
- **Fix:** move them to a separate Gradle test task or source set, or exclude them by name from `jvmTest` unless a property is set.
- **Severity:** should-fix. **Confidence:** high.

**C4. Duplicated fakes behave differently from the real repository.**
- **Where:** `ServerRepository` is faked 7 times:
  - `ServerSetupViewModelTest:51`
  - `ServerAddressesViewModelTest:34`
  - `SignInViewModelTest:139`
  - `HomeViewModelTest:30`
  - `HomeLocalNetworkViewModelTest:27`
  - `JellyfinAccountsTest:65`
  - `LiveJellyfinSmokeTest:58`
- **What differs:** `StoredServerRepository.save` keeps a replaced server in place and announces it to the capabilities state. Most fakes move it to the end (`filterNot + server`), and only one mirrors capabilities.
- **More copies:** the `LocalNetworkAccess` fake is copied verbatim between `LocalNetworkPromptViewModelTest:19-44` and `HomeLocalNetworkViewModelTest:44-69`. `FakeLocalNetworkAccess` is `internal` to core:network's commonTest, so other modules cannot reuse it.
- **Scenario:** a ViewModel test passes against an order the real repository never produces. A change that relies on order then breaks in the app only.
- **Fix:** use `StoredServerRepository(InMemoryKeyValueStore(), …)`, as `RoamingAddressSourcesTest:48` does, or publish test fixtures from core:network.
- **Severity:** should-fix. **Confidence:** high.

**C5. Weak or misleading assertions.**
- `SignInViewModelTest:102`: `assertTrue(!(…).working)` is checked synchronously right after `signIn()`, so it would pass even if `working` became true a moment later.
- `ServerRepositoryTest:56`: `assertTrue(store.get("servers") == "[]")` should be `assertEquals`.
- `ServerSetupViewModelTest:101`: named "aHangingHttpsCandidate…", but its fake fails at once.
- `ServerSetupViewModelTest:164-171`: never checks that the probe was cancelled.
- **Severity:** nit. **Confidence:** high.

**C6. Test scopes are not cleaned up.**
- **Where:**
  - `LocalNetworkPromptViewModelTest:47` and `HomeLocalNetworkViewModelTest:74` create `CoroutineScope(Dispatchers.Unconfined)` and never cancel it.
  - `SignInViewModelTest.kt:153,165` (`ServersViewModelTest`) uses a parentless `SupervisorJob` and cancels it outside `finally`.
  - `ShellGraphTest:31,38` never closes its `koinApplication`.
- **Scenario:** a failing assertion leaks a running collector into later tests in the same JVM fork, and later failures then point at the wrong test.
- **Severity:** nit. **Confidence:** high.

**C7. `ServerClientTest:214-222` uses a closed port.** It closes a `ServerSocket(0)`, then expects a connection to that port to be refused.
- **Scenario:** on a busy CI host another process binds the port in between, and the "unreachable" test gets a connection. That is rare.
- **Severity:** nit. **Confidence:** high.

**C8. No CancellationException problems were found.** Two broad catches in main code, `ServerClient.kt:33-35` and `ServerAddressSources.kt:131`, rethrow it. That matches the intent of CLAUDE.md's rule, though not its letter: `catch (e: CancellationException) { throw e }` followed by `catch (e: Throwable)`. `Redaction.kt:48`'s `runCatching` wraps non-suspending code. No finding, recorded for completeness.

### D. Docs and Claude setup

**D1. `.claude/settings.json` is narrow and partly stale.**
- **Allow-list:**
  - It allows `./scripts/check.sh` but not `:web:jsNodeTest`, `npm --prefix webApp …` or `./gradlew :desktopApp:run`, which CLAUDE.md lists as everyday commands.
  - `Bash(./gradlew :apple:linkDebugFrameworkIosSimulatorArm64:*)` is allowed, but its macOS and tvOS siblings are not.
- **Deny-list:**
  - `Bash(rm -rf:*)` is bypassed by `rm -fr` or `rm -r -f`.
  - `git push origin main` is bypassed by `git push` on a branch tracking main, or by `git push origin HEAD:refs/heads/main`.
  - `appleApp/Configuration/Local.xcconfig` (team ID) is not in the `Read` deny list, unlike `.env` and keystores.
- **Scenario:** the deny-list is read as a guarantee in `docs/github-setup.md` ("enforced … by `.claude/settings.json`"), but it isn't one. Only the GitHub ruleset protects `main`.
- **Severity:** nit (the ruleset is the real guard). **Confidence:** high.

**D2. Command and agent docs mention stale or absent things.**
- `/new-feature` step 3 asks for "a ViewModel unit test in `commonTest`", but every ViewModel test is in `jvmTest` (C2).
- `kmp-reviewer` cites ADR 0005/0006 for "no Compose in pure modules", which matches. But it doesn't check the `web` umbrella (ADR 0011) or the js target.
- `/add-playback-engine` refers to `EngineCapabilities` and `PlaybackPlanner`, which do not exist yet (expected, M2).
- **Severity:** nit. **Confidence:** high.

**D3. docs/platform-notes.md is hard to navigate.**
- **Size:** 2,348 lines, 37,156 words, 58 top-level sections, no table of contents.
- **Order:** sections are in roughly chronological order, not by topic. Roaming alone has 11 sections ("Roaming on iOS", "…, final", "…, suspension fix", the same three for Android, plus emulator, browsers, browsers final and headless).
- **The "Not verified" list** is the last section (line 2247), so the one list workers must check before relying on a fact is the hardest to find.
- **Scenario:** a worker checking whether tvOS ATS was verified has to search 2,300 lines. Superseded sections (the non-final roaming runs) can be read as current.
- **Fix:** split by topic into `docs/platform-notes/` (Apple, Android, desktop, web, servers, roaming, playback). Keep a short index with the "Not verified" list at the top, and mark superseded runs.
- **Severity:** should-fix. **Confidence:** high.

**D4. ADR and doc drift:** see the separate list below this section.

### E. Security and privacy

**E1. Commit author names in public history.** 38 squash-merge commits on `main` carry the GitHub account's display name as author, not `skeksik` as CLAUDE.md requires; GitHub sets the author of a squash merge from the merging account's profile. Only the two earliest commits use `skeksik`. The value is not repeated here.
- **Scenario:** the repo is public, and CLAUDE.md's author rule exists so that the history shows only the handle.
- **Fix:** set the GitHub profile name, or have the owner merge with a commit whose author is set explicitly. Rewriting `main` is not proposed (never force-push).
- **Severity:** should-fix (owner decision). **Confidence:** high.

**E2. The rest of the history scan found nothing private.**
- **IPv4 literals:** the history (37,223 lines of patches) holds only these ranges:
  - documentation addresses: 203.0.113.x
  - private ranges: 192.168.x, 10.x, 172.16–31.x
  - CGNAT test values: 100.64.0.0/10
  - loopback, link-local, broadcast and SSDP multicast
  - boundary test values (192.169.1.1, 172.32.0.1, 100.128.0.1)
  - version strings (an emulator build, a Chrome user agent)
- **Host names:** only example and placeholder names (`media.example.com`, `media.lan`, `nas`, `media.tailnet-name.ts.net`, `*.invalid`) and public documentation sites.
- **Token-like strings:** only lockfile integrity hashes, Xcode object ids and an obvious test value (`0123456789abcdef…`).
- **Key patterns:** no GitHub, Anthropic or AWS key patterns and no private key blocks.
- **Secrets handling as built:** logs carry the class name only (`NetworkModule.kt:27`), and request log entries hold no host or headers.

**E3. Redaction list gaps (not verified).** `Redaction.secretParameters` (`Redaction.kt:18`) covers `apikey`, `api_key`, `token`, `access_token`, `accesstoken`, `secret`, `password`, `pw` and `code`.
- **Missing generic names:** `key`, `auth`, `signature`/`sig`, `session`, `jwt`. A future Seerr or Stash URL could carry them, but no current request does.
- **Severity:** nit; revisit when `data:seerr` and `data:stash` are written. **Confidence:** medium.

**E4. Android cleartext is allowed for every host.**
- **Where:** `network_security_config.xml` sets `base-config cleartextTrafficPermitted="true"`. The app enforces its own rule instead (ADR 0016 and 0020: plain HTTP to a local address, or to the internet only after a confirmed warning).
- **Scenario:** any library that makes its own requests outside `ServerClient` gets cleartext without the app's warning (Coil today, Media3 later). PR #27 lists "whether Media3 and Coil honour the cleartext setting" as not verified. Android's docs prefer `domain-config` over a global opt-in, but typed addresses rule that out, so this is a deliberate trade-off.
- **Severity:** nit (documented decision). **Confidence:** high.

### D4. ADRs and docs whose text no longer matches the code

Every ADR was checked against the code (grep and read). **Overview:**
- **Match:** 0004, 0005, 0012, 0013, 0014, 0015, 0016, 0017.
- **Superseded and correctly marked:** 0001, and the editions part of 0009.
- **Planned, not built:** 0007 and 0010, plus the web engine part of 0011.

| ADR / doc | Text that no longer matches | Code | Severity |
|---|---|---|---|
| 0002 Architecture and libraries | :6 promises a common `LoadState<T>`; :7 says "Koin for … ViewModels" | No `LoadState` exists; each ViewModel has its own sealed UiState (`HomeViewModel.kt:18`, `SignInViewModel.kt:20`). ViewModels are built in the page from Koin-bound interfaces, not bound themselves (`HomeDestination.kt:68`, `web/.../CurrentWeb.kt:68-80`; #24 says so) | should-fix (update the ADR) |
| 0003 Module structure | :6 names the shell `iosApp`; :9 says wasmJs | `appleApp/` (ADR 0012); every pure module has `js {}`, none has `wasmJs` | nit |
| 0008 Optional services | :31 "`data:stash` … only the features that need Stash pull it in" | All three umbrellas include it unconditionally (`shared/build.gradle.kts:49`, `apple/build.gradle.kts:40`, `web/build.gradle.kts:30`). It doesn't matter under 0014, but the line is not marked superseded | nit |
| 0009 Licences | :34-36 still describe a removable "adult extension" | Extensions were dropped by 0014; the status line marks only the editions as superseded | nit |
| 0011 Web UI | :238 "Dolby Vision is not probed yet" | Probes exist (`core/playback/.../web/BrowserProbes.kt:93-94`) | nit |
| 0015 Server clients | :15 "choosing comes with server setup" | Address choosing came with 0019 | nit |
| 0018 Jellyfin sign-in | :16 "the first address until roaming"; :33 the web flow comes "once the React shell has screens" | Roaming is bound (`NetworkModule.kt:40-42`); the web sign-in shipped in #37 | nit |
| 0019 Roaming | :138-140 the network security config and `ACCESS_LOCAL_NETWORK` "come with the shell" | Done in #27 (ADR 0020; `AndroidManifest.xml:5-9,15`) | nit |
| 0020 Android cleartext | :175-177 says its citations live in "two open PRs" that must merge | Both merged (#23, #25); the cited sections exist (`platform-notes.md:1295,1659`) | nit |
| CLAUDE.md Layout | `composeUi` "player screen + controls, video surfaces"; `core/database` "cache"; `core/playback` "PlayerEngine interface, capabilities, playback planner" | None exist. `core:database` holds settings, secrets and device identity; `core:playback` holds probes, `DeviceProfile` and `VideoRanges`. Roaming and `LocalNetworkAccess` are not named under `core/network` | should-fix: mark these as planned |
| CLAUDE.md Stack and Commands | "Added" leaves out JNA and lifecycle-viewmodel; `./gradlew jvmTest` is described as "JVM tests in every module" | `web` has no JVM target; its tests run with `:web:jsNodeTest` (check.sh and CI run it, but the command list doesn't name it) | nit |
| docs/architecture.md | No mention of ADR 0020 or `LocalNetworkAccess`; the `feature:home` row lacks `HomeLocalNetworkViewModel`; no `feature:settings` row (6 ViewModels); :92 SwiftUI reads state "through SKIE" | SKIE not added | should-fix |
| docs/roadmap.md | The Android network setup and local network permission (#27, ADR 0020), Android polish (#36) and the Apple device name work (#34) appear nowhere; the M1 "Done when" (:83) has no state; :66 typo "below. password" | — | nit |
| docs/platform-notes.md "Not verified" | :2348 "web, Android and Apple builds not verified yet" | CI builds all three; roadmap M0 :19-22 ticks them | should-fix |
| same | :2250-2251 engine errors "pass through unmapped"; :2326-2327 `NWPathMonitor` at start and Wi-Fi to Wi-Fi | JS "Fail to fetch" is mapped (`jsMain/.../PlatformUnreachable.kt`); `NWPathMonitor` was seen on macOS (:1439) and iOS (:1512, :1551, :1577). Only tvOS and moves between access points remain open | nit |
| same | :2282-2283 screen readers in Compose for web; the :2309 heading "no device, no LAN, no live server" | Compose for web was dropped (ADR 0011); later device runs exist | nit |

## 5. Code quality summary

**Strengths:**
- **Supply chain:**
  - The Gradle wrapper pins `distributionSha256Sum` and validates the URL.
  - Repositories live in settings, with Google filtered by group.
  - Configuration cache and build cache are on.
  - The version catalog is the only place versions live.
- **Clever CI guards:**
  - The APK check catches a crash-at-start class of bug.
  - The team ID guard never prints the value.
  - The keyring step really exercises the Secret Service.
  - Dependency submission is limited to shipped code.
- **Secrets discipline is good:**
  - Logs never carry hosts, headers, bodies or exception messages.
  - The redaction rules fail towards hiding.
  - The history scan found nothing private apart from author names (E1).
- **Tests:** 382 tests with no mocking library, and hand-written fakes throughout. The roaming tests are a model of virtual-time testing. No test or main code swallows `CancellationException`.
- **Documentation is unusually thorough:**
  - Every PR records what was not verified.
  - ADRs are dated, and superseded ones say so.
  - The platform notes cite primary sources with dates.

**Main problems:**
1. **Tests run on one platform.** `commonTest` runs only on the JVM; no JS, iOS, macOS, tvOS or Android unit test runs anywhere. For a project whose core targets six platforms, that is the largest verification gap (C1).
2. **Inconsistent test timing.** Half the tests use virtual time, half use `runBlocking` and the wall clock (C2). Gated tests report as passed (C3). The duplicated fakes drift from the real repository (C4).
3. **Release readiness is absent.** Version numbers are not unified (A3), release ui-tooling is shipped (A1), there is no minify or signing (A4), the installer metadata is missing (A5), and there is no privacy manifest, Data safety or licences screen. ADR 0009 says third-party licences apply "from the first build given to anyone else (testers included)", but the licences screen is only scheduled for M8.
4. **CI hardening:** actions are not SHA-pinned, the default token permissions apply, there are no timeouts, and `main` runs get cancelled (B1–B3).
5. **Docs drift and findability:** small drift across a dozen docs and ADRs (D4), and a 2,348-line platform notes file with the "Not verified" list at the bottom (D3).

## 6. Improvements (ranked)

| # | Title | Why | Modules / files touched | Effort | Priority |
|---|---|---|---|---|---|
| 1 | Run `commonTest` on JS and on one Apple target in CI | C1: core logic ships to the web and Apple untested there | pure-Kotlin `build.gradle.kts` (`nodejs()`), `ci.yml` (macOS job: `macosArm64Test`), `check.sh` | M | high |
| 2 | Harden the workflow | B1–B3 | `ci.yml`: top-level `permissions: contents: read`; SHA-pinned actions with version comments; `timeout-minutes` per job; cancel only PR runs; one `gradle/actions` major | S | high |
| 3 | Gate live and keyring tests properly | C3, B9: no silent passes, no accidental live runs | `data/jellyfin` and `core/database` build files (separate test task or exclude unless `-Pcurrent.live`), the tests, `ci.yml:43-44` (drop the grep) | S | high |
| 4 | Move ViewModel tests to `runTest` and virtual time | C2: flake risk, hang risk, and they then fit `commonTest` | `feature/settings`, `feature/home`, `data/jellyfin` tests | M | high |
| 5 | Shared test fixtures | C4: one faithful `ServerRepository` and `LocalNetworkAccess` fake | core:network (a `testFixtures`-style source set, or test-only public fakes in a `core:testing` module; owner's call, since it adds a module) | M | medium |
| 6 | Licence check for Gradle and Kotlin/JS dependencies, and a plan for the licences screen before testers | B7, ADR 0009 | a Gradle licence report task (needs approval if it is a new plugin), `check-licences.mjs` extended to `kotlin-js-store`, roadmap: move the licences screen before the first tester build | M | medium |
| 7 | One version source | A3 | `gradle.properties` feeding `androidApp` (versionName and versionCode), `desktopApp` (`packageVersion`) and an xcconfig generated or checked by a script | S | medium |
| 8 | Remove wizard leftovers | A1, A2: release ui-tooling, dead catalog entries, dead host and device test wiring | `shared/build.gradle.kts`, `libs.versions.toml` | S | medium |
| 9 | Restructure the platform notes | D3: findability; a stale "Not verified" list | `docs/platform-notes.md` → `docs/platform-notes/*.md` plus an index with the "Not verified" list first; fix the stale items in D4 | M | medium |
| 10 | Docs drift sweep | D4 | the ADRs listed, CLAUDE.md Layout/Stack/Commands, architecture.md, roadmap.md | S | medium |
| 11 | Team ID guard: also match `DevelopmentTeam` | B6 | `scripts/check-no-team-id.sh` | S | medium |
| 12 | Convention plugins in `build-logic` | A6: 13 copies of one block; prerequisite for #1 being a one-line change | new `build-logic/`, every module build file, CLAUDE.md's "copy the shape" rule and `/new-module` | L | low (needs the owner's agreement) |
| 13 | Release readiness items on the roadmap | Section 7: privacy manifest, Data safety, export compliance key, MSI upgrade UUID, `allowBackup` rules, Java toolchain | `docs/roadmap.md` M8 (and M4 for backup rules) | S | low |
| 14 | Tidy `.claude/settings.json` | D1: allow everyday commands; deny reading `Local.xcconfig`; don't present the deny-list as a guard | `.claude/settings.json`, `docs/github-setup.md` | S | low |

## 7. Platform conventions and research

All sources were read on 2026-10-21. Apple pages were read through their JSON documentation endpoints.

### Kotlin and KMP

**Sources:**
- Kotlin coding conventions: https://kotlinlang.org/docs/coding-conventions.html
- expect/actual: https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
- Hierarchy: https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Recommended structure: https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html

| Guidance | The app now | Recommendation |
|---|---|---|
| Platform files carry the source set as a suffix (`Platform.jvm.kt`) | Same-named files in 3–4 source sets (A8) | [all] Rename to `*.android.kt`, `*.apple.kt`, `*.js.kt`, `*.jvm.kt` when the files are next touched |
| Prefer interfaces plus factories over expect/actual; expect/actual classes are Beta | Interfaces bound in Koin, with small `actual fun`s (`platformKeyValueStore()`) | [all] Matches; keep it |
| The default hierarchy template gives `appleMain`, `iosMain` and others; manual `dependsOn` disables it | `appleMain`, `iosMain`, `macosMain` and `tvosMain` come from the template | [all] Matches |
| With a native SwiftUI app, split shared logic from shared UI so Apple doesn't pull in Compose; Android entry point in its own module (AGP 9) | `apple` exports only pure modules; `androidApp` is separate; libraries use `com.android.kotlin.multiplatform.library` | [all] Matches |
| Libraries: explicit visibility, return types and KDoc on public API | Explicit API mode is not on; KDoc coverage is good in core | [all] Optional: `explicitApi()` in `core:*` would make the `Shared` and `current-core` surfaces deliberate |

### Gradle

**Sources:**
- Best practices index: https://docs.gradle.org/current/userguide/best_practices_index.html
- Structuring builds: https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html
- Performance: https://docs.gradle.org/current/userguide/best_practices_performance.html
- Security: https://docs.gradle.org/current/userguide/best_practices_security.html

| Guidance | The app now | Recommendation |
|---|---|---|
| Version catalogs; repositories in settings | Yes | [all] Matches |
| Configuration cache, build cache, `-Dfile.encoding=UTF-8`, `-bin` distribution | Yes (`gradle.properties:10-12`, wrapper) | [all] Matches |
| `distributionSha256Sum`; treat wrapper changes as security-sensitive | Yes; setup-gradle validates wrappers by default | [all] Matches |
| Share build logic with convention plugins in a `build-logic` included build, not by copying, `allprojects` or `subprojects` | Copies by rule (A6) | [all] Improvement #12 |
| Toolchains for reproducible builds | None (A7) | [desktop, Android] Declare a toolchain before release builds, so jpackage's runtime is fixed |
| Type-safe project accessors | Not used; they are still incubating | [all] Leave as is |

### GitHub Actions

**Sources:**
- Secure use: https://docs.github.com/en/actions/reference/security/secure-use
- Concurrency: https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency
- Workflow syntax: https://docs.github.com/actions/reference/workflow-syntax-for-github-actions
- setup-gradle: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md

| Guidance | The app now | Recommendation |
|---|---|---|
| Pin actions to a full commit SHA. GitHub now also documents workflow dependency lockfiles (public preview) as stronger than SHA pins | Tags (B1) | [CI] SHA pins with `# vX.Y.Z` comments; consider the lockfile once it leaves preview |
| Least-privilege `GITHUB_TOKEN`: read-only by default, raised per job | Only `dependency-graph` sets permissions | [CI] Top-level `permissions: contents: read` |
| Concurrency group with `github.workflow`; cancel superseded runs | Cancels on `main` too (B2) | [CI] Cancel PR runs only |
| Job `timeout-minutes` (default 360) | None (B3) | [CI] 30–45 min Linux and Windows, 60 min macOS |
| setup-gradle: only the default branch writes the cache; don't combine with setup-java's `cache: gradle`. The default "enhanced" cache provider is proprietary (free for public repos); `cache-provider: basic` is MIT | Default provider; no setup-java cache, which is correct | [CI] If the repo goes private, check the enhanced cache's terms or switch to `basic` |
| Avoid `pull_request_target`; don't inline untrusted `${{ }}` in scripts | Neither is used | [CI] Matches |
| Dependabot for `github-actions` | None (B5) | [CI] Optional weekly Dependabot for actions only. Unverified: whether it updates SHA pins together with their comments |

### Android release readiness

**Sources:**
- Target SDK requirement: https://developer.android.com/google/play/requirements/target-sdk
- App signing: https://developer.android.com/studio/publish/app-signing
- Versioning: https://developer.android.com/studio/publish/versioning
- R8: https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- Network security config: https://developer.android.com/privacy-and-security/security-config
- Local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469

| Guidance | The app now | Recommendation |
|---|---|---|
| New apps and updates must target API 36 from 2026-08-31 | targetSdk 37 | [Android] Matches |
| Play App Signing; a separate upload key; key material and passwords outside build files and source control | No signing config; `*.jks` and `*.keystore` are gitignored | [Android] At M8, read signing from a gitignored `keystore.properties` or CI secrets |
| `versionCode` increases with every release; `versionName` is user-visible | Fixed 1 / "1.0" (A3) | [Android] Derive both from `current.version` |
| R8: `isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt` (AGP 9.3+ has an `optimization {}` block) | Minify off (A4) | [Android] Turn R8 on with keep rules for kotlinx.serialization and Ktor before the first tester APK. AGP 9.1.1 is in use, so the older DSL applies |
| Cleartext: prefer `domain-config`; from API 37 an implicit config allows cleartext to loopback only | Global `base-config` opt-in (E4), justified by typed addresses | [Android] Keep it, but route every network client (Coil, the future Media3) through the app's own HTTP rule, or verify that they respect it (PR #27's open item) |
| Android 17 (target 37): LAN traffic needs the runtime `ACCESS_LOCAL_NETWORK` or a system picker | Declared and requested at runtime (ADR 0020) | [Android] Matches. Nothing asks for an already saved local server (#38's note); add the Home entry point |
| Data safety form is required even for apps that collect nothing, plus a privacy policy link and deletion instructions | None | [Android] Add to M8. Unverified: how Play classifies traffic to the user's own self-hosted server |
| Backups: by default the app's data is backed up | `allowBackup="true"`, no rules (A4) | [Android] `dataExtractionRules` and `fullBackupContent` excluding `current-local` (device id) and future secrets. ADR 0017 plans this for M4; do it before testers |

### Apple release readiness

**Sources:**
- Privacy manifest files: https://developer.apple.com/documentation/bundleresources/privacy-manifest-files
- Required reason APIs: https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Kotlin privacy manifest: https://kotlinlang.org/docs/multiplatform/multiplatform-privacy-manifest.html
- App privacy details: https://developer.apple.com/app-store/app-privacy-details/
- TN3179, local network privacy: https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- `NSAllowsLocalNetworking`: https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking
- `CFBundleShortVersionString`: https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring
- Export compliance: https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations

| Guidance | The app now | Recommendation |
|---|---|---|
| `PrivacyInfo.xcprivacy` declares collected data types (all platforms) and required-reason APIs (iOS, iPadOS, tvOS; not macOS). UserDefaults access needs reason CA92.1 | No privacy manifest in `appleApp/`. `UserDefaultsKeyValueStore` uses UserDefaults, a required-reason API | [iOS, tvOS] Add `PrivacyInfo.xcprivacy` with `NSPrivacyAccessedAPICategoryUserDefaults` / CA92.1, no tracking, and the collected data types (likely none). Run Kotlin's `required_reason_finder.py` on the `Shared` framework. Unverified: whether Ktor or the Kotlin runtime call other required-reason APIs |
| Privacy labels in App Store Connect cover third-party code; "collect" means retained beyond the real-time request | None | [Apple] Add to M8. Unverified: how data sent to a user-configured server counts |
| Local network: `NSLocalNetworkUsageDescription` (plus `NSBonjourServices` if browsing); the first request may fail before the prompt is answered; the Simulator doesn't show the prompt; **tvOS has no local network privacy** | Usage description set (`appleApp/Current/Info.plist`); no Bonjour browsing yet | [iOS, macOS] Matches. When discovery lands, add `NSBonjourServices` and retry the first local request after the prompt. tvOS needs no prompt |
| ATS: `NSAllowsLocalNetworking` allows IP literals and `.local` / unqualified names; IP connections are blocked by default since iOS 17 and macOS 14 | Set | [Apple] Matches. Plain http to an internet address still fails ATS; ADR 0016's warning flow must handle that on Apple |
| `CFBundleShortVersionString`: three period-separated integers; `CFBundleVersion` increases for each upload | `MARKETING_VERSION=1.0`, `CURRENT_PROJECT_VERSION=1` (A3) | [Apple] Use `0.1.0` from the single version source |
| Export compliance: `ITSAppUsesNonExemptEncryption = NO` when only exempt (OS-provided) encryption is used, or App Store Connect asks on every upload | Not set | [Apple] Add `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` once confirmed (only the system's HTTPS is used) |
| Signing | Team ID kept out of the repo through `Local.xcconfig` and the guard (B6) | [Apple] Matches the public-repo rule. Distribution signing was never done (#9). Unverified: no official signing page was read for this review |

### Open-source licence attribution

**Sources:**
- Apache-2.0 §4: https://www.apache.org/licenses/LICENSE-2.0
- MIT: https://opensource.org/license/mit
- Google OSS licences: https://developers.google.com/android/guides/opensource

**What the licences require:**
- **Apache-2.0** (Ktor, Koin, Coil, Compose, AndroidX, kotlinx) asks recipients to get a copy of the licence and any upstream NOTICE text, which can be "a display generated by the Derivative Works". That is the licences screen.
- **MIT** (React, among others) asks that the copyright and permission notice go with all copies, the web bundle included.
- **Google's plugin** builds a licence list from POM `<licenses>` entries, but only for Android, and Google says the developer stays responsible for showing the notices.

**Recommendations:**
- **[all]** Generate one licence list at build time for each shipped artifact set: Gradle dependencies per app, plus webApp's and Kotlin/JS's npm packages. Show it on an About → Open-source licences page in Compose, React and SwiftUI. Ship a `THIRD-PARTY-NOTICES` file in the MSI, the DEB and the web build.
- **[all]** ADR 0009 applies this from the first build given to a tester, so move the licences screen out of M8 or don't hand out builds before it exists.
- **[Android]** If Google's oss-licenses plugin is considered, it is a new dependency (needs approval) and covers Android only. Unverified: its AGP 9 compatibility.

## Seen outside my area
- `core/network/.../ServerClient.kt:33-35` uses `catch (e: CancellationException) { throw e }` followed by `catch (e: Throwable)`. Safe, but it doesn't follow CLAUDE.md's wording ("do not catch"); `ServerAddressSources.kt:131` is similar.
- `feature/settings/.../ServerSetupViewModel.kt:84` takes the concrete `MutableCapabilitiesState`, while CLAUDE.md says "as interfaces".
- `core/designsystem` has no contrast test for `darkColors` (`ContrastTest.kt:12` checks light only).
- `core/designsystem/.../Tokens.kt` is Compose-only, and `webApp/src/styles.css` copies the values by hand (ADR 0006 :20; deferred to M4).
- `webApp` has no tests and no test script (`webApp/package.json`).
- #38's note: nothing asks for local network access for an already saved local server (`shouldAskForLocalNetwork` with `SavedServers` is called only in tests).
