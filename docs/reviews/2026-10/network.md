# Review: networking core (`core/model`, `core/network`)

Reviewed 2026-10-21 at `origin/main` 6fb1957, by reading the code. Gradle was not run. Line numbers refer to that commit.
ADRs: 0013-0020. Sources: `git log`, the PR descriptions (#12-#38), docs/platform-notes.md and docs/architecture.md.

## 1. Features in this area

| # | Feature | What it does | ADR | PRs | Checked on (platform-notes, PRs) |
|---|---|---|---|---|---|
| F1 | Domain model | `ServiceType`, `ServerRef` (an opaque key), `MediaId`, and the open kinds `MediaKind`, `ImageType`, `Permission` and `ServerFeature`. Also `Artwork` and `AspectRatio`, `User`, `SavedServer` and `ServerAddress`, plus `AppInfo`, which is generated from `gradle.properties`. Every other module uses these types. | 0013, 0014 | #12, #13, #17 | JVM tests and `jsTest` (browser) on Windows (#12). No Apple build except in CI. The Swift-side shape was never checked. |
| F2 | Capabilities state | `Capabilities` and `ServerStatus` (signed in, reachable, user, version, features), behind `CapabilitiesState` with the writer `MutableCapabilitiesState`. Features read it to hide what is unavailable. | 0008, 0014 | #13 | JVM tests only. |
| F3 | Server clients | `ServerClients.create` makes one `ServerClient` per server on the shared `HttpClient`. It rebases requests onto the current address (`ServerRequests`) and runs a per-service `RequestAuth`. It never follows redirects, and `onUnauthorized` handles a 401. | 0015, 0018 | #14, #19, #23 | JVM (Java engine, including a real refused connection). Android, iOS and browsers only through the roaming harness runs (#23). |
| F4 | Error model | `ServiceException` with `ServiceErrorKind`. Engine errors map to kinds. The original cause is not kept, so URLs and bodies never leak. Platform-specific "unreachable" errors are recognised: Android's missing `INTERNET` permission and the browser's "Fail to fetch". | 0015 | #14, #23 | JVM tests. The Android `SecurityException` was seen on a device (#23). The JS predicate was seen in browser runs. Darwin/JS/Android exception types are unverified (platform-notes, "Not verified"). |
| F5 | Redacted request log | `RequestLog` produces a `RequestLogEntry` with the method, the redacted path, the status, the duration and `cancelled`. `Redaction` hides secret query values. | 0015 | #14, #33 | JVM tests only. **No logger is bound in any app** (`NetworkLogger.None`, see §4 N3). The `cancelled` output was never seen on a device. |
| F6 | `RequestDeadline` | Ends a request at its timeout even when a blocking engine (Android `HttpURLConnection`, DNS) ignores cancellation. | — (platform-notes "Request timeouts on blocking engines") | #26 | JVM test with a fake blocking engine. Pixel 8 Pro, Android 17 (probes ended at 5.01-5.10 s). Darwin and JS were not checked. |
| F7 | Saved servers and settings store | `ServerRepository` / `StoredServerRepository` stores each server under its own key, with an index of keys. `newServerRef` makes a random key. `ServerUrl` parses and normalises addresses. `ServerUrl.candidates` guesses a scheme and port. `ServerProbe` and `ServerIdentity` form the contract the data layers implement. | 0016 | #14, #17 | JVM tests. The Android and web stores compile but were not run (#17). A live Jellyfin was probed on 2026-10-20 (platform-notes). |
| F8 | Several addresses per server | `withAddress`, `withoutAddress`, `withAddressMoved` and `canRemoveAddress` edit the address list. `AddressScopes` classifies an address as local or internet. | 0016, 0019 | #17, #23, #35 (UI) | JVM tests. The addresses page ran on the Android Emulator (#35, platform-notes). |
| F9 | Roaming | `RoamingAddressSources` keeps one source per saved server and restarts it after a bug. `RoamingServerAddress` is an event loop that probes every address and uses the best proved one, with network changes, suspension, periodic checks and retries. `OrderedServerAddress` serves services that cannot identify themselves (Stash). `FixedServerAddress` and `FirstAddressSources` are the simple variants. | 0019 | #23, #32, #38 | macOS 26.6.2 (harness), iOS 27.0.1 (cases 7 and 8 pass), Pixel 8 Pro Android 17 (cases A and B, offline), Android Emulator (case B), Chrome, Edge and Firefox on Windows 11 (final run and headless failover). **Not run:** tvOS, desktop JVM (Windows/Linux), Android on mobile data, VPN on Android, AP-to-AP moves. |
| F10 | Network change signals | `NetworkChanges` and `NetworkChange` (`Changed`, `Suspended`, `Resumed`) per platform. Android uses the default-network callback via `DefaultNetworkTracker`. Apple uses `nw_path_monitor` with a signature comparison plus the UIKit background/foreground notifications. The JVM polls `NetworkInterface` every 5 s. JS listens for `online` and `navigator.connection` `change`. | 0019 | #23 | Apple: macOS and iOS (above). Android: Pixel and emulator. JS: three browsers. **JVM polling was never run on a real network change** (only `PolledInterfacesTest`). tvOS never. |
| F11 | Local network access | `LocalNetworkAccess`, `LocalNetworkPermission`, `shouldAskForLocalNetwork`, `LocalNetworkRequestBridge` and `LocalNetworkPermissionState` (counting denials into `Blocked`). Android's `AndroidLocalNetworkAccess` uses `ACCESS_LOCAL_NETWORK` on API 37+. Roaming skips local addresses while access is denied (#38). | 0020 | #27, #36, #38 | Pixel 8 Pro and the Android Emulator, Android 17 (#27 steps 1-6, rotation, dismissed dialog). Emulator (#38 cases a-d). **Not run:** a denial in the app's own dialog followed by roaming, `Blocked` in roaming, split DNS while denied on a real network, the overlay-VPN exception, the periodic check while denied, and #36 on any device. Apple and desktop/web are `NotNeeded`. |

## 2. File map

Paths are relative to `core/`; `…` stands for `src/<set>/kotlin/com/skeksik/current/core/<module>`.

| Path | Holds | Feature |
|---|---|---|
| `model/build.gradle.kts` | KMP library: all targets; generates `AppInfo` from `current.version` | F1 |
| `model/…/Artwork.kt` | `ImageType` (open), `AspectRatio` (reduced fraction), `Artwork` | F1 |
| `model/…/Capabilities.kt` | `Capabilities`, `ServerStatus`, `ServerFeature` | F2 |
| `model/…/MediaId.kt` | `MediaId` (server + opaque id) | F1 |
| `model/…/MediaKind.kt` | open `MediaKind` | F1 |
| `model/…/SavedServer.kt` | `SavedServer`, `ServerAddress`, `AddressScope` | F7, F8 |
| `model/…/Server.kt` | `ServiceType` (open), `ServerRef` | F1 |
| `model/…/User.kt` | `User`, open `Permission` | F1, F2 |
| `model/commonTest/…/{Artwork,Capabilities,MediaId,OpenValues,User}Test.kt` | model invariants, open values, capability queries | F1, F2 |
| `network/build.gradle.kts` | KMP library; Ktor engines per platform; depends on `core:model` (api) and `core:database` | all |
| `network/commonMain/…/CapabilitiesState.kt` | `CapabilitiesState`, `MutableCapabilitiesState` | F2 |
| `network/commonMain/…/HttpClientFactory.kt` | `createHttpClient`, lenient `ServiceJson`, `expect platformEngine` | F3 |
| `network/commonMain/…/RequestAuth.kt` | `RequestAuth` fun interface | F3 |
| `network/commonMain/…/ServerClient.kt` | `ServerClient`, `ServerClients`, `ChosenAddress` | F3, F4 |
| `network/commonMain/…/ServerPlugins.kt` | `ServerRequests` (rebase + auth), `rebaseOnto`, `RequestLog` plugin | F3, F5 |
| `network/commonMain/…/ServiceException.kt` | `ServiceErrorKind`, `ServiceException`, `serviceExceptionFor`, platform predicates | F4 |
| `network/commonMain/…/NetworkLogger.kt` | `NetworkLogger`, `RequestLogEntry` | F5 |
| `network/commonMain/…/Redaction.kt` | `Redaction` (secret query names, `pathAndQuery`, `url`) | F5 |
| `network/commonMain/…/RequestDeadline.kt` | `RequestDeadline` plugin | F6 |
| `network/commonMain/…/ServerUrl.kt` | `ServerUrl` parse/normalise | F7 |
| `network/commonMain/…/ServerProbe.kt` | `ServerIdentity`, `ServerProbe`, `ServerUrl.candidates` | F7, F9 |
| `network/commonMain/…/ServerRepository.kt` | `ServerRepository`, `newServerRef`, `StoredServerRepository`, stored DTOs | F7 |
| `network/commonMain/…/ServerAddresses.kt` | address list edits on `SavedServer` | F8 |
| `network/commonMain/…/AddressScopes.kt` | `ServerUrl.scope`, `needsLocalNetworkAccess`, IPv4/IPv6 classification | F8, F11 |
| `network/commonMain/…/ServerAddressSource.kt` | `ServerAddressSource`, `FixedServerAddress` | F3, F9 |
| `network/commonMain/…/ServerAddressSources.kt` | `ServerAddressSources`, `FirstAddressSources`, `RoamingAddressSources` (+ `StableAddress`, `NoAddress`) | F9 |
| `network/commonMain/…/RoamingServerAddress.kt` | `RoamingTiming`, `ManagedServerAddress`, `RoamingServerAddress` (event loop), `OrderedServerAddress` | F9, F11 |
| `network/commonMain/…/NetworkChanges.kt` | `NetworkChanges`, `NetworkChange`, `expect platformNetworkChanges` | F10 |
| `network/commonMain/…/DefaultNetworkTracker.kt` | Android default-network report interpretation (common, testable) | F10 |
| `network/commonMain/…/LocalNetworkAccess.kt` | `LocalNetworkAccess`, `LocalNetworkPermission`, `shouldAskForLocalNetwork`, `LocalNetworkRequestBridge`, `LocalNetworkPermissionState` | F11 |
| `network/commonMain/…/NetworkModule.kt` | Koin `networkModule`, `AppScope`, `logBug` | all |
| `network/androidMain/…/AndroidLocalNetworkAccess.kt` | global `localNetworkRequests` bridge, `AndroidLocalNetworkAccess` | F11 |
| `network/androidMain/…/PlatformEngine.kt` | Ktor Android engine | F3 |
| `network/androidMain/…/PlatformNetworkChanges.kt` | default network callback → `NetworkChange` | F10 |
| `network/androidMain/…/PlatformUnreachable.kt` | missing-`INTERNET` `SecurityException` predicate | F4 |
| `network/appleMain/…/PlatformEngine.kt` | Darwin engine | F3 |
| `network/appleMain/…/PlatformLocalNetworkAccess.kt` | `NotNeeded` | F11 |
| `network/appleMain/…/PlatformNetworkChanges.kt` | `nw_path_monitor` + UIKit notifications by name | F10 |
| `network/appleMain/…/PlatformUnreachable.kt` | always false | F4 |
| `network/jsMain/…/PlatformEngine.kt` | Js (fetch) engine | F3 |
| `network/jsMain/…/PlatformLocalNetworkAccess.kt` | `NotNeeded` | F11 |
| `network/jsMain/…/PlatformNetworkChanges.kt` | `online` + `navigator.connection` `change` | F10 |
| `network/jsMain/…/PlatformUnreachable.kt` | "Fail to fetch" predicate | F4 |
| `network/jvmMain/…/PlatformEngine.kt` | Java engine | F3 |
| `network/jvmMain/…/PlatformLocalNetworkAccess.kt` | `NotNeeded` | F11 |
| `network/jvmMain/…/PlatformNetworkChanges.kt` | `PolledInterfaces`, `snapshotOf` | F10 |
| `network/jvmMain/…/PlatformUnreachable.kt` | always false | F4 |
| `network/commonTest/…/AddressScopeTest.kt` | scope classification | F8 |
| `network/commonTest/…/CapabilitiesStateTest.kt` | writer semantics, Koin sharing | F2 |
| `network/commonTest/…/DefaultNetworkTrackerTest.kt` | Android signal interpretation | F10 |
| `network/commonTest/…/FakeLocalNetworkAccess.kt` | test double | F11 |
| `network/commonTest/…/LocalNetworkAccessTest.kt` | `shouldAsk`, denial counting, bridge changes | F11 |
| `network/commonTest/…/PlatformUnreachableTest.kt` | message predicates | F4 |
| `network/commonTest/…/RedactionTest.kt` | redaction | F5 |
| `network/commonTest/…/RoamingAddressSourcesTest.kt` | source lifecycle, restarts, Stash ordering, denial | F9, F11 |
| `network/commonTest/…/RoamingServerAddressTest.kt` | ~90 virtual-time cases of the event loop | F9, F11 |
| `network/commonTest/…/ServerAddressesTest.kt` | address edits | F8 |
| `network/commonTest/…/ServerRepositoryTest.kt` | persistence, capabilities announce | F7 |
| `network/commonTest/…/ServerUrlCandidatesTest.kt`, `ServerUrlTest.kt` | parsing, candidates | F7 |
| `network/jvmTest/…/HttpClientFactoryTest.kt` | lenient JSON | F3 |
| `network/jvmTest/…/LocalNetworkRequestTest.kt` | permission state + bridge | F11 |
| `network/jvmTest/…/PolledInterfacesTest.kt` | JVM polling | F10 |
| `network/jvmTest/…/RequestDeadlineTest.kt` | blocked-engine deadline | F6 |
| `network/jvmTest/…/ServerClientTest.kt` | client, error mapping, log | F3, F4, F5 |

## 3. Unverified parts, per feature

Across the area:
- `commonTest` has run on the JVM only. `core:model`'s `jsTest` ran once (#12). No `core:network` test has run on JS, Android or native. The JS predicate and the Android `SecurityException` predicate are covered only by message-string tests.
- Apple targets were built only in CI, never locally (all PRs).

Per feature:
- **F1 Model:** how the types look from Swift (open value classes, `companion` constants) was never checked (#12). `AppInfo` generation was not run outside Gradle on Windows.
- **F2 Capabilities:** `reachable` is written only by `RoamingServerAddress`. For Stash (`OrderedServerAddress`) and for services without a probe, nothing ever sets it (§4 N5). The `reachable = null` reset after a source restart is untested (#23 "Not tested").
- **F3 Clients:** no request has gone to a live server except the sign-in flows and the roaming harnesses. The Darwin and JS engines' redirect handling under `followRedirects = false` is not covered by a platform test.
- **F4 Errors:** which exceptions the Darwin, JS and Android engines throw for a refused connection, an unknown host or a TLS failure (platform-notes, "Not verified"). Anything unmapped passes through as a bug, and its message may contain the URL.
- **F5 Log:** never wired to any sink in an app (§4 N3). The `cancelled` vs `no response` explanation for the internet probe at home is an inference, never seen on a device (platform-notes, "Not verified").
- **F6 Deadline:** not checked on Darwin or JS (#26). It does not bound body reads after the headers, by design. It does not bound `awaitCurrent` either (§4 N2).
- **F7 Saved servers:** the Android `SharedPreferences` and web stores were not run at the time (#17). Corrupt or duplicate index entries are tested only for an unreadable entry, not for a duplicate (§4 N10).
- **F8 Address scope:** the overlay-VPN exception (`100.64.0.0/10`, `.ts.net`) is "not verified on a device" (`AddressScopes.kt:10`, #38). IPv6 overlay addresses are not handled at all (§4 N4). Classification goes by name only, so split DNS is not seen (by design, platform-notes).
- **F9 Roaming:**
  - **Not run:** tvOS; desktop JVM (Windows, Linux) on any real network change; Android on mobile data; a VPN on Android; moves between access points of one network; a switch to another Wi-Fi network while suspended on iOS (#23).
  - **Not unit-tested** (#23): the queue bound; a stray `CancellationException` ending a run; the app scope's handler; the `reachable` reset on replacement; a signal flow that completes without failing.
  - **Open follow-ups** (#23):
    - The restart after a bug waits for probes that ignore cancellation.
    - On macOS, waking from sleep sends no `Resumed`.
    - Requests already sent are not cancelled on a network change.
    - Background playback needs its own rule (§4 N2).
- **F10 Signals:**
  - **JVM:** the polling cost (a `NetworkInterface` enumeration every 5 s on `Dispatchers.IO`) was never measured on Windows. Whether virtual adapters cause false changes is reasoned in ADR 0019, not observed.
  - **Apple:** the gateway signature was never checked for telling two networks apart. Whether `UIApplicationDidEnterBackgroundNotification` and `UIApplicationWillEnterForegroundNotification` are posted on tvOS is unknown.
  - **Android:** the `SecurityException` path (no `ACCESS_NETWORK_STATE`) has no test.
- **F11 Local network:**
  - **Not run on a device:** #36 (the Home ask); a denial in the app's own dialog followed by roaming; `Blocked` together with roaming; the periodic check while denied; an internet name that resolves locally on a real network; whether Tailscale needs the permission (#38).
  - **Not reached:** a switch to denied while the app keeps running. Revoking access kills the process (#38 case d), so the drop-in-use path is unit-tested only.
  - **Not done:** the machine-local store is not yet excluded from Android backups (#27).
  - **Apple:** iOS/macOS local network privacy is modelled as `NotNeeded`. A denial there is seen only as fast `Unreachable` probes. No UI can explain it.

## 4. Code review

Each finding was checked against the code at 6fb1957. Severity: must-fix / should-fix / nit.

| # | Where | What is wrong | Scenario | Severity | Confidence |
|---|---|---|---|---|---|
| N1 | `network/commonMain/…/LocalNetworkAccess.kt:102-107` vs `AddressScopes.kt:12-13` | `shouldAskForLocalNetwork(SavedServers)` asks when any saved address has the stored scope `Local`. That scope includes overlay VPN addresses (`100.64.0.0/10`, `*.ts.net`), but roaming treats exactly those as *not* needing the permission (`needsLocalNetworkAccess`). The two halves disagree. | On Android 17, with a single server saved as `https://media.example.ts.net`, Home asks for "Nearby devices" (#36 `HomeLocalNetworkViewModel`). Roaming would reach that server without it. A user who taps Don't allow twice ends in `Blocked` for nothing. | should-fix | high (code). Whether Android exempts the overlay is itself unverified. |
| N2 | `network/commonMain/…/RoamingServerAddress.kt:139-147`; `ServerClient.kt:30`; `ServerPlugins.kt:26` | While the source is `suspended`, `awaitCurrent` waits with no limit. Nothing bounds that wait: `RequestDeadline` and `HttpTimeout` cover only `Send`, and `awaitCurrent` runs before it (in `ServerClient.request` and in the plugin's `onRequest`). | iOS posts `DidEnterBackground` even when the app keeps running for background audio or PiP. With playback in M2, a progress or stop report (`Sessions/Playing/Progress`) sent in the background hangs until the app returns to the foreground, and playback time is never recorded. #23 noted this ("A later background audio feature will need its own rule"). | should-fix (before background playback) | high |
| N3 | `network/commonMain/…/NetworkModule.kt:36` | `single { NetworkLogger.None }` is the only `NetworkLogger` binding in the repo. No module overrides it. The redacted request log of ADR 0015 is built, tested and dropped. | A user reports "server offline". There is no log to attach. The #38 emulator run had to use the stand-in server's log because "the app's request log is not wired to logcat". | should-fix | high |
| N4 | `network/commonMain/…/AddressScopes.kt:15-20` | `isOverlayVpn` knows only Tailscale's IPv4 CGNAT range and `.ts.net`. Tailscale's IPv6 addresses are ULAs (`fd7a:115c:a1e0::/48`, from memory, unverified), so they classify as `Local` and `needsLocalNetworkAccess` is true. | A server saved at its Tailscale IPv6 literal is never probed while access is `CanAsk` or `Blocked` on Android 17. It shows "Offline" even though it may not need the permission. | should-fix | medium (the prefix and Android's treatment are unverified) |
| N5 | `network/commonMain/…/RoamingServerAddress.kt:642-693`; `ServerAddressSources.kt:84-89` | `OrderedServerAddress` (Stash) never writes `ServerStatus.reachable`, and neither does `NoAddress` (any service without a probe, Seerr today). Their status stays `null` ("unknown") forever. The offline states of ADR 0019 and `Capabilities` docs never show for them. | Stash is down: Home shows no offline state. Each request fails on its own as `Unreachable`, with no server-level signal. | should-fix (when `data:stash` lands) | high |
| N6 | `network/commonMain/…/RoamingServerAddress.kt:565, 576` | After a full check that misses, the last proved address is kept ("a miss keeps the last proved address") but `reachable` is set to `false`. A later successful request does not set it back. Only the next check does: 15 s, growing to 5 min while misses repeat. | A slow internet address times out a single 5 s probe while requests to it still succeed. The UI shows "Offline" while content loads. | nit | high (code) |
| N7 | `network/commonMain/…/LocalNetworkAccess.kt:157-168` | `LocalNetworkRequestBridge.request`: a second caller awaits the first caller's `pending` deferred. If the first caller is cancelled, its `finally` clears `pending`. `onResult` and `detach(finishing = true)` then complete nothing, and the second caller waits until its own scope is cancelled. | Two ViewModels (Home's and the add-server prompt's) ask at once, and the first is cleared while the dialog is up. The second "Allow" button stays busy. This is unlikely today because the dialog is modal, but nothing prevents it. | nit | high (code) |
| N8 | `network/commonMain/…/RoamingServerAddress.kt:210-211`; `ServerAddressSources.kt:65-74` | The guard `error("Network changes ended")` never fires in the app. `RoamingAddressSources` feeds sources a `shareIn` flow, which never completes. `retryWhen` restarts only a *failed* upstream. A platform flow that completes normally leaves every source without a signal, silently. Android does this on a `SecurityException` (`PlatformNetworkChanges.kt:21-23`), and `NetworkChanges.None` does it too. That is intended for Android, but the comment says the opposite. | A maintainer relies on the guard for a new platform signal that ends on error-free completion. Sources go blind with no log. | nit (comment accuracy, test gap) | high |
| N9 | `network/commonMain/…/CapabilitiesState.kt:11`; `ServerRepository.kt:46`; `ServerAddressSources.kt:50` | The KDoc says "only server setup and sign-in write it". In fact `StoredServerRepository`, `RoamingServerAddress` and `RoamingAddressSources` also write it. Writers take the final class `MutableCapabilitiesState`, not an interface, so a writer cannot be rebound (CLAUDE.md "interfaces in the constructor", ADR 0014). | A replacement capabilities store (for example, one persisted per edition) cannot be bound without editing `core:network`. | nit | high |
| N10 | `network/commonMain/…/ServerRepository.kt:57-60, 80-85` | `save` computes `isNew` outside `state.update`, so two saves on different threads can both append. This is documented as single-threaded. `load` keeps duplicate refs from the index as duplicate `SavedServer`s. | A hand-edited or partly written index with a ref listed twice shows the server twice in Settings. | nit | high |
| N11 | `model/…/SavedServer.kt:21`; `network/…/ServerRepository.kt:132, 140`; `LocalNetworkAccess.kt:106` | `ServerAddress.scope` is persisted, although it is derivable from the URL (`ServerUrl.scope`). Rules changed since data was first saved: #38 touched `AddressScopes`. The stored value can go stale, and `shouldAsk` reads the stored one while roaming computes it. | After a rule change, an old saved address keeps its old scope in the UI and in the ask decision, but roaming uses the new one. | nit | medium |
| N12 | `network/commonMain/…/ServerPlugins.kt:7, 10` | Duplicate `import kotlinx.coroutines.currentCoroutineContext`. | Compiler warning; import order unsorted. | nit | high |
| N13 | `network/commonMain/…/AddressScopes.kt:28, 54-58` | `ipv4(name)` is parsed twice. IPv4-mapped IPv6 (`::ffff:192.168.1.10`) classifies as `Internet`, because the first hextet is `0`. | Someone types a mapped literal and gets the plain-HTTP internet warning. | nit | high |
| N14 | `network/androidMain/…/AndroidLocalNetworkAccess.kt:18` | `localNetworkRequests` is a top-level global. `MainActivity` uses it directly, not through Koin. | A second activity, a test or an edition cannot substitute the requester. This breaks the "replace by rebinding" rule of ADR 0014. | nit | high |
| N15 | `network/commonMain/…/ServerAddressSources.kt:33-39` | `FirstAddressSources` ("For tests that need no roaming") ships in production `commonMain`. Only `data:jellyfin` tests use it. | It is public API that the app must never bind. Binding it by mistake would send credentials to the first address without a probe. | nit | high |
| N16 | `network/commonMain/…/NetworkModule.kt:27` | `logBug` uses `println`. On Apple, that goes to stdout and not to the unified log. There is no level, and it bypasses `NetworkLogger`, so bugs never reach a problem report either. | A source restart on iOS is invisible in Console.app. | nit | high |

Checked and found fine:
- `CancellationException` is never swallowed. `ServerClient.request` rethrows it, `serviceExceptionFor` returns null for it, and `RequestLog` reads `isActive` in `finally` without catching. `retryWhen` refuses it. `RoamingServerAddress.ask` catches only `ServiceException`. `RequestDeadline` uses `withTimeoutOrNull`, which keeps outer cancellation.
- Redaction: secret query values are hidden. Hosts never reach `RequestLogEntry`. `ServiceException` drops the cause. `ServerUrl.toString` is documented as not for logs.
- Dependency direction: `core:model` has no dependencies. `core:network` depends on `core:model` and `core:database` only. Neither module imports Compose. Platform code is limited to small `expect`/`actual` pieces.
- `StoredServerRepository.remove` writes the index before deleting the entry, so a crash leaves no dangling ref.
- The plugin order (log outside the deadline) matches the platform-notes analysis.

Test quality: the roaming loop is unusually well covered (about 90 virtual-time cases). Gaps:
- N1: `shouldAsk` with an overlay-only server.
- N7: the bridge with a cancelled first caller.
- N5: Stash `reachable`.
- N8: the `shareIn` completion path.
- Redaction of percent-encoded names such as `api%5Fkey`.
- The Android, Apple and JS `NetworkChanges` adapters (only the common tracker is tested).
- All tests run on the JVM only.

## 5. Code quality summary

Strengths:
- The design is consistent. Roaming is one serial event loop with tokens instead of locks, which makes a hard concurrency problem reviewable.
- Errors are modelled carefully, and secrets do not leak: no cause is kept, logs carry no hosts, and query values are redacted.
- Every subtle rule has a KDoc that explains why it exists and links to platform-notes evidence.
- Device runs on iOS, Android and three browsers back the main paths, and the unit tests are thorough.
- The `expect`/`actual` surface is small and `commonMain` comes first.

Main problems:
1. Two features are built but never reach users: the request log is unbound (N3), and Stash/Seerr reachability is never reported (N5).
2. The local-network logic has two sources of truth for "is this local". The ask (stored scope) and roaming (computed `needsLocalNetworkAccess`) disagree on overlay VPNs (N1, N11, N4).
3. The unlimited wait while suspended is a known time bomb for background playback (N2).
4. `RoamingServerAddress.kt` (695 lines) is dense. The rules are right but hard to change without the test suite. Comments and KDoc are thorough, though a few have drifted (N8, N9).
5. Platform adapters and JS/native behaviour rest on manual harness runs only. Desktop JVM roaming and tvOS have never run.

## 6. Improvements (ranked)

| Rank | Title | Why | Modules | Effort | Priority |
|---|---|---|---|---|---|
| 1 | Wire the request log | Bind a ring-buffer `NetworkLogger` (last N entries, in memory) and expose it to a "copy diagnostics" action. It also makes device runs need no harness (N3, N16). | core:network, shared, web | S | high |
| 2 | One rule for "needs local network access" | `shouldAskForLocalNetwork` should use `ServerUrl.needsLocalNetworkAccess` instead of the stored scope. Add a test with a `.ts.net`-only server (N1). | core:network | S | high |
| 3 | Report reachability for every source | `OrderedServerAddress` sets `reachable` from failures and successes. `NoAddress` servers get `reachable = false`. Alternatively, `ServerClient` marks a server reachable after a 2xx (N5, N6). | core:network | S-M | high |
| 4 | A rule for requests while suspended | Before background playback, give `awaitCurrent` a policy for suspension: either a caller-chosen bound, or keep the address in use while a background mode is active (N2). | core:network, later feature:player | M | high (before M2) |
| 5 | Fix the permission bridge race | Complete the shared deferred only when the last waiter leaves, or never clear `pending` until a result or finishing detach. Add a test (N7). | core:network | S | medium |
| 6 | Overlay VPN on IPv6 | Verify Tailscale's IPv6 prefix and Android's treatment of VPN interfaces, then extend `isOverlayVpn`. Handle IPv4-mapped IPv6 (N4, N13). | core:network, docs | S | medium |
| 7 | Derive scope, don't persist it | Read `scope` from the URL on load, or drop the stored field after a migration (N11). | core:model, core:network | M | medium |
| 8 | A writer interface for capabilities | Add an interface for the writer and bind `MutableCapabilitiesState` to it. Fix the KDoc (N9). | core:network | S | low |
| 9 | Correct the signal-completion comment and test it | Document that a completed platform signal means "no signal" through `shareIn`. Test that `RoamingAddressSources` tolerates it (N8). | core:network | S | low |
| 10 | Run `commonTest` on JS, plus a desktop roaming run | Add `jsTest` (and `macosArm64Test` on the macOS CI job) to `check.sh` for `core:network`. Do one recorded Windows desktop roaming run with `PolledInterfaces`. | build, docs | M | medium |
| 11 | Small cleanups | Remove the duplicate import, parse `ipv4` once, move `FirstAddressSources` to a test-fixtures source set, de-duplicate refs in `load`, and route `localNetworkRequests` through Koin (N10, N12-N15). | core:network, androidApp | S | low |
| 12 | Split `RoamingServerAddress.kt` | Move `OrderedServerAddress` and the `Check` and `Loop` state into their own files, without changing behaviour. | core:network | S | low |

## 7. Platform conventions and UX research

All sources were read on 2026-10-21. "Read" means the page was opened and the claim found in its text. "Unverified" means it could not be confirmed from a primary source.

### Sources

**Apple**
- HIG, Privacy (https://developer.apple.com/design/human-interface-guidelines/privacy). Read.
  - Ask only when the app clearly needs access, ideally when the feature is used, not at launch.
  - At most one custom screen may come before the system alert. Its single button is titled "Continue" or "Next", never "Allow".
  - Purpose strings are short, specific, active sentences.
- HIG, Loading (https://developer.apple.com/design/human-interface-guidelines/loading). Read.
  - Show something at once; use placeholders instead of a blank screen.
  - The HIG has no offline or connectivity page, and the tvOS design page says nothing about connectivity.
- TN3179, Understanding local network privacy (https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy). Read.
  - The first local operation triggers the alert, and it may be denied at once while the alert is up. Use waiting APIs or retry.
  - A denial is visible as `unsatisfiedReason == .localNetworkDenied` on the connection's path. After a later grant, the system retries waiting connections.
  - On iOS, an app in the background with an undecided state is denied silently, with no alert.
  - macOS 15+ has the same privacy control, per user account.
  - The Simulator does not support local network privacy.
- WWDC20 10110, Support local network privacy in your app (https://developer.apple.com/videos/play/wwdc2020/10110/). Read as a summary. Wait for a user action instead of touching the LAN at launch.
- `URLSessionConfiguration.waitsForConnectivity` (https://developer.apple.com/documentation/foundation/urlsessionconfiguration/waitsforconnectivity). Read.
- Networking Overview, "Why Networking Is Hard" (https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/NetworkingOverview/WhyNetworkingIsHard/WhyNetworkingIsHard.html). Read; an archived document.
  - Do not preflight reachability; always attempt the connection.
  - Retry automatically when the host is back, and dismiss the error by itself.
  - Show no dialogs for background failures.
  - Back off between retries, up to about 15 minutes.

**Android**
- Request runtime permissions (https://developer.android.com/training/permissions/requesting). Read.
  - Ask in context.
  - Show a rationale when `shouldShowRequestPermissionRationale` is true.
  - Two denials are permanent.
  - Degrade gracefully and say exactly which feature is off. Don't block the UI.
  - "Don't link to system settings in an effort to convince the user to change their decision."
- Local network permission (https://developer.android.com/privacy-and-security/local-network-permission). Read.
  - `ACCESS_LOCAL_NETWORK` is in the `NEARBY_DEVICES` group, and is enforced for target 37 on Android 17.
  - Denied TCP typically times out.
  - `NsdManager` with `FLAG_SHOW_PICKER` gives a user-picked device's address without the permission.
- Reading network state (https://developer.android.com/develop/connectivity/network-ops/reading-network-state). Read.
  - Use `registerDefaultNetworkCallback`.
  - A network you can see is not necessarily one you can use.
  - `NET_CAPABILITY_VALIDATED` tells whether it really reaches the internet.
- Build offline-first apps (https://developer.android.com/topic/architecture/data-layer/offline-first). Read.
  - The local source is the truth.
  - Retry with backoff only for connectivity errors, never for 401.
- Material.
  - M3 snackbar and banner guidance is unverified: the site rendered no text.
  - Material 1, Offline states (https://m1.material.io/patterns/offline-states.html). Read; no longer maintained. Load something rather than nothing, show offline status, and mark which features are unavailable.

**Windows**
- InfoBar (https://learn.microsoft.com/en-us/windows/apps/design/controls/infobar). Read.
  - "Internet connectivity lost" is the canonical example of an inline, non-intrusive bar that closes when the status resolves.
  - It can carry an action button. It should not flash in and out.
  - Use a dialog only when the problem blocks the whole app.
- `NetworkInformation.NetworkStatusChanged` (https://learn.microsoft.com/en-us/uwp/api/windows.networking.connectivity.networkinformation.networkstatuschanged). Read. It is a WinRT event, reachable from the JVM only through interop.

**Web**
- MDN, `navigator.onLine` (https://developer.mozilla.org/en-US/docs/Web/API/Navigator/onLine). Read. It is "inherently unreliable"; use it as a hint only.
- MDN, Network Information API (https://developer.mozilla.org/en-US/docs/Web/API/Network_Information_API). Read. Limited availability.
- MDN, Page Visibility API (https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API). Read. Use it to stop polling while hidden.
- MDN, Mixed content (https://developer.mozilla.org/en-US/docs/Web/Security/Mixed_content). Read.
- Chrome, Local Network Access (https://developer.chrome.com/blog/local-network-access). Read.
  - The prompt launched in Chrome 142.
  - A private IP literal, a `.local` name, or a `fetch` with `targetAddressSpace: "local"` is exempt from the mixed-content block once the user allows it.
  - A public name that resolves to the LAN is not exempt.
  - Firefox's and Safari's behaviour is unverified here; see platform-notes.

### Compared with the app today

| Convention | App now | Verdict |
|---|---|---|
| Don't preflight; attempt, then diagnose (Apple, Android, MDN) | Roaming probes the saved server's own identity endpoint and never checks "is there internet". Signals only say "something changed". | Matches. |
| Retry automatically with backoff, and recover without user action (Apple, Android offline-first) | Retries every 15 s, doubling to 5 min, restarted by network changes and resumes. The address comes back without a reload (browser final run). | Matches. Apple's ceiling is about 15 min; 5 min is fine for a media app. |
| Keep content visible; mark the server offline, don't remove features (Material 1, Android) | `Capabilities` keeps features visible and sets `reachable = false`. | Matches in the model. It is incomplete for Stash and Seerr (N5), and can show offline while requests succeed (N6). |
| Tell a denied local network permission apart from an unreachable server (TN3179, Android LNP) | Android does this through `LocalNetworkAccess` and a hint on a timeout. Apple is `NotNeeded`, so a local network denial on iOS or macOS reads as "Offline". | Gap on Apple. |
| Ask in context, explain first, one pre-alert screen with "Continue" (HIG, Android) | Android asks on "Add server" and from Home for saved local servers, after the app's own notice with **Continue**. Nothing asks at launch. | Matches. N1 makes it ask when nothing needs it. |
| Don't re-prompt after a denial; no persuasive settings links (Android) | After **Don't allow**, screens wait for **Ask again**. In `Blocked`, they offer **Open settings** and **Try again**. | Mostly matches. The settings link answers the user's own **Try again** or **Ask again** path, which Android tolerates as a way back. Keep it from appearing unprompted. |
| No error UI for background work (Apple) | Requests made while suspended wait instead of failing. | Matches, at the cost of N2. |
| Pause polling while hidden (Page Visibility) | The web source has no `Suspended`. Periodic checks keep running in hidden tabs. | Small gap. |
| An inline status bar that does not flash (InfoBar) | A burst of signals clears and re-proves the address within ms to 1 s (settle). The UI side is outside this area. | Model allows it. The UI should debounce `reachable`. |

### Recommendations

1. **[Apple] Implement `LocalNetworkAccess` for iOS and macOS 15+.**
   - Read `.localNetworkDenied` from the path of a probe's connection, or from `nw_path` (TN3179), and report `Blocked`.
   - Add `NSLocalNetworkUsageDescription` with a specific sentence.
   - Explain in Settings > Privacy & Security > Local Network.
   - Do this on a device: the Simulator cannot test it.
2. **[Apple] Expect the first local probe to be denied at once while the alert shows.** Re-run the check when the app becomes active again, or a few seconds later, instead of waiting 15 s for the retry. Platform-notes saw this on macOS.
3. **[Android] Ask only when an address really needs local network access** (N1).
   - Consider `NsdManager` with `FLAG_SHOW_PICKER` for discovery that needs no permission (verify that it fits Jellyfin's UDP discovery first).
4. **[All] Make offline states truthful.**
   - `reachable = true` after any successful request.
   - Stash and Seerr report reachability (N5, N6).
   - The UI shows a non-blocking, inline banner ("Can't reach <server name>", with Retry), keeps cached content, and clears the banner by itself.
   - On Windows desktop this is the InfoBar pattern; on Android a banner or snackbar (M3 guidance unverified); on Apple an inline status, not an alert.
5. **[All] Debounce the visible offline state** for about a second, matching `networkSettles`, so a burst of path updates does not flash a banner (InfoBar "don't flash").
6. **[iOS, tvOS, Android] Define background behaviour before playback.** Progress reports during background audio or PiP must not wait for the foreground (N2). Apple's guidance is not to show errors for background failures, but the work itself must still run.
7. **[Web] Use the Page Visibility API.** Treat `hidden` like a soft suspension: skip periodic checks, and check at once on `visible`.
8. **[Web] Document the hosting rule.** An https deployment reaches a LAN server only by its IP literal or a `.local` name, after Chrome's Local Network Access prompt. An internet name with split DNS stays blocked. Show this hint when a LAN address fails from an https origin.
9. **[Desktop] Optional:** a native network-change event (`NetworkStatusChanged` on Windows) would replace 5 s polling. It needs interop or a dependency, which needs approval. Measure the polling cost first.

## Seen outside my area
- `data:jellyfin` tests use `FirstAddressSources` from production `commonMain` (N15). A test-fixtures home would serve them.
- `feature:home`'s `HomeLocalNetworkViewModel` and `feature:settings`'s `LocalNetworkPromptViewModel` both call `LocalNetworkAccess.request()`. That is the precondition for N7, and both inherit N1.
- `NetworkLogger` has no binding in `shared` or `web` (N3). The fix belongs to the shells.
