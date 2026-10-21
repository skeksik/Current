# Code review 2026-10: Apple

Area: the `apple` umbrella framework, the `appleApp` Xcode project, `scripts/build-apple-app.sh` and `scripts/check-no-team-id.sh`, every
`appleMain`/`iosMain`/`macosMain`/`tvosMain` source set, and the Kotlin API as SwiftUI will see it. Reviewed on 2026-10-21 at `origin/main`
6fb1957, by reading only: there is no Mac here, so nothing Apple was built or run for this review.

## 1. Features in this area

| Feature | What it does | ADR | PRs | Checked on (docs/platform-notes.md, PR bodies) |
|---|---|---|---|---|
| `Shared` umbrella framework | Static framework `Shared` that exports `core:*`, `data:*` and `feature:*` (no Compose) for the SwiftUI app. Keeps one source file so the link task is not NO-SOURCE. | 0006, 0012 | initial commit, #8 (libraries), #9 (made it link) | Linked for iOS simulator, macOS and tvOS simulator by Xcode 27.1 and by CI (Xcode 26.6). `iosArm64` linked once, for the owner's iPhone (#9). `tvosArm64` never linked. |
| One SwiftUI app target | `appleApp/Current.xcodeproj`, one target for iOS, macOS and tvOS, minimum OS 26, bundle ID `com.skeksik.current`. Its first build phase runs `:apple:embedAndSignAppleFrameworkForXcode`. Today a placeholder view reads `VideoRanges` from the framework to prove the link. | 0012 | #9 | Launched on an iPhone 17 Pro simulator (iOS 26.5), an Apple TV 4K simulator (tvOS 27.0), macOS 26.6.2 (process only, window not looked at), and an iPhone 15 Pro Max (iOS 27.0.1, development-signed). |
| Signing kept out of the repo | Empty `TEAM_ID` in `Config.xcconfig`, optional gitignored `Local.xcconfig`, `DEVELOPMENT_TEAM = "${TEAM_ID}"`. `check-no-team-id.sh` fails CI when a team ID is committed. | 0012 | #9, #10 | Script run on main and against a planted team (#10). |
| CI build of the app | `build-apple-app.sh` builds Debug, unsigned, by SDK (`iphonesimulator`, `macosx`, `appletvsimulator`). Runs in `check.sh` on macOS and in CI's "Apple apps (macOS)" job. | 0012 | #9 | CI on every PR. |
| Info.plist local network keys | `NSLocalNetworkUsageDescription` and ATS `NSAllowsLocalNetworking`, shared by all three platforms. | 0019, 0020 (Android counterpart) | #34 | Keys read from Apple's docs only. The same keys were put in a throwaway harness for the iOS roaming runs, not through this plist (#34: "Run manually on: nothing"). |
| Settings store (`UserDefaultsKeyValueStore`) | `KeyValueStore` on `NSUserDefaults` with the prefix `current.`. Holds saved servers and settings. | 0016 | #17 | Compiles in CI. Used at runtime only by the throwaway iOS roaming harness (Koin with `databaseModule`). No test has run on Native. |
| Machine-local store | The same store with the prefix `current.local.`. Holds the device id. Keeping it out of backups is planned for M4. | 0017 | #18 | As above. |
| Secret store | `InMemorySecretStore` until the Keychain (M4): tokens are lost when the app quits. | 0017 | #18 | Compiles only (#18 "Not verified: Android, Apple and web stores at runtime"). |
| Device name | iOS/tvOS: `UIDevice.currentDevice.name`. macOS: `SCDynamicStoreCopyComputerName`, or "Mac". Replaced `NSProcessInfo.hostName`, which raised the Local Network prompt and got the first launch killed by the watchdog. | 0017 | #18, #34 | Docs only. #34: no Apple run, because nothing starts Koin on Apple yet. |
| HTTP engine | Ktor Darwin engine (`NSURLSession`). | 0002, 0015 | #8, #17 | Exercised by the macOS test binary and the iOS harness during the roaming runs. |
| Unreachable mapping | `isPlatformUnreachable = false`: on Darwin only `IOException`s count as unreachable. | 0015 | #23 | Not verified which errors Darwin throws (platform-notes, "Not verified"). |
| Network changes | `nw_path_monitor` compared on status, interfaces (name and type) and gateways, so the first update and unchanged repeats don't count. `UIApplicationDidEnterBackgroundNotification` is `Suspended` and `UIApplicationWillEnterForegroundNotification` is `Resumed`, both observed by name. | 0019 | #23 | macOS 26.6.2 (test binary, earlier version of the rules), iOS 27.0.1 on an iPhone 15 Pro Max (harness, "Roaming on iOS", "final", "suspension fix"). tvOS never. |
| Local network access | `LocalNetworkAccess.NotNeeded` on Apple: the app does not track the Local Network prompt yet. | 0020 | #27 | n/a |

## 2. File map

| Path | What it holds | Feature |
|---|---|---|
| `apple/build.gradle.kts` | Five Apple targets, a static `Shared` framework, `export` and `api` of the 12 pure-Kotlin modules | `Shared` framework |
| `apple/src/commonMain/kotlin/com/skeksik/current/apple/Framework.kt` | Package line only. Keeps the link task from being NO-SOURCE | `Shared` framework |
| `appleApp/Current.xcodeproj/project.pbxproj` | One target, the "Compile Kotlin Framework" phase, SDK-conditional settings, framework search path and `-framework Shared`, sandbox and hardened runtime on macOS | App target, signing, framework link |
| `appleApp/Current.xcodeproj/project.xcworkspace/contents.xcworkspacedata` | Default workspace stub | App target |
| `appleApp/Configuration/Config.xcconfig` | Empty `TEAM_ID`, product name, bundle ID, versions, `#include? "Local.xcconfig"` | Signing |
| `appleApp/Current/CurrentApp.swift` | `@main` `App` with one `WindowGroup` | App target |
| `appleApp/Current/ContentView.swift` | Placeholder that prints `VideoRanges`'s type name | App target, framework link |
| `appleApp/Current/Info.plist` | `CADisableMinimumFrameDurationOnPhone`, ATS `NSAllowsLocalNetworking`, `NSLocalNetworkUsageDescription` | Local network keys |
| `appleApp/Current/Assets.xcassets/**` | `AccentColor` (empty), `AppIcon` with one iOS 1024 image (dark and tinted slots empty, no macOS entries) | App target |
| `appleApp/Current/Preview Content/Preview Assets.xcassets/Contents.json` | Empty preview catalog (`DEVELOPMENT_ASSET_PATHS`) | App target |
| `scripts/build-apple-app.sh` | `xcodebuild` Debug, unsigned, for three SDKs | CI build |
| `scripts/check-no-team-id.sh` | Fails on a committed `DEVELOPMENT_TEAM` value or non-empty `TEAM_ID` | Signing |
| `core/database/src/appleMain/.../UserDefaultsKeyValueStore.kt` | The `actual`s for the settings store, the machine-local store (prefix `current.local.`) and the secret store (memory), plus the `NSUserDefaults` store | Settings, machine-local store, secrets |
| `core/database/src/iosMain/.../DeviceName.kt` | `UIDevice.currentDevice.name` | Device name |
| `core/database/src/tvosMain/.../DeviceName.kt` | Same as iOS, KDoc copied | Device name |
| `core/database/src/macosMain/.../DeviceName.kt` | `SCDynamicStoreCopyComputerName`, or "Mac" | Device name |
| `core/network/src/appleMain/.../PlatformEngine.kt` | `Darwin` engine factory | HTTP engine |
| `core/network/src/appleMain/.../PlatformUnreachable.kt` | `false`: nothing outside `IOException` counts | Unreachable mapping |
| `core/network/src/appleMain/.../PlatformLocalNetworkAccess.kt` | `LocalNetworkAccess.NotNeeded` | Local network access |
| `core/network/src/appleMain/.../PlatformNetworkChanges.kt` | Path monitor, path signature, background and foreground observers | Network changes |
| Tests | **None.** No `appleTest`, `iosTest`, `macosTest` or `tvosTest` source set exists. `commonTest` (for example `DeviceIdentityTest`, `RoamingServerAddressTest`) compiles for Native targets but CI runs only `jvmTest` and `:web:jsNodeTest`. | — |

## 3. Unverified parts, per feature

**`Shared` framework and app target**
- `iosArm64` linked once on the owner's Mac (#9). `tvosArm64` was never linked: CI and `build-apple-app.sh` build only `iphonesimulator`, `macosx` and `appletvsimulator`.
- Release has never been built (`build-apple-app.sh` passes `-configuration Debug`), so neither have `linkRelease*` or `VALIDATE_PRODUCT`. Distribution signing and an archive were never done (#9 "Not verified").
- Runs from the Xcode Run button; a physical iPad or Apple TV (#9 "Not verified").
- The macOS window was never looked at (#9).
- No Swift code beyond the placeholder uses the framework: StateFlow, suspend, sealed and enum types have never been read from Swift. SKIE is not added. platform-notes lists 0.10.15 as mentioning Kotlin 2.4.20, but whether it works with this exact Kotlin, Ktor and Koin set is unverified.

**Signing guard**
- Tested only on `DEVELOPMENT_TEAM` and `TEAM_ID` lines (#10). The `DevelopmentTeam` key that Xcode writes under `TargetAttributes` was never tried (see 4.1).

**Info.plist keys (#34: no device run)**
- tvOS: the ATS key pages don't list tvOS, and TN3179 lists no local network privacy on tvOS. So plain `http` to a LAN address on tvOS is unverified, with or without the key.
- Whether `NSAllowsLocalNetworking` admits public IP addresses, and whether AVPlayer follows it.
- That `http://media.lan:8096` (a dotted name that doesn't end in `.local`) is blocked: read from the key's text, never tried.
- The prompt text has never been seen on a device through this plist. The iOS runs used a harness with its own copy of the keys.

**Stores and secrets**
- `UserDefaultsKeyValueStore`: never run under a test. It ran only inside the throwaway iOS harness (#17, #18 "Not verified").
- Machine-local store: device id restored by an iCloud or device backup onto a second iPhone, or by Migration Assistant on a Mac. ADR 0017 names the risk; nothing guards against it yet.
- tvOS persistent storage limits for `NSUserDefaults`: not researched (see section 7, unverified).
- Keychain: not written (M4).

**Device name (#34 "Not verified")**
- The exact generic string from `UIDevice.name` on iOS, and what tvOS returns.
- Whether `SCDynamicStoreCopyComputerName` works inside the App Sandbox (the macOS build is sandboxed) and stays local on macOS 15+.
- Whether `UIDevice.currentDevice` read off the main thread (Koin builds `DeviceIdentity` lazily, possibly on `Dispatchers.Default`) trips the Main Thread Checker.
- Nothing starts Koin on Apple, so none of this has run in the app (#34).

**Darwin engine and errors**
- Which `NSError`s reach `DarwinHttpRequestException` (an `IOException`, per api.ktor.io 3.3.x) and so become `Unreachable`: refused, unknown host, TLS, ATS refusal (platform-notes "Not verified").
- Whether `HttpTimeout`'s request timeout behaves on Darwin as on the JVM (platform-notes "Not verified").

**Network changes**
- tvOS: path monitor at start, Wi-Fi switch, and the two UIKit notifications under the SwiftUI lifecycle. All unverified.
- iOS: a move between access points of one network; a switch to another Wi-Fi network while suspended ("Roaming on iOS, suspension fix", "Not run").
- Two Wi-Fi networks with the same gateway address and no unsatisfied gap go unnoticed by design (#23).
- macOS: no `Suspended`/`Resumed` at all, including wake from sleep (#23 follow-up).
- The UIKit notification names are string literals checked against behaviour on iOS 27.0.1, not against a compiled constant.

**Local network access**
- Apple reports `NotNeeded`, so the denied state is never shown to the user on iOS or macOS 15+. What a denial does was measured on iOS: a local probe fails in 16-30 ms on Wi-Fi and hangs until its timeout on cellular ("Roaming on iOS").

**Tests**
- No Native test task runs anywhere. The path signature, the stores' prefixing and the device-name fallbacks have no tests at all.

## 4. Code review

### 4.1 `check-no-team-id.sh` misses the `DevelopmentTeam` key — should-fix, confidence high
`scripts/check-no-team-id.sh:11` greps for `DEVELOPMENT_TEAM[^=]*=` only. When a team is picked under Signing & Capabilities, Xcode also
writes the team under `TargetAttributes` as `DevelopmentTeam = ABCDE12345;` (camel case). `project.pbxproj:103-105` has that dictionary
today, holding `CreatedOnToolsVersion` only. Scenario: the owner picks a team in Xcode to sign for a device, then resets the build setting
to `${TEAM_ID}`. The build setting passes the check, but the `TargetAttributes` entry is committed to the public repo. Fix: also fail on
`DevelopmentTeam\s*=` in tracked `project.pbxproj` files. Consider `PROVISIONING_PROFILE_SPECIFIER` too.

### 4.2 Device id lives in `NSUserDefaults`, which backups restore — should-fix before M4, confidence high (documented)
`core/database/src/appleMain/.../UserDefaultsKeyValueStore.kt:8-9`. The machine-local store is `NSUserDefaults` under `current.local.`.
The KDoc says so, and ADR 0017 defers the fix to M4. Scenario: once Keychain sessions exist (M4), the user restores an iPhone backup onto a
new iPhone, or migrates a Mac. Both devices then sign in to Jellyfin with the same device id, and each sign-in revokes the other's token,
which is exactly what ADR 0017 exists to prevent. Must land together with the Keychain, not after it: a Keychain item with
`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, or an `NSUserDefaults` value checked against a `ThisDeviceOnly` Keychain marker.

### 4.3 Apple reports `LocalNetworkAccess.NotNeeded` although iOS and macOS 15+ enforce local network privacy — should-fix before M4, confidence high
`core/network/src/appleMain/.../PlatformLocalNetworkAccess.kt:5`. `LocalNetworkPermission.NotNeeded`'s KDoc says "Local addresses are
reachable", which is false on iOS and macOS 15+ after a denial (TN3179). Scenario: the user taps "Don't Allow" at the first prompt. Home's
banner (`HomeLocalNetworkViewModel`) stays `Hidden`, so the app never explains why the home server only works over its internet address, and
offer no way to Settings. On cellular, roaming still tries the local address first and waits for its 5 s timeout (measured, "Roaming on
iOS"). There is no query API on Apple. TN3179 suggests reading `NWPath.unsatisfiedReason == .localNetworkDenied` from an
`NWConnection` to a local address, or `kDNSServiceErr_PolicyDenied` from a browser. The KDoc on `LocalNetworkAccess.NotNeeded` ("Apple until
its prompt is handled") is honest about it; `LocalNetworkPermission.NotNeeded`'s KDoc should name Apple as the exception until then.

### 4.4 Every Darwin failure becomes `Unreachable` with no detail — should-fix, confidence medium
`core/network/src/appleMain/.../PlatformUnreachable.kt:4` together with `ServiceException.kt:77`. `DarwinHttpRequestException` extends
`IOException` (api.ktor.io, Ktor 3.3.x reference), so every `NSURLSession` error maps to `ServiceErrorKind.Unreachable`, with
`detail = "DarwinHttpRequestException"`. The `NSError` domain and code are dropped. Scenarios:
- A user types `http://media.example.com:8096`, a public name over plain http. ATS refuses it (`NSURLErrorAppTransportSecurityRequiresSecureConnection`, -1022), and add-server says "unreachable" instead of "this platform needs https for internet addresses".
- A home server with a self-signed certificate (`NSURLErrorServerCertificateUntrusted`, -1202) reads as unreachable, not as a certificate problem.

Suggested fix: map `origin.code` in an Apple `actual` to a numeric detail, or to new kinds (`Insecure`, `Certificate`). The code is safe to
log; the `userInfo` is not, since it holds the failing URL. That error mapping needs a Mac run before it is relied on, as platform-notes
already lists. The KDoc's claim "not known to come outside `IOException`" is accurate but undersells the problem.

### 4.5 The ViewModels can't be used from Swift without an Apple facade that doesn't exist yet — should-fix before M4, confidence high
`apple/build.gradle.kts:14-29` exports the modules but no entry point. What SwiftUI needs and cannot do itself:
- **Koin start and the module list.** The list is private to the Compose `shared` module (`shared/.../App.kt:28`). The web facade repeats it (`web/.../CurrentWeb.kt:37`). Apple would be a third copy.
- **ViewModel lifetime.** The ViewModels extend `androidx.lifecycle.ViewModel` and use `viewModelScope`. Clearing them takes a `ViewModelStore` (`web/.../Pages.kt:13-25` does this with `ViewModelHolder`); Swift can't call `onCleared`. Without a holder, every SwiftUI screen leaks its ViewModel's coroutines, including `SharingStarted.Eagerly` collectors of `ServerRepository.servers`.
- **Constructors.** Several ViewModels have an `internal` primary constructor and a public secondary one, which is fine. But arguments come from Koin (`koin.get()`), which Swift can't call generically.
- **Exceptions.** No public `suspend fun` has `@Throws` (3 `@Throws` in the whole tree, none on the API Swift would call). A Swift call to `JellyfinAccounts.signIn` that fails with `ServiceException` terminates the app: Kotlin/Native only bridges declared exceptions, and suspend functions declare only `CancellationException` by default.

Suggested fix: an `apple` facade (`CurrentApple`) that starts Koin once off the main thread, hands out ViewModel holders, and is the only thing Swift calls. Ideally the module list and ViewModel factories move to one pure-Kotlin place that `shared`, `web` and `apple` all read (ADR 0014: "the only place that names every feature").

### 4.6 The framework exports far more than SwiftUI should touch — nit / API design, confidence high
`apple/build.gradle.kts:17-28` exports all 12 modules. Swift will see, among others, `LocalNetworkRequestBridge` (Android activity
plumbing), `createHttpClient`, `RoamingAddressSources` collaborators, `InMemorySecretStore`, and `UserDefaultsKeyValueStore` (public at
`UserDefaultsKeyValueStore.kt:15`, while every other `actual` is internal). This bloats the Obj-C header and invites Swift code to bypass
the ViewModels. Export `core:model` and the `feature:*` modules (their UI states and ViewModels) plus the facade; keep `core:network`,
`core:database` and `data:*` as `implementation`. Check which `core:network` types the UI states expose (`ServiceException`, `ServerUrl`)
before narrowing. Also make `UserDefaultsKeyValueStore` `internal`.

### 4.7 `InMemorySecretStore` is a plain `HashMap` hit from two threads on Apple — should-fix, confidence medium
`core/database/src/commonMain/.../SecretStore.kt:55-69` is bound in production on Apple (`UserDefaultsKeyValueStore.kt:12`) and Android.
`JellyfinAccounts` reads and writes it from `viewModelScope` (main dispatcher, sign-in and sign-out) and from `AppScope`
(`Dispatchers.Default`: session restore at start, dropping a session on a 401). Kotlin/Native collections are not thread-safe. Scenario: a
401 handled on `Default` removes a token while the user signs in on main. The map's internal state can corrupt, giving a lost token or an
exception, rarely and not reproducibly. Guard it with a `Mutex` (its methods already suspend). The window is small; the fix is three lines.

### 4.8 Network changes: notes on `PlatformNetworkChanges.kt` — mostly sound
- `last` (line 35) is touched only on the monitor's serial queue: `dispatch_queue_create(..., null)` is serial. No race. Good.
- `nw_endpoint_copy_address_string` is freed (line 71). Good.
- The observers use `queue = null`, so blocks run on the posting (main) thread and only `trySend`. Good.
- **macOS has no `Resumed` after sleep** (lines 54-56: the UIKit names are never posted there). A Mac that sleeps at home and wakes on
  another network gets a path update, but not always: a wake on the same Wi-Fi after hours gives requests the cached address, then the
  15 s retry. Improvement: `NSWorkspaceDidWakeNotification` (and `WillSleep` as `Suspended`). Note that it posts on
  `NSWorkspace.sharedWorkspace.notificationCenter`, not on `NSNotificationCenter.defaultCenter`, so it needs a `macosMain` file. Nit, confidence high that it is missing; the benefit is unmeasured.
- KDoc (lines 26-31) is accurate and matches the iOS runs.

### 4.9 Device name: KDoc copied to tvOS, and main-thread access — nit, confidence high (KDoc) / low (threading)
- `core/database/src/tvosMain/.../DeviceName.kt:5-8` says "such as \"iPhone\" since iOS 16" and blames the Local Network prompt. On tvOS
  the name and the version differ: the entitlement page says tvOS 16, and TN3179 says tvOS has no local network privacy. Rewrite it for
  tvOS, or mark what tvOS returns as unverified.
- `UIDevice` is a UIKit class, imported into Swift as `@MainActor`. `DeviceIdentity` is a lazy Koin `single`, so its first `get()` may
  run on `Dispatchers.Default` (session restore). Whether the Main Thread Checker flags `UIDevice.name` is unverified. Check it in the
  first PR that starts Koin on Apple, or read the name once on main in the facade and pass it in.

### 4.10 Two prefixes that nest — nit, confidence high
`UserDefaultsKeyValueStore.kt:9,15`: the settings store's prefix `current.` is a prefix of the local store's `current.local.`. A settings
key `local.deviceId` would read the device id. No such key exists today (keys: `servers`, per-server entries, `localNetwork.*` on the
local store). Use disjoint prefixes, for example `current.settings.` and `current.local.`. That needs a one-off migration if it is
done after M4, so do it before the Apple app ships.

### 4.11 Xcode project settings — nits, confidence high
- `project.pbxproj:344,396` `SWIFT_VERSION = 5.0`. Every Swift line is still to be written: start in Swift 6 language mode, so data races
  are compile errors when SKIE's `async` sequences are collected. Xcode 26's templates also offer main-actor default isolation (unverified
  for this project).
- `AppIcon` (`Assets.xcassets/AppIcon.appiconset/Contents.json`) has iOS entries only. The macOS build gets no icon, and the dark and
  tinted slots are empty. tvOS is a known gap (ADR 0012).
- `ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor` with an empty colorset: harmless, but decide whether the design tokens
  (ADR 0006 consequence) feed it.
- No `ITSAppUsesNonExemptEncryption` key: App Store Connect then asks about export compliance on every upload. Add it when distribution
  starts (only standard HTTPS is used).
- `ENABLE_HARDENED_RUNTIME`, `ENABLE_APP_SANDBOX` and `ENABLE_OUTGOING_NETWORK_CONNECTIONS` are set for all SDKs. They only take effect
  on macOS, which is fine. ADR 0012 doesn't record that the Mac app is sandboxed, while platform-notes says "Whether the Mac app will be
  sandboxed is not decided". The project has decided it; record that.

### 4.12 `build-apple-app.sh` covers less than CI's job name suggests — should-fix, confidence high
`scripts/build-apple-app.sh:8-11` builds Debug for simulators and macOS only. The device slices (`iosArm64`, `tvosArm64`) and Release
(where Kotlin links with optimisations and `VALIDATE_PRODUCT = YES`) are never built. Scenario: a dependency that publishes no `tvosArm64`
klib, or a Release-only link failure, is found only when someone archives for TestFlight. Building by SDK needs no simulator runtime,
so adding `-sdk iphoneos` and `-sdk appletvos` with `CODE_SIGNING_ALLOWED=NO` should work the same way (unverified). One Release
build per PR may be too slow; nightly or on `main` pushes would do.

### 4.13 No Native tests run — should-fix, confidence high
CI runs `jvmTest` and `:web:jsNodeTest` only (`scripts/check.sh:10`, `.github/workflows/ci.yml` macOS job: build only). So:
- `commonTest` never runs against Kotlin/Native, whose memory model, `HashMap` and coroutine dispatchers differ;
- the Apple `actual`s have no tests at all.

Suggested fix: add `:core:database:macosArm64Test` and `:core:network:macosArm64Test` (or `allTests` filtered to `macosArm64`) to the macOS
job, and small `appleTest` tests:
- `UserDefaultsKeyValueStore` round trip and prefixing, with a suite name, not `standardUserDefaults`;
- the path signature from a fake path, which needs extracting a pure function over (status, interfaces, gateways).

`iosSimulatorArm64Test` needs a simulator runtime; `macosArm64Test` doesn't.

### 4.14 Conventions check
- Dependency direction: `apple` depends only on pure-Kotlin modules, with no Compose. Good.
- No Compose in pure-Kotlin modules: the Apple source sets import Foundation, Network, UIKit and SystemConfiguration only. Good.
- commonMain first: the Apple source sets hold only `actual`s for real platform APIs. Good.
- `CancellationException`: not caught anywhere in this area. Good.
- Secrets and logging: nothing in the Apple source sets logs. Tokens are in memory only. Good.
- The repo is public: no team ID, hostnames or addresses in the project. `Config.xcconfig` warns. Good.
- CapabilitiesState: not read in this area (nothing feature-level here yet).

## 5. Code quality summary

The Apple slice is small, careful and well documented:
- Each `actual` is a few lines and states its reason, with a pointer to the measured evidence in platform-notes.
- The device-name fix came straight from a device run that caught a watchdog kill.
- The path monitor's signature comparison is measured against about 230 no-change updates on a real iPhone.
- The project keeps signing out of the repo and guards it in CI.
- The single-target, minimum-26 decision keeps the Swift side simple.

The main problems:
1. **Nothing connects SwiftUI to the core yet.** No Koin start, no ViewModel lifetime, no exception policy, and the module list exists in two copies already. That is the critical path for M4.
2. **Almost nothing in this area is tested or built in its shipping form.** No Native tests, no device slices, no Release build.
3. **Three platform gaps are deferred but known:** Keychain, device id out of backups, local network denial on Apple. Two of them (4.2, 4.3) change behaviour a user can see and should be planned as M4's first PRs, not its last.
4. **Darwin errors lose their cause** (4.4). That will make add-server's messages wrong on Apple for ATS and certificate failures.

## 6. Improvements (ranked)

| # | Title | Why | Modules | Effort | Priority |
|---|---|---|---|---|---|
| 1 | Apple facade: Koin start, ViewModel holders, one shared module list | SwiftUI can't start the core or clear ViewModels (4.5). The module list is already duplicated in `shared` and `web`. | `apple`, a pure-Kotlin composition spot (new or in `feature`), `shared`, `web` | M | High (first M4 PR) |
| 2 | Add SKIE (needs owner approval as a dependency) and prove one screen | StateFlow as `AsyncSequence`, sealed `HomeUiState` as a Swift enum, `async` suspend. Confirm it works on Kotlin 2.4.20 before writing screens (ADR 0006: "re-check when the first Swift screen is written"). | `apple`, `gradle/libs.versions.toml`, `appleApp` | S | High |
| 3 | Run Native tests in CI (`macosArm64Test`), plus `appleTest` for the store and path signature | Nothing Apple-specific is tested (4.13) | `core:database`, `core:network`, CI | S-M | High |
| 4 | Keychain `SecretStore` and a device id that backups don't carry | 4.2. Users otherwise sign in at every launch, and later sign each other out. | `core:database` (appleMain) | M | High (M4) |
| 5 | Apple `LocalNetworkAccess` from `NWConnection`'s unsatisfied reason | 4.3. The denial is invisible; roaming wastes 5 s per check on cellular. | `core:network` (appleMain) | M | Medium-High |
| 6 | Keep the `NSError` code on Darwin failures; ATS and certificate kinds | 4.4. Wrong add-server messages. | `core:network` (appleMain, ServiceException) | S-M | Medium |
| 7 | Team-ID guard also catches `DevelopmentTeam` | 4.1. Public repo. | `scripts` | S | Medium (cheap) |
| 8 | Build device SDKs and a Release configuration in CI | 4.12 | `scripts/build-apple-app.sh`, CI | S | Medium |
| 9 | Narrow the framework's exports; make `UserDefaultsKeyValueStore` internal | 4.6 | `apple`, `core:database` | S | Medium (with #1) |
| 10 | Lock `InMemorySecretStore` | 4.7 | `core:database` | S | Medium |
| 11 | macOS sleep and wake as `Suspended`/`Resumed` | 4.8 | `core:network` (macosMain) | S | Low |
| 12 | Disjoint `NSUserDefaults` prefixes before shipping | 4.10 | `core:database` | S | Low |
| 13 | Project hygiene: Swift 6 mode, macOS icon, tvOS KDoc, record the sandbox decision in ADR 0012 | 4.9, 4.11 | `appleApp`, `core:database`, docs | S | Low |

## 7. Platform conventions and UX research

(In progress: research against Apple's Human Interface Guidelines follows in the next commit.)

## Seen outside my area
- `shared/src/commonMain/.../App.kt:28` and `web/src/jsMain/.../CurrentWeb.kt:37` each list the Koin modules, and the web facade builds
  every ViewModel by hand. A third copy for Apple is coming (4.5). This is a candidate for one shared composition point.
- `core/database/src/commonMain/.../SecretStore.kt:55`: `InMemorySecretStore` isn't thread-safe and is the production store on Android too (4.7).
- `core/network/.../LocalNetworkAccess.kt:63-64`: `LocalNetworkPermission.NotNeeded`'s KDoc ("Local addresses are reachable") is wrong for Apple today (4.3).
- `core/network/.../ServiceException.kt:77`: `IOException` as one bucket hides TLS and policy failures on every engine, not just Darwin.
