# Review: data, playback probes and the presentation layer (2026-10-21)

Area: `core/database`, `data/jellyfin`, `core/playback`, `feature/home`, `feature/settings`, and the placeholder modules `data/seerr`,
`data/stash`, `feature/library`, `feature/requests`, `feature/player`. I reviewed the code on `main` at 6fb1957 by reading it. Gradle was
not run. Line numbers point at that commit.

## 1. Features in this area

| Feature | What it does | ADR | Built in | Checked on (docs/platform-notes.md, PR descriptions) |
|---|---|---|---|---|
| Settings store (`KeyValueStore`) | Small string settings that survive a restart: the saved servers and the signed-in user's name. Desktop uses a JSON file in the config folder, web uses `localStorage`, Android uses `SharedPreferences`, Apple uses `NSUserDefaults`. | 0016 | #17 | Desktop on Windows 11 (folder layout, no folder made until the first write). The JSON file on JVM in tests. The web store ran in Chrome through the web shell (#37: a reload stays signed in or signed out). Android ran on the emulator as part of #35/#36, though nothing checked the store itself. Apple was only compiled in CI. |
| Machine-local store (`MachineLocal`) | Values that must not roam or be restored on another device, such as the device id. Desktop uses `%LOCALAPPDATA%` or `$XDG_STATE_HOME`. Every other platform uses its settings store under a separate name or prefix. | 0017 | #18, made public in #27 | Desktop in JVM tests. Elsewhere only compiled. The Android backup exclusion and Apple's "this device only" Keychain item are deferred to M4. |
| Secrets (`SecretStore`) | Tokens. Windows uses Credential Manager; Linux uses the Secret Service (libsecret over JNA) with a 2-minute limit per call. With no keyring, secrets stay in memory, or go to an owner-only file if the user chooses that. Web uses `localStorage`. Android and Apple use memory until M4. `storage` tells the UI which store is in use. | 0017 | #18 | Credential Manager round trip on Windows 11 with JDK 21. libsecret with GNOME Keyring under WSL Ubuntu 24.04, including the locked-keyring timeout and the `rw-------` file. Not checked: KWallet, a real desktop unlock prompt, Flatpak or Snap. The web store ran in Chrome (#37). |
| Device identity (`DeviceIdentity`) | A random device id made once per install and kept machine-local, plus a device name for server dashboards: the computer name on desktop, "Chrome on Windows" on web, the model on Android, `UIDevice.name` or the Sharing name on Apple. No DNS lookup is made, which matters on Apple. | 0017, 0018 | #18, #34 | Desktop and web through sign-in runs. Apple was only compiled: per #34, the device checks wait for the first PR that starts Koin on Apple. |
| Jellyfin server probe | `GET /System/Info/Public` without credentials returns the server id, name and version. Add server and roaming both use it. | 0016, 0019 | #17, #33 | Recorded shapes in tests (#17). Live over HTTPS against 12.2 on 2026-10-20 (`LiveJellyfinSmokeTest`). Through the web shell in Chrome against a stand-in (#37). On the Android emulator against a stand-in (#35, #36). |
| Jellyfin sign-in, Quick Connect, sign-out, restore (`JellyfinAccounts`) | Password sign-in and Quick Connect (a code approved in another app). The token goes to the secret store and the user to settings. At start, saved sessions are put into `CapabilitiesState` and then checked with `Users/Me`. Any 401 signs the server out. Sign-out ends the session on the server. A server that answers with another id is refused. | 0018 | #19, #20 | Live on 2026-10-20 against 12.2 (password and Quick Connect, restore, logout followed by 401), from the JVM. Desktop under Xvfb against a stand-in (#24); Windows itself was not run there. Web in headless Chrome against a stand-in, 49/49 checks, plus the owner's hand check against their own server (#37). Never on Android or Apple: Apple has no UI, and the Android runs were signed out. |
| Browser probes and the web device profile | About 200 runtime queries (`canPlayType`, `MediaSource.isTypeSupported`, `decodingInfo`, `matchMedia`, the channel count) are turned into `MediaSupport`, and from that into a Jellyfin-shaped `DeviceProfile`: direct play, fMP4 HLS, bit depth, range type, resolution, subtitles. | 0007, 0011 | #6 | Fixtures recorded in Chrome 155, Edge 155, Firefox 158 and Safari (macOS, 2026-10-19). Dolby Vision probes have no recorded "yes". The Dolby Vision base-layer fallback has never been played. |
| Home (`HomeViewModel`) | Shows no server, signed out (offering sign-in to the first Jellyfin server), or signed in as whom, from `ServerRepository` and `CapabilitiesState`. | 0014, 0018 | #24 | Desktop (Xvfb), web in Chrome (#37) and the Android emulator. |
| Home local network notice (`HomeLocalNetworkViewModel`) | On Android, explains and then asks for "Nearby devices" when a saved server has a local address. It shows a denial, and a Blocked state that points to the app settings. | 0020 | #36 | Android emulator, Android 17: cases 1 to 6 passed. Not run: a dismissed dialog, Try again, and Open settings from Blocked. |
| Add server and add address (`ServerSetupViewModel`) | Tries every candidate URL at once (https first, then the default port). It shows the found server with a warning for plain http on the internet, refuses duplicates, and when adding an address checks that the same server answers there. | 0016, 0019 | #17, #23 | Desktop, web in Chrome, the Android emulator (#35, #36). |
| Local network prompt on Add server (`LocalNetworkPromptViewModel`) | Asks before the first check. After a timeout while access is denied, it points to the permission, and it retries when a grant arrives. | 0020 | #27, #36 | Pixel 8 Pro and emulator, Android 17. Not run: the Blocked notice on Add server, the Tailscale/VPN case. |
| Sign-in page (`SignInViewModel`) | The username/password form, a Quick Connect button when the server has it enabled, the code screen, and typed problems. | 0018 | #19 | Desktop (Xvfb, stand-in) and web in Chrome (stand-in, plus the owner's own server). |
| Servers list (`ServersViewModel`) | Saved servers with who is signed in and whether each is reachable, plus sign-out, remove, where tokens are kept, and the "keep in a file" offer. | 0017, 0018 | #19, #24 | Desktop (Xvfb), web in Chrome. On the Android emulator it showed the misleading "No credential store was found" note (#35). |
| Server addresses (`ServerAddressesViewModel`) | A server's addresses in priority order, with the one in use, the http-on-internet flag, reorder and remove (always keeping at least one). | 0019 | #23, #35 | Android emulator (#35, all steps pass apart from phone-width layout). The Windows keyboard pass is still pending. |
| Playback support (`PlaybackSupportViewModel`) | Settings > Playback support shows what this device plays. The web runs the probes once per page load. | 0007, 0011 | #6 | Web (Chrome, Edge, Firefox, Safari) via the Copy button that produced the fixtures. |
| Placeholders | `data/seerr` and `data/stash` hold only a `build.gradle.kts` (depending on `core:model` and `core:network`) with no sources. `feature/library` and `feature/requests` have only a build file depending on `core:model`. `feature/player` has only a build file depending on `core:model` and `core:playback`. None has sources or tests. | — | Initial commit | — |

## 2. File map

All paths start at `src/` of their module. Package directories (`kotlin/com/skeksik/current/...`) are left out.

### core/database
| File | What it holds | Feature |
|---|---|---|
| commonMain/KeyValueStore.kt | `KeyValueStore` interface; `InMemoryKeyValueStore` | Settings store |
| commonMain/SecretStore.kt | `SecretStorage` enum; `SecretStore` interface (with `fileOffered`/`keepInFile`); `InMemorySecretStore` | Secrets |
| commonMain/DeviceIdentity.kt | `DeviceIdentity` and `load` (random 128-bit hex id kept in the local store) | Device identity |
| commonMain/BrowserDeviceName.kt | User agent to "Browser on OS" | Device identity (web) |
| commonMain/DatabaseModule.kt | `MachineLocal` qualifier, `databaseModule`, the `expect` factories | All |
| androidMain/SharedPreferencesKeyValueStore.kt | Android `actual`s: `SharedPreferences` stores, memory secrets, the device name | Settings, local store, secrets, identity |
| appleMain/UserDefaultsKeyValueStore.kt | Apple `actual`s: `NSUserDefaults` stores, memory secrets | Settings, local store, secrets |
| iosMain/DeviceName.kt | `UIDevice.name` | Device identity |
| tvosMain/DeviceName.kt | `UIDevice.name` (same file as iOS) | Device identity |
| macosMain/DeviceName.kt | `SCDynamicStoreCopyComputerName` | Device identity |
| jsMain/LocalStorageKeyValueStore.kt | `localStorage` stores (settings, local, `LocalStorageSecretStore`), user agent | Settings, local store, secrets, identity |
| jvmMain/AppFolders.kt | Config and local folders for Windows, XDG and macOS | Settings, local store, secrets |
| jvmMain/DesktopStores.kt | JVM `actual`s: JSON stores, `DesktopSecretStore.create`, the computer name | All (desktop) |
| jvmMain/JsonFileKeyValueStore.kt | One JSON file with atomic replace, set aside when unreadable, owner-only option | Settings, local store, secrets file |
| jvmMain/DesktopSecretStore.kt | `SecretBackend`; keyring, then file, then memory, with fallback for the run | Secrets |
| jvmMain/LibSecret.kt | JNA binding to libsecret/GLib, the worker thread with a timeout | Secrets (Linux) |
| jvmMain/WindowsCredentials.kt | JNA binding to `CredReadW`/`CredWriteW`/`CredDeleteW` | Secrets (Windows) |
| commonTest/DeviceIdentityTest.kt | Id made once and kept; blank name; browser names | Device identity |
| jvmTest/DesktopSecretStoreTest.kt | Keyring, failure fallback, file opt-in, owner-only permissions | Secrets |
| jvmTest/JsonFileKeyValueStoreTest.kt | Round trip, no temp files left, unreadable file set aside | Settings store |
| jvmTest/KeyringTest.kt | Real Windows/Linux keyrings, gated by `CURRENT_TEST_SECRET_SERVICE` | Secrets |

### data/jellyfin
| File | What it holds | Feature |
|---|---|---|
| commonMain/AuthDtos.kt | Internal DTOs for sign-in and Quick Connect | Sign-in |
| commonMain/JellyfinAccounts.kt | `JellyfinAccounts` interface, `QuickConnectRequest`, `SignInFailure`/`SignInException`, `StoredJellyfinAccounts` | Sign-in, Quick Connect, restore, sign-out |
| commonMain/JellyfinAuthorization.kt | The `MediaBrowser ...` Authorization header | Sign-in, every request |
| commonMain/JellyfinServerProbe.kt | `ServerProbe` for `/System/Info/Public` | Probe |
| commonMain/JellyfinModule.kt | Koin: probe, accounts (created at start, restore launched) | All |
| jvmTest/JellyfinServerProbeTest.kt | Id, web page, redirect, proxy login page, cancellation log | Probe |
| jvmTest/JellyfinAccountsTest.kt | Header, password, wrong credentials, different server, Quick Connect, sign-out offline, restore, 401 | Sign-in |
| jvmTest/KoinGraphTest.kt | App modules resolve; qualifiers kept | Wiring |
| jvmTest/LiveJellyfinSmokeTest.kt | Opt-in live test (`CURRENT_LIVE_JELLYFIN_URL`), never in CI | Probe, sign-in |

### core/playback
| File | What it holds | Feature |
|---|---|---|
| commonMain/DeviceProfile.kt | `DeviceProfile` and its parts in Jellyfin's shape | Web device profile (later every engine) |
| commonMain/VideoRanges.kt | `RangeSupport`; `VideoRanges.rangeTypes` (Jellyfin `VideoRangeType`s) | Device profile |
| commonMain/web/BrowserProbes.kt | `ProbeQuery`, `MediaSupport`, the probe table and `interpret` | Browser probes |
| commonMain/web/WebDeviceProfile.kt | `MediaSupport` to `DeviceProfile` | Web device profile |
| jsMain/web/ProbeRunner.kt | Runs the queries in the page (`js(...)`), `probeMediaSupport()` | Browser probes |
| commonTest/VideoRangesTest.kt | Range types per support | Device profile |
| jvmTest/web/BrowserProbesTest.kt | Fixture-driven tests of `interpret` and `WebDeviceProfile` (classes `BrowserProbesTest`, `WebDeviceProfileTest`) | Probes, profile |

### feature/home
| File | What it holds | Feature |
|---|---|---|
| commonMain/HomeViewModel.kt | `HomeUiState`, `HomeViewModel` | Home |
| commonMain/HomeLocalNetworkViewModel.kt | `HomeLocalNetworkState`, `HomeLocalNetworkViewModel` | Home local network notice |
| jvmTest/HomeViewModelTest.kt | States, restored first value, Jellyfin first | Home |
| jvmTest/HomeLocalNetworkViewModelTest.kt | Internet-only, ask, deny, blocked, resume | Home local network notice |

### feature/settings
| File | What it holds | Feature |
|---|---|---|
| commonMain/ServerSetupViewModel.kt | `ServerSetupUiState`, `ServerSetupProblem`, `ServerSetupViewModel` | Add server / add address |
| commonMain/LocalNetworkPromptViewModel.kt | `LocalNetworkPromptState`, the ViewModel, `mayExplain` | Local network prompt |
| commonMain/SignInViewModel.kt | `SignInUiState`, `SignInProblem`, `SignInViewModel` | Sign-in page |
| commonMain/ServersViewModel.kt | `ServersUiState`, `ServerRow`, `ServersViewModel` | Servers list |
| commonMain/ServerAddressesViewModel.kt | `ServerAddressesUiState`, `AddressRow`, the ViewModel | Server addresses |
| commonMain/PlaybackSupportViewModel.kt | `PlaybackSupportUiState`, the ViewModel | Playback support |
| jvmTest/ServerSetupViewModelTest.kt | https wins, hanging candidate, problems, duplicates, add address | Add server |
| jvmTest/LocalNetworkPromptViewModelTest.kt | Ask-then-check, denial, blocked, grant retries | Local network prompt |
| jvmTest/SignInViewModelTest.kt | Quick Connect offered, password, problems, code, expiry, cancel. `ServersViewModelTest` (rows, remove signs out first) is in this file too. | Sign-in, servers list |
| jvmTest/ServerAddressesViewModelTest.kt | Order, in use, move, remove, reachability | Server addresses |
| jvmTest/PlaybackSupportViewModelTest.kt | Probing, then Ready | Playback support |

## 3. Unverified parts, per feature

**Settings store.**
- Android `SharedPreferences` and Apple `NSUserDefaults` were never tested; #17 says "The Android and web stores at runtime (they compile)". Android stores ran incidentally on the emulator, but nothing checked persistence across a restart.
- No test of the JS `localStorage` store when storage is blocked (private mode), which its KDoc promises to survive.
- `JsonFileKeyValueStore` was not tested with a file that is not UTF-8, or one it cannot read (see C7).
- Whether an MSIX package redirects `%APPDATA%` (platform notes).

**Machine-local store.** On Android it is not excluded from backup, and on Apple it lives in `NSUserDefaults`, which is backed up and restored. So a restored phone reuses the device id and two devices sign each other out (ADR 0017 accepts this until M4). Nothing tests that the local store and the settings store stay apart on Android, Apple or web (separate file or prefix).

**Secrets.**
- Android and Apple: memory only, so the user signs in again after every launch. On Android the UI then says "No credential store was found" (#35).
- KWallet, a real desktop unlock prompt, Flatpak and Snap were not verified.
- The keyring test runs only with an environment flag. #18 says the CI keyring step was new and untested at the time.
- Not tested: `remove` or `get` after a keyring failure (see C4), and `WindowsCredentials.put` over 2560 bytes from `DesktopSecretStore` (see C14).

**Device identity.**
- Apple: never run. Per #34 the checks are deferred until Koin starts on Apple: the name `UIDevice.name` gives on iOS and tvOS, whether `SCDynamicStoreCopyComputerName` stays local on macOS 15+, and no watchdog kill on a first launch.
- `browserDeviceName` is tested only for the user agents in `DeviceIdentityTest`. Edge and Firefox on iOS and iPadOS desktop-mode agents are not covered (see C10).

**Jellyfin probe.** Covered live on 2026-10-20. Not verified: the HTTPS redirect status code, and which exceptions the Android, Darwin and JS Ktor engines throw for a refused connection, unknown host or TLS failure (platform notes, "Not verified"), which feed the problem shown on Add server.

**Jellyfin sign-in.**
- Never run on Android or Apple.
- Desktop ran only under Xvfb against a stand-in (#24); the live runs were JVM tests.
- No test for:
  - a 401 from an old token arriving after a new sign-in (C1);
  - a transient failure during Quick Connect polling (C2);
  - restore with a token but no stored user;
  - the server-id check when the answer has no `ServerId` (it is accepted then).
- `JellyfinAccountsTest` is JVM-only, so the JS and native coroutine and Ktor paths are untested.
- The KDoc's "10 minutes" matches the platform notes, which were read in the source but not timed live.

**Browser probes and web device profile.**
- A Dolby Vision base layer was never played (`baseLayerFallback = true` is an assumption, recorded in "Not verified").
- No recorded browser says yes to the Dolby Vision probes.
- HDR on screen was not checked.
- `ProbeRunner.kt` has no test: it is JS-only and CI runs JVM tests.
- The profile was never sent as `PlaybackInfo` by this code (that happened in the web spike), and nothing in the app consumes the profile yet (M2).
- The probes run once per page load. Moving between an HDR and an SDR monitor is not handled (ADR 0011, an M2 follow-up).

**Home.** Not tested: the state while restore is still running on a slow keyring. The first frame reads `capabilities.value` before `restore()` has put the session in, if `secrets.get` suspends (it blocks on a keyring), so Home can flash "signed out". The KDoc claims the first frame shows the restored session. That holds when the keyring answers fast, which was not measured.

**Home local network notice.** From the platform notes: the dismissed dialog, Try again and Open settings from Blocked were not run.

**Add server.**
- The plain-http-on-internet warning was not run on Android (#35).
- The Windows keyboard pass is pending (#35).
- Not verified: an internet name that resolves to a local address while denied (#38).

**Local network prompt.** Not run: the Blocked notice on Add server, Tailscale/VPN, and a denial in the app's own dialog (#38).

**Sign-in page.**
- Not run on Windows itself, Android or Apple.
- Not tested: tapping Sign in while Quick Connect is starting (C3), and leaving the page during Quick Connect (the job is cancelled with `viewModelScope`; fine, but untested).

**Servers list.**
- Not tested: remove of an offline server, and leaving the page while it runs (C5).
- `keepSecretsInFile` was not tested through the ViewModel.

**Server addresses.** The Windows keyboard pass is pending. The phone-width layout is broken (composeUi, #35, deferred to M4).

**Playback support.** Only the JVM test with a fixture. Nothing on native platforms, which have no probe yet.

**Repeated across PRs.** `./scripts/check.sh` was not run, or did not fully pass, in the worker environment for #23, #24, #27, #33, #34, #35, #36, #37 and #38 (Maven Central 429, a yarn download refused by the proxy, or no Android SDK). Those PRs relied on CI. Apple code in this area was built only in CI.

## 4. Code review

Severity: **must-fix** = wrong behaviour users will hit; **should-fix** = a real defect with a narrower trigger, or a misleading contract;
**nit** = cleanup. Confidence is about the defect, not its frequency. I checked each finding against the code; scenarios are traced
through the named lines.

### C1. A stale 401 signs out a fresh session — should-fix, high confidence
`data/jellyfin/.../JellyfinAccounts.kt:238` and `:227-232`

**What's wrong.** `clientFor` gives every authorized client `onUnauthorized = { scope.launch { forget(server.ref) } }`. `forget` drops whatever token is current for the server, not the token that got the 401. ADR 0018 notes that Jellyfin revokes a token when the same device id signs in again, so every request still in flight with the old token is certain to get a 401 after a new sign-in.

**Scenario.**
1. At start, `restore()` finds a token but no readable stored user (`:104`, `:110`), so Home shows signed out.
2. `verify()` with the old token is waiting on the roaming address (`awaitCurrent`) or a slow server.
3. The user signs in. The server revokes the old token, and `verify` gets a 401.
4. `forget` removes the new token from memory, the keyring and settings. The user is signed out right after signing in.

**Why it gets worse.** In M2 the same happens to any library request started before a re-sign-in.

**Fix.** Compare and forget: `forget(server, token)` that acts only if `tokens.value[server] == token`, and the same for the secret store.

### C2. One network blip ends Quick Connect — should-fix, high confidence
`JellyfinAccounts.kt:158-161`, `:174-178`

**What's wrong.** `poll` rethrows every `ServiceException` except 404. A single `Timeout`, `Unreachable` or 5xx during the up-to-10-minute wait ends `finishQuickConnect`.

**Scenario.**
1. On a phone, the Wi-Fi to mobile handover happens while the user walks to the TV to approve the code (roaming reports the address unreachable, `ServerClient.request` `:39`).
2. `SignInViewModel` goes back to the form with "Unreachable".
3. The user may already have approved the code on the other device, but has to start again and approve a new code.

**Fix.** Treat `Unreachable`, `Timeout` and 5xx as "not yet" inside the loop. Keep 404 as expired and let the 10-minute limit end it.

### C3. Sign in and Quick Connect race; cancel stops the wrong job — should-fix, high confidence
`feature/settings/.../SignInViewModel.kt:83-113`, `:116-120`

**What's wrong.** `startQuickConnect` leaves the state `Form(working = false)` until the code arrives, so `signIn()` is still allowed. `signIn()` then overwrites `work` without cancelling the Quick Connect job.

**Scenario.**
1. The user presses Quick Connect, then Sign in before the code shows (or presses Enter in the password field).
2. Both jobs run. If the password sign-in finishes first, the Quick Connect job later sets `QuickConnect(code)`, replacing `SignedIn`.
3. `cancelQuickConnect` then cancels the finished password job (the current `work`), not the poll. The poll keeps running for up to 10 minutes and can finish with `SignedIn` or an error form on a page the user has left. On desktop the ViewModel is held until the page goes away.

**Fix.** Use one job: cancel `work` in `signIn()`, and mark the form `working` while Quick Connect starts.

### C4. After a keyring failure, sign-out leaves the token in the keyring — should-fix, high confidence
`core/database/.../DesktopSecretStore.kt:52`, `:66-68`, `:81-86`, `:90-95`

**What's wrong.** One failed keyring call turns `_storage` to `Memory`, and then `usingKeyring` is false for the rest of the run. After that, `get` never reads the keyring and `remove` never deletes from it.

**Scenario.**
1. On Linux, the keyring daemon prompts to unlock at start. The user dismisses it, or it times out after 2 minutes, while `restore()` reads a token (`keyringOrNull` sets Memory).
2. Every saved token is now invisible, so the user looks signed out.
3. The user signs out or removes the server. The real token stays in the keyring.
4. If the logout did not reach the server (offline), the token is still valid. At the next start, `restore()` finds it and signs the user back in, against what they asked for.

**Fix.** Always try `remove` on the keyring, best effort. Consider whether one dismissed prompt should switch to memory for the whole run, or only fail that call.

### C5. Removing a server waits on the network inside `viewModelScope` — should-fix, medium confidence
`feature/settings/.../ServersViewModel.kt:73-78`, together with `JellyfinAccounts.kt:180-185`, `:208-216`

**What's wrong.** `remove` first runs `accounts.signOut`. That call waits for an address (`awaitCurrent`), then up to 5 s for `Sessions/Logout`, and only after that calls `forget` and `servers.remove`. If the ViewModel is cleared in between (the user leaves Settings on web or desktop), the `CancellationException` passes through `endSession`, which catches only `ServiceException`. Then neither the token nor the server is removed.

**Scenario.** The server is offline. The user confirms Remove and leaves the page at once. The server is still listed later, still signed in.

**Also seen.** Even without leaving, the row stays for seconds after the user confirmed. The same delay applies to `signOut`, so the UI keeps showing "signed in" during it.

**Fix.** Forget locally first (token, user, capabilities, saved server). Then end the server session best-effort in the app scope (`AppScope`), with the old token captured.

### C6. The KDoc promises a Content-Security-Policy that does not exist — should-fix, high confidence
`core/database/src/jsMain/.../LocalStorageKeyValueStore.kt:40-43`

**What's wrong.** The KDoc says "A strict Content-Security-Policy keeps foreign scripts out". `webApp/index.html` has no CSP, and #37 says it was "left for the deploy work". Tokens sit in `localStorage` under `current.secret.`, readable by any script on the origin.

**Scenario.** A developer reads the KDoc and assumes the mitigation is in place. A self-hosted deployment on a shared origin (served next to other apps) exposes tokens to those apps.

**Fix.** Reword the KDoc to say the CSP is still to come, and track adding the CSP as a deploy task.

### C7. A settings file that is not UTF-8 stops the desktop app at start — should-fix, medium confidence
`core/database/src/jvmMain/.../JsonFileKeyValueStore.kt:46-55`

**What's wrong.** `Files.readString` throws `MalformedInputException`, an `IOException`, for bytes that are not UTF-8. Only `SerializationException` and `IllegalArgumentException` are set aside, so the KDoc's promise ("a file that does not parse is kept as `.unreadable` and the store starts empty, so the app still starts") fails for byte-level damage. The same goes for a file the process cannot read.

**Scenario.** `settings.json` is saved as UTF-16 by a text editor, or truncated mid-character by a full disk on another tool. The Koin `single` throws while the app starts.

**Fix.** Also set aside on `CharacterCodingException`, and add a test.

### C8. Dead `keys()`, with a misleading KDoc — nit, high confidence
`JsonFileKeyValueStore.kt:41-43`

`keys()` has no caller (checked with grep over the repo). Its KDoc says it is "for moving values to another store", but `keepInFile` moves values from memory. Remove it.

### C9. Production code with only test callers — nit, high confidence
`ServerAddressesViewModel.kt:83-85`

`move(from, to)` is used only by tests. The desktop, Android and web UIs use `moveUp` and `moveDown`. Keep it if drag-to-reorder is planned; otherwise drop it and test `moveUp`/`moveDown` instead.

### C10. `browserDeviceName` KDoc and iOS browsers — nit, medium confidence
`core/database/.../BrowserDeviceName.kt:3`, `:6-8`

**What's wrong.** The KDoc says Edge and Chrome on iOS "say Safari too, so their checks come first". Chrome on iOS is handled (`CriOS/`), but Edge on iOS identifies itself as `EdgiOS/`, which does not contain `Edg/`, and Firefox on iOS as `FxiOS/`. Both come out as "Safari on iOS". iPadOS Safari in its default desktop mode sends a Mac user agent and comes out as "Safari on macOS".

**Confidence.** The tokens are from public user-agent documentation and were not re-checked against the platform notes; this is unverified here.

**Impact.** Cosmetic, in a server dashboard. Add `EdgiOS/` and `FxiOS/`, and correct the comment.

### C11. tvOS KDoc copied from iOS — nit, high confidence
`core/database/src/tvosMain/.../DeviceName.kt:5-8`

**What's wrong.** The KDoc talks about "iPhone since iOS 16" and an app killed at launch. That was iOS, and #34 lists the tvOS name as unverified. The iOS and tvOS files are also byte-identical.

**Fix.** Say what is known for tvOS. Consider one UIKit-level `actual`, if the source-set hierarchy allows it.

### C12. The common ViewModel uses a web-only type behind a lambda — should-fix (design, before M2), high confidence
`feature/settings/.../PlaybackSupportViewModel.kt:5`, `:21`

**What's wrong.** The ViewModel takes `suspend () -> MediaSupport`, and `MediaSupport` lives in `core.playback.web`. It is common Kotlin, so this compiles everywhere, but every native engine will have to dress its capabilities up as browser probe answers. A bare function type also skips the CLAUDE.md rule that a ViewModel takes interfaces that are bound in Koin and can be replaced. The web facade passes a lambda (`web/.../CurrentWeb.kt:79`).

**Fix.** Before Media3, AVPlayer and libmpv land, add a `PlaybackCapabilities` interface in `core:playback` with a platform-neutral support model, bound per platform in Koin.

### C13. Two copies of the same local network state machine — nit, high confidence
`HomeLocalNetworkViewModel.kt:19-81` and `LocalNetworkPromptViewModel.kt:16-103`

Both define a four-state sealed type (Hidden, Ask(denied), Asking, Blocked) and the same `refresh`/`allow`/`openSettings` logic. They differ only in the ask reason and in the extra callbacks. A shared state type and helper would keep the two screens from drifting apart, for example in the wording of Blocked.

### C14. Uncaught `IllegalArgumentException` from the Windows store — nit, high confidence
`WindowsCredentials.kt:39` and `DesktopSecretStore.kt:90-95`

**What's wrong.** `put` throws `IllegalArgumentException` for a value over 2560 bytes. `keyringOrNull` catches only `SecretBackendException`, so the exception escapes to the caller (sign-in) instead of falling back to memory.

**Impact.** Latent: Jellyfin tokens are 32 hex characters. Seerr cookies or Stash API keys could be longer later.

**Fix.** Throw `SecretBackendException`, or fall back.

### C15. A saved token with no stored user shows signed out — nit, high confidence
`JellyfinAccounts.kt:104-111`

**What's wrong.** With a token but no stored user (the settings file was set aside, or `decodeUser` failed), the session is not marked signed in until `verify` succeeds. When the server is offline, the user sees "signed out" although the token is kept. Signing in again then revokes that token on the server.

**Fix.** Mark the session signed in with a placeholder user, or drop the token. Either is more consistent.

### C16. Misleading problem when the server was removed — nit, high confidence
`ServerSetupViewModel.kt:178-180`

If the server named in `addTo` was removed meanwhile (on another page or tab), the result is `DifferentServer` ("Another server answers at this address"). The sign-in model has `ServerNotSaved` for this case; Add address should report the same.

### C17. Two requests to start Quick Connect — nit, high confidence
`JellyfinAccounts.kt:145-150`

`startQuickConnect` calls `quickConnectEnabled` again, creating a second client and request; the platform notes saw this too. The 401 from `Initiate` already tells "disabled". Small.

### C18. Things that look wrong but are not

**`LocalNetworkPromptViewModel.kt:80` (`LocalNetworkPromptState.Ask() ->`).** This compares by data-class equality with `Ask(denied = false)`, which is intended.

**`ServersViewModel.kt:58` (`addresses.first()`).** Safe: `SavedServer` requires at least one address (`core/model/.../SavedServer.kt:15`).

**`ServerClient.request` and coroutine cancellation.** It rethrows `CancellationException` (`core/network/.../ServerClient.kt:33`). No code in my area catches `CancellationException`; every catch names a specific type.

**`BrowserProbes.interpret`'s `ok`.** It matches `id#n` spellings exactly: `"play:mp4,m4v:hevc#"` does not match `hevc10`.

### Conventions check
- **Dependency direction.** Correct. Features depend on `data` and `core` only. `feature:settings` uses `data:jellyfin` (`JellyfinAccounts`), which is allowed. No feature depends on another. No Compose in any pure-Kotlin module in this area; the ViewModels use `androidx.lifecycle`, which is multiplatform.
- **commonMain first.** Good. Platform code is limited to stores, keyrings, device names and the JS probe runner.
- **Interfaces in Koin.** Repositories and stores are interfaces bound in `databaseModule` and `jellyfinModule`. ViewModels take interfaces, apart from C12. The UI builds ViewModels with `get()` (`composeUi/.../AddServerScreen.kt:83`, `web/.../CurrentWeb.kt:65-79`) instead of Koin `viewModel` definitions, so replacing a ViewModel means editing two UIs. That is acceptable, but it is not "rebinding".
- **CapabilitiesState.** Read by Home, Servers and Addresses, and updated by sign-in, the probe and restore. Home offers sign-in only for Jellyfin, which is right while it is the only service with users.
- **Secrets and logging.** No token, password or host is logged in this area. Tokens go only to `SecretStore`. The Quick Connect secret is `internal`, and the request log redacts `secret=` (live check in the platform notes). The password stays in `SignInUiState.Form` until sign-in ends, which is normal for a form state.
- **Device id.** It uses `kotlin.random.Random` rather than a cryptographic source. It is not a secret, so this is acceptable, but `SecureRandom` or `crypto.randomUUID()` would rule out collisions from weak seeding on any platform (nit).

### Test quality
Strong overall:
- The names read as behaviour ("aHangingHttpsCandidateDoesNotBlockOnceItFails").
- Fakes are used instead of mocks.
- Fixtures were recorded from real browsers.
- There is an opt-in live test against a real server.

Gaps:
- All `data` and `feature` tests are JVM-only, so the JS and native coroutine and Ktor paths are untested.
- No race tests (C1, C3).
- No failure-path tests for `DesktopSecretStore.remove` after a failure (C4) or for a `JsonFileKeyValueStore` encoding error (C7).
- No test for `ProbeRunner` (JS).
- `ServersViewModelTest` lives inside `SignInViewModelTest.kt`, which makes it hard to find.

## 5. Code quality summary

The area is small, carefully written and well documented. Each KDoc says why, not only what. Platform traps found on real devices are written into the code next to the fix, for example the Apple `hostName` watchdog kill and the libsecret lookup that reports no error without a bus. Errors are typed end to end (`ServiceException` with kinds, `SignInFailure`, `ServerSetupProblem`), and the UI maps each to its own message. The probe-to-profile pipeline is deterministic and tested against fixtures recorded from four browsers. Secrets have a clear model: keyring, then an opt-in file, then memory, with `SecretStorage` shown to the user. No `CancellationException` is caught.

The main problems are concurrency at the edges of the session lifecycle:
- A 401 from an old token signs out a new session (C1).
- Quick Connect does not survive a network blip (C2).
- The sign-in page can run two jobs at once (C3).
- Removal depends on a network call finishing inside a ViewModel scope (C5).

On the desktop, a single keyring failure makes sign-out leave the token behind (C4). The rest is documentation drift (C6, C10, C11), a little dead code, and one design point to settle before native playback lands (C12). Android and Apple keep tokens only in memory until M4. Most user-visible flows have run only on desktop under Xvfb, in headless Chrome and on the Android emulator. None has run on Apple.

## 6. Improvements (ranked)

| # | Title | Why | Modules | Effort | Priority |
|---|---|---|---|---|---|
| 1 | Forget only the token that got the 401 | C1: a new session is signed out by a stale request; gets worse in M2 | data/jellyfin | S | High |
| 2 | One job on the sign-in page | C3: overlapping sign-in and Quick Connect, cancel hits the wrong job | feature/settings | S | High |
| 3 | Quick Connect keeps polling through transient failures | C2: network blips during approval end the flow | data/jellyfin | S | High |
| 4 | Sign-out and remove act locally first, end the server session in the app scope | C5: offline remove is slow and can be lost when the page closes | data/jellyfin, feature/settings | S | High |
| 5 | Keyring remove is always attempted after a failure | C4: token left in the keyring after sign-out; possible sign-in again at next start | core/database | S | High |
| 6 | Content-Security-Policy for the web app, and fix the KDoc | C6: tokens in `localStorage` without the promised mitigation | webApp (deploy config), core/database (KDoc) | M | High (before any public deploy) |
| 7 | Set aside unreadable or badly encoded settings files | C7: the desktop app fails to start | core/database | S | Medium |
| 8 | Keystore and Keychain secret stores, and keep the local store out of backups | Android and Apple sign in again after every launch; restored devices share an id (ADR 0017 M4) | core/database | L | Medium (M4) |
| 9 | Platform-neutral `PlaybackCapabilities` interface | C12: native engines should not fake browser probe answers | core/playback, feature/settings, web | M | Medium (before M2 native engines) |
| 10 | Shared local network ask state | C13: two copies of the same state machine | feature/home, feature/settings (or core/network) | S | Low |
| 11 | Test the JS and native paths of data and feature code | All tests are JVM-only; JS stores and probes are untested | build (`jsNodeTest`/`iosSimulatorArm64Test`), tests | M | Low |
| 12 | Docs and dead-code cleanup | C8-C11, C14-C17 | core/database, data/jellyfin, feature/settings | S | Low |

## 7. Platform conventions and UX research

All sources were read on 2026-10-21; Apple pages were read as developer.apple.com JSON. "UNVERIFIED" marks what I could not confirm
from a primary source. Where I describe the app, I mean the UI that renders this area's ViewModels (composeUi and webApp). Changes
there belong to those areas, but the behaviour is driven from here.

### 7.1 Password-manager autofill

**Sources**
- Android Autofill for Views: https://developer.android.com/identity/autofill/autofill-optimize
  - Use `android:autofillHints` with `AUTOFILL_HINT_USERNAME` / `AUTOFILL_HINT_PASSWORD`.
  - Never set `importantForAutofill="no"` on credential fields.
  - Call `AutofillManager.commit()` after the last step.
- Compose autofill: https://developer.android.com/develop/ui/compose/text/autofill and the `ContentType` reference
  - `Modifier.semantics { contentType = ContentType.Username }` / `ContentType.Password`.
  - Credentials are offered for saving when the user navigates away, or on `LocalAutofillManager.current?.commit()`.
  - The minimum Compose version is not stated on the page (UNVERIFIED).
- Android TV: https://developer.android.com/identity/form-factors
  - "Android TV doesn't support autofill". Credential Manager (API 34+) offers saved passwords and passkeys through a QR code.
- Apple: https://developer.apple.com/documentation/security/enabling-password-autofill-on-a-text-input-view
  - Use `textContentType` `.username` / `.password`; SwiftUI `.textContentType(_:)`.
  - tvOS supports Password AutoFill with the same content types, filled from an iPhone (Continuity Keyboard).
  - Associated domains (https://developer.apple.com/documentation/security/password-autofill) are fixed when the app is built. They cannot name user-hosted servers, so AutoFill can only suggest by heuristics. That it then works without an associated domain is UNVERIFIED.
- Windows: https://learn.microsoft.com/en-us/windows/apps/develop/security/credential-locker
  - `PasswordVault` exists for WinRT apps; save only after success and with the user's consent.
  - I found no system password-autofill path for a desktop JVM app (UNVERIFIED). Compose Multiplatform desktop autofill is UNVERIFIED (CMP-9861 is open, see platform-notes).
- Web:
  - https://web.dev/articles/sign-in-form-best-practices: use a real `<form>` and `<label>`; set `autocomplete="username"` / `current-password`; keep `name` and `id` stable; after success, navigate away or `replaceState`.
  - MDN autocomplete: browsers may need `name`, a parent form and a submit button.
  - WCAG 2.2 SC 3.3.8 (https://www.w3.org/WAI/WCAG22/Understanding/accessible-authentication-minimum.html): never block paste or password managers.

**What the app does now**
- Web: the sign-in page matches web.dev. It is a real `<form>` with a submit button, `name` plus `autocomplete="username"` and `"current-password"`, `autoCapitalize="off"` and `spellCheck={false}` on the username, and it navigates away after success.
- Compose (`composeUi/.../SignInScreen.kt:80-98`):
  - Neither field sets `contentType`, so Android Autofill and password managers have no hints. They may still guess the password field from `KeyboardType.Password`.
  - The username field uses the default keyboard, with autocorrect and auto-capitalisation on.
- Apple: no UI yet.

**Recommendations**
- **[Android]** Add `semantics { contentType = ContentType.Username }` and `ContentType.Password` to the sign-in fields. Call `LocalAutofillManager.current?.commit()` on `SignInUiState.SignedIn`, so the save prompt appears right after a successful sign-in, as in most well-made Android apps.
- **[Android, desktop]** Username field: `KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)`. Jellyfin user names are case-sensitive on some setups, and auto-capitalisation turns "anna" into "Anna" (UNVERIFIED whether Jellyfin compares names case-insensitively).
- **[iOS, macOS, tvOS]** When the SwiftUI sign-in lands: `.textContentType(.username)` / `.password`, `.textInputAutocapitalization(.never)` and `.autocorrectionDisabled()` on the username. Do not prefill the password (HIG, Entering data). Expect AutoFill to suggest by heuristics only (no associated domain for user servers).
- **[Android TV]** Do not rely on autofill. Make Quick Connect the main path (see 7.2).
- **[Windows]** No system autofill to target. Keep paste working in both fields (it works today), so password-manager users can copy their password in.
- **[Web]** Keep it as it is. Optionally add `id="current-password"` (web.dev), and `required` on the username in place of the disabled button. Never block paste.

### 7.2 Device-code and QR sign-in (Quick Connect)

**Sources**
- RFC 8628 (https://www.rfc-editor.org/rfc/rfc8628):
  - Show the user code and the verification URI. A QR code of `verification_uri_complete` is allowed, but the client "MUST still display the user_code".
  - Poll at the server's interval (default 5 s); on `slow_down`, add 5 s.
  - Back off on connection timeouts rather than giving up (§3.5).
  - Avoid look-alike characters.
- Jellyfin Quick Connect (https://jellyfin.org/docs/general/server/quick-connect/, `QuickConnectManager.cs`):
  - A 6-digit code, approved under Settings > Quick Connect on a signed-in device. Requests live 10 minutes.
  - `Initiate` answers 401 when Quick Connect is off. `Connect` answers 404 for an unknown or expired secret.
  - Jellyfin does not dictate a polling interval.
- Apple HIG, Managing accounts (https://developer.apple.com/design/human-interface-guidelines/managing-accounts): "Delay sign-in for as long as possible." On tvOS, "Prefer letting people use another device to sign up or authenticate."
- Android TV app quality (https://developer.android.com/docs/quality-guidelines/tv-app-quality), TV-LI: "Users are able to login using mobile or Google Account". A dedicated Android TV code sign-in guide was not found (UNVERIFIED).

**What the app does now**
- The password form comes first. A Quick Connect button appears when the server says it is on.
- The code is shown large (`codeStyle`) with "Use a password instead". The app polls every 5 s for up to 10 minutes.
- One failed poll ends the flow (C2).
- An expired code returns to the form with a problem instead of offering a new code.
- There is no countdown, and no instruction says where to approve it beyond the title.

**Recommendations**
- **[tvOS, Android TV]** Open sign-in on the Quick Connect code when the server has it enabled, with "Use a password instead" as the second choice. This is the reverse of desktop and web, and it follows the HIG and TV-LI.
- **[all]** Under the code, say where to approve it: "On a device that is signed in, open Settings > Quick Connect and enter this code". This reads the path from Jellyfin's docs; check the exact menu name per Jellyfin version.
- **[all]** Group the six digits as "123 456" visually, but keep the text as one string for screen readers and copying.
- **[all]** When the code expires, offer "Get a new code" in place, rather than dropping back to the form.
- **[all]** Back off and keep polling on network errors (RFC 8628 §3.5; fixes C2).
- **[all, later]** A QR code is not useful while Jellyfin has no approval URL that carries the code. Revisit if the server gains one. Keep the code visible next to any QR code (RFC 8628 §3.3.1).

### 7.3 Error messages

**Sources**
- Apple HIG Writing (https://developer.apple.com/design/human-interface-guidelines/writing):
  - Place the message close to the problem, without blame, and say what the person can do.
  - Avoid "oops" and "we".
- Apple HIG Alerts (https://developer.apple.com/design/human-interface-guidelines/alerts):
  - No "Error" titles or codes.
  - Avoid alerts at launch: for no network, show cached data and a nonintrusive label.
- Microsoft writing style (https://learn.microsoft.com/en-us/windows/apps/design/style/writing-style):
  - Say what happened, what happens next and a realistic fix.
  - Address the user as "you" and the app as "we", unlike Apple.
- Microsoft Error Messages (https://learn.microsoft.com/en-us/windows/win32/uxguide/mess-error, Windows 7-era guidance): problem, cause, solution; in-place errors for input.
- WCAG 2.2:
  - 3.3.1 Error Identification (https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html): name the field in text, with `aria-invalid` and a live region.
  - 3.3.3 Error Suggestion (https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html): suggest the fix unless that harms security, so do not say whether the username or the password was wrong.
- MDN `aria-invalid`: set it only after validation, and pair it with `aria-errormessage` or `aria-describedby`.
- Material 3 text-field error guidance: the page renders with JavaScript and could not be read (UNVERIFIED).

**What the app does now**
- Problems are typed in this area (`ServerSetupProblem`, `SignInProblem`) and rendered in place. Examples: "No server answered", "The server took too long to answer." with the Nearby devices hint, "Wrong username or password.", "Another server answers at this address."
- Focus returns to the field (web, #37).
- Web: `role="alert"` on the notice, and `aria-invalid` on the password for wrong credentials and on the address field.
- That covers WCAG 3.3.1 and 3.3.3, and wrong credentials do not reveal which field was wrong.
- Gaps:
  - `Refused` and `NotThisService` give no fix.
  - `Redirected` cannot say where the redirect goes, because the location is not kept.
  - The web notice is not tied to its field with `aria-describedby`.
  - Compose fields do not use `isError` / `supportingText`; I did not re-read the M3 guidance for these.

**Recommendations**
- **[all]** Give each problem a next step:
  - `Redirected`: "Check the path, for example /jellyfin, or use https".
  - `NotThisService`: "This address answers, but not as a Jellyfin server. Check the port (8096 by default)".
  - `Refused`: "The server refused the request. Its remote access settings may block this network".
  - `TimedOut` while access is not denied: suggest checking that the device is on the same network.
- **[web]** Add `aria-describedby` (or `aria-errormessage`) from the invalid field to the notice. Set `aria-invalid` on the username too for `WrongCredentials`, since the error is about both.
- **[Android, desktop]** Use `isError` and `supportingText` on `OutlinedTextField` for field-level problems (address, credentials), so screen readers announce them with the field. Material 3's own wording is UNVERIFIED.
- **[iOS, macOS, tvOS]** Show problems inline, never as alerts with "Error" titles (HIG). For an unreachable server at start, keep the signed-in state with an "offline" label, which `JellyfinAccounts` already supports (`reachable = false`).
- **[Windows]** Pick one voice for all platforms. Apple avoids "we", Microsoft uses it. The current strings ("No server answered", "The server took too long to answer.") are neutral and fit both; keep that style.

### 7.4 Adding a server

**Sources**
- Jellyfin networking (https://jellyfin.org/docs/general/post-install/networking/):
  - Ports 8096 (HTTP) and 8920 (HTTPS, off by default). HTTPS is recommended behind a reverse proxy.
  - A base URL must be part of the address.
  - UDP 7359 discovery reaches the local subnet only.
  - Discovery details and the official SDK's HTTPS-first probing are already in docs/platform-notes.md, "Jellyfin 12.2: local discovery and server identity".
- Android:
  - Cleartext is off by default since API 28 (https://developer.android.com/privacy-and-security/security-config).
  - `ACCESS_LOCAL_NETWORK` (https://developer.android.com/privacy-and-security/local-network-permission) is handled per ADR 0020.
- Apple TN3179 (https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy):
  - Local network privacy applies on iOS 14+ and macOS 15+, not tvOS.
  - There is no API to check or prompt.
  - Broadcast needs the multicast entitlement on iOS.
- Apple ATS `NSAllowsLocalNetworking`: since iOS 17 and macOS 14, plain HTTP to IP addresses needs this key, and plain HTTP to a public name needs `NSAllowsArbitraryLoads`.
- Browsers:
  - Mixed content (https://www.w3.org/TR/mixed-content/) blocks `http://` from an HTTPS page.
  - Chrome's Local Network Access prompt (https://developer.chrome.com/blog/local-network-access) is already in the platform notes.
- Field input:
  - MDN `inputmode` says a field that must hold a URL should be `type="url"`.
  - A bare host such as `192.168.1.10` is not a valid URL for `type="url"` validation. From the WHATWG definition, not re-read: UNVERIFIED wording.
  - Compose `KeyboardType.Uri`; SwiftUI `.keyboardType(.URL)` (iOS and tvOS only).

**What the app does now**
- One field accepts a host, IP, port, scheme or path (`ServerUrl.candidates`).
- It probes https first, then http and the default port, all at once, and the best answer wins.
- It shows the server's name, version and scope ("On your local network" / "On the internet"). It warns about plain http on the internet before saving, and refuses duplicates by server id.
- On Android it explains Nearby devices before asking.
- Web input: `type="url" inputMode="url" autoComplete="url" autoCapitalize="off" spellCheck={false}` inside a `noValidate` form, so the browser's URL validation does not reject a bare host.
- Compose: `KeyboardType.Uri` with `ImeAction.Go`.
- There is no automatic discovery yet. The M1 spike (#25) ran the UDP discovery on devices.

**Recommendations**
- **[all]** List servers found on the local network above the address field, the way most media apps present adding a server. Each entry shows the server name and address from `System/Info/Public`, which the probe already returns. Typing an address stays available.
  - **[Android]** Do this only after Nearby devices is granted.
  - **[iOS, macOS]** Note that it needs the multicast entitlement on iOS (TN3179). The entitlement is unverified for this app, see platform-notes.
  - **[tvOS]** No local network prompt applies.
  - **[web]** Not possible: browsers have no UDP.
- **[all]** Show an example in the field's hint, for example `media.example.com` or `192.168.1.10:8096`, and say that a path such as `/jellyfin` is allowed. This saves the most common `Redirected` and `NotThisService` failures.
- **[all]** After "Found", go straight on to sign-in (desktop and web already do; #37). Most apps then show the server's public users to pick from; that would need `/Users/Public`, whose shape is still unverified (platform-notes).
- **[web]** Keep `noValidate` with `type="url"`, or switch to `type="text" inputmode="url"`, which needs no `noValidate` and gives the same keyboard. Either works. Pages served over HTTPS cannot reach `http://` servers (mixed content). Say so in the problem text when the page is HTTPS and the input is http, instead of "No server answered".
- **[iOS, tvOS]** When the SwiftUI page lands: `.keyboardType(.URL)`, `.textInputAutocapitalization(.never)`, `.autocorrectionDisabled()`, and `.submitLabel(.go)`.
- **[macOS, iOS]** The plain-http-on-internet warning matters more on Apple: ATS blocks plain http to public names unless `NSAllowsArbitraryLoads` is set. Decide this per ADR before the Apple UI ships, or the check succeeds in Kotlin and then fails in AVPlayer (UNVERIFIED whether AVPlayer follows the same ATS rule, see platform-notes).

## Seen outside my area
- `webApp/index.html`: no Content-Security-Policy, although tokens live in `localStorage` (see C6; #37 left it for deploy).
- `composeUi/.../SignInScreen.kt:80-98`: the username and password fields set no autofill content type (`ContentType.Username` / `ContentType.Password` semantics), so Android Autofill and password managers get no hints. The username field does not turn off autocorrect or capitalisation (see section 7).
- composeUi on Android shows "No credential store was found" (#35), although Android's store is coming in M4 rather than missing. The text should depend on platform as well as `SecretStorage.Memory` with `fileOffered = false`.
- ADR 0011 describes a `webMain` source set for `js` and `wasmJs`. The code in this area uses `jsMain` only (`LocalStorageKeyValueStore.kt`, `ProbeRunner.kt`), so the ADR is out of date.
