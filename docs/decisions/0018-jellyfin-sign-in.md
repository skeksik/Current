# 0018: Jellyfin sign-in

Status: accepted (2026-10-20). Logic and ViewModels; the screens come with the shells.

## Context
M1 ends when you can sign in and out of Jellyfin on Windows and in the browser, and the app restarts signed in. ADR 0016 saves servers
with the id they reported; ADR 0017 keeps tokens and the per-install device id. Jellyfin accepts only its `MediaBrowser` header since
12.0, revokes a token when the same device id signs in again, and offers Quick Connect (a code approved in another signed-in app).

## Decision
- **`JellyfinAccounts`** (`data:jellyfin`) owns Jellyfin sessions: password sign-in (`POST /Users/AuthenticateByName`), Quick Connect
  (`GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate`, polling `GET /QuickConnect/Connect?secret=` every 5 s for up to 10 minutes,
  then `POST /Users/AuthenticateWithQuickConnect`), sign-out, and `authorizedClient` for the data layer to come.
- **The header** is `MediaBrowser Client="Current", Device="…", DeviceId="…", Version="…"[, Token="…"]` with URL-encoded values, built
  from `DeviceIdentity` and `AppInfo`, sent without `Token` until signed in.
- **Credentials reach only the saved server.** Requests go to the saved server's address (`ServerAddressSources`, the first address
  until roaming). The `ServerId` in a sign-in answer must equal the id saved at setup; otherwise the new session is ended on that server
  and nothing is kept.
- **What is kept:** the token in the `SecretStore` (`jellyfin/<server key>/token`); the user's id, name and admin flag in settings
  (`jellyfin.<server key>.user`), which are not secret and let the app show who is signed in while the server is offline.
- **At start** saved sessions go straight into the capabilities state, then `GET /Users/Me` checks each in the background: it refreshes
  the user, a 401 signs out, and an unreachable server stays signed in but offline (ADR 0014).
- **Any 401** from an authorized client signs that server out (`ServerClients`' `onUnauthorized`): a revoked token, or the same device
  id signed in elsewhere.
- **Sign-out** ends the session on the server when it answers (`POST /Sessions/Logout`, 5 s), and forgets the token and user either way.
  Removing a server signs out first.
- **Permissions:** Jellyfin's `Policy.IsAdministrator` maps to `Permission.admin`; nothing else yet.
- **ViewModels** (`feature:settings`): `SignInViewModel` (form, Quick Connect code, problems: wrong credentials, unreachable, timed out,
  refused, different server, Quick Connect off or expired) and `ServersViewModel` (saved servers with who is signed in, sign out, remove,
  where tokens are kept, and the opt-in file when there is no keyring).

## Consequences
- The web app has the same flow, through the `web` facade once the React shell has screens; CORS is open on Jellyfin (platform notes).
- The shapes come from the 12.2 OpenAPI spec. The opt-in live smoke tests (`LiveJellyfinSmokeTest`) confirmed Quick Connect sign-in,
  the restored-session check and sign-out against a live 12.2 server on 2026-10-20; password sign-in has not been run live.
- The user's avatar is not loaded yet (`PrimaryImageTag`), and Quick Connect approval of another device's code (`/QuickConnect/Authorize`)
  is left for Seerr sign-in (M3).
