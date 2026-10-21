# 0019: Roaming between server addresses

Status: accepted (2026-10-20), with several addresses per server.

## Context
A saved server keeps several addresses in the user's priority order (ADR 0016): local ones (`http://192.168.1.10:8096`,
`http://media.lan:8096`) and internet ones (`https://media.example.com`). ADR 0015 made every client read its address from a
`ServerAddressSource` for each request, so roaming needs a smarter source, not new clients. Credentials must reach only an address that
proved to be the same server. docs/platform-notes.md ("Detecting network changes") found no platform that says which network the device
is on, so a signal can only mean "something may have changed".

## Decision
- **One event loop per server.** `RoamingServerAddress` handles every input in order from a single queue: an edit, a network change
  and its settle, a resumed network, a request (no address, or the address in use failing), a timer, and each probe's result. Only the
  loop changes the state (the address in use, whether requests are held, the check running, the retry wait); requests read what it
  publishes and wait on it. The settle and the timers are scheduled events, and probes run as child jobs that post their results;
  each carries a token of the settle, timer or check it belongs to, and the loop ignores one that has been replaced. Requests and
  resumed networks are queued only when one is not already waiting, so the queue stays bounded however fast they come. The rules
  below are the loop's.
- **One `RoamingServerAddress` per saved server**, made by `RoamingAddressSources` (bound in Koin in place of the first address),
  started for every saved server and shared by every client of that server. It runs in the app scope until the server is removed,
  follows edits to its addresses, and has no address once stopped.
- **Choosing: the best address proved so far, upgraded as higher ones answer.** Every address is asked at once with the service's
  `ServerProbe`, without credentials, and only one that reports the saved server id is used. When no address is in use, the first
  that proves itself is used at once and the check moves up to each higher one that answers, so requests never wait for a higher
  address that hangs until its probe times out (5 s for Jellyfin). An address already in use is only replaced mid-check by a higher
  one; a lower one takes over only when the check ends without it answering. The check ends when the highest proved address has no
  higher one still being asked, or once it has run for twice the probe's timeout (10 s for Jellyfin), with the best
  address it proved by then, since a probe can fail to answer at all. An address that answers with another id, a redirect or
  anything else is never used. In a browser, a local `http` address from an `https` page fails as mixed content and simply does
  not answer.
- **What the check proves:** the id comes from a public endpoint (`/System/Info/Public`), so it stops credentials going to *another*
  server that happens to answer at an address (a different network, a reused IP or name). It does not stop impersonation: anyone who can
  answer on a plain `http` local address can copy the id. HTTPS addresses are protected by TLS as usual.
- **Requests wait for the first proved address** at start and after a network change: a request made while that check runs waits
  until some address proves itself (at most the 1 s settle window plus twice the probe's timeout, 11 s for Jellyfin) instead of going
  to an unproved address, and fails as unreachable if nothing is proved. A network change holds requests from the moment it is
  reported, before the loop has handled it. On a new network the old address is dropped at once, since
  whatever answers there may be another server. For the other triggers the address in use stays until a check finds another.
- **A miss keeps the last proved address.** When nothing answers and the network has not changed, the address in use stays in use and
  the server is marked unreachable; the check is retried with the waits below. Only a network change, or an edit that removes it,
  clears the address.
- **One wait per request:** `ServerClient.request` asks the source once, sends every request in its block to that address, and reports
  that same address when one cannot connect or times out.
- **When it asks again:**
  - at start, and when the user adds, removes or reorders addresses;
  - on a network change (`NetworkChanges`): Android's default network callback; the browser's `online` and `navigator.connection`
    `change` events; on desktop, the network interfaces polled every 5 s (a change in which ones are up or in their addresses); on
    Apple, Network framework's path monitor (`NWPathMonitor`), when the path's status, interfaces (names and types) or gateways differ
    from the last update's; the first update is the path at start, and unchanged repeats do not count. A network change also starts the retry
    waits over at 15 s. The address is dropped and requests wait from the first update, but the check starts only once updates have
    stopped for 1 s, since one transition comes as a burst;
  - when the same network is usable again (`NetworkChange.Resumed`): on Android when the system unblocks the default network (it
    blocks a background app's network, and a check made then finds nothing), and on iOS and tvOS when the app comes back to the
    foreground. The check starts at once, the address in use stays, and the retry waits start over. A request that was waiting across
    it starts its wait again instead of failing, once per resumed network and never beyond twice the bound in all. The macOS
    wake is not a signal yet (a follow-up);
  - when a request to the address in use cannot connect or times out (reports for an address no longer in use are ignored), and
    when a request finds no address at all. Never while a check runs, which answers it, and at most once per 15 s after the last
    check ended; a request that comes sooner is kept and the check comes when that window ends. So a caller that retries on every
    failure cannot drive checks back to back, and recovery takes at most 15 s plus a probe;
  - every 5 minutes, asking the address in use and those above it: to move back to a higher (local) address, or to notice that the
    one in use stopped answering, in which case every address is asked;
  - while nothing answers, after 15 s, doubling up to 5 minutes after each miss on the timer (not after one a request or a change
    woke), back to 15 s after a success.
  Edits and resumed networks that arrive during a check make exactly one more check; requests make none. A check whose addresses are edited, or whose network changes,
  while it runs is cancelled at once; a new one starts at once after an edit, and once the network has settled after a change.
- Whether the server answered goes into the capabilities state (`reachable`).
- **Connection failures reported outside `IOException`** are request failures (`Unreachable`), not bugs, so they cannot end the roaming
  loop: Android's `SecurityException` for a missing `INTERNET` permission (logged once, so a shell without the permission does not pass
  for a server that is down), and Ktor's browser `Error("Fail to fetch")` for any failed fetch. Only those exact shapes count; any other
  `SecurityException` or `Error` stays a bug.
- **A source whose run a bug ends is replaced**, a stray `CancellationException` included. Clients hold a stable wrapper; behind it
  a fresh source, with its own queue, ids and state, starts cold (holding requests and checking) from the saved server as it is then,
  so the dead run's queued events and probe results are never handled. It starts after 15 s, doubling while the bug recurs up to
  5 minutes, back to 15 s after a run of 5 minutes. A dying run drops its address at once, and `reachable` goes back to unknown
  until the fresh source has checked. The bug reaches the app scope's exception handler, which logs its class name only (ADR 0015),
  so it does not end the app on Android and Apple; a stray cancellation, which that handler never sees, is logged the same way.
- **A network signal that fails starts again** after 15 s, doubling up to 5 minutes, and counts as a change, since one may have been
  missed meanwhile. A source whose signal ends is treated as a bug, never left running blind.
- **A service that cannot identify itself without credentials** (Stash, later; an explicit list) gets its addresses in priority order,
  moves to the next on a reported failure and back to the first on a network change. Every saved address is one the user typed or
  confirmed (ADR 0016). Any other service without a probe gets no address.
- **Editing addresses:** `withAddress`, `withoutAddress` (never the last) and `withAddressMoved` on `SavedServer` (`core:network`);
  adding still goes through `ServerSetupViewModel(addTo = …)`, which accepts only the same server id. `ServerAddressesViewModel`
  (`feature:settings`) lists the addresses with their scope and the one in use, and removes and reorders them.

## Consequences
- Android declares `INTERNET` and `ACCESS_NETWORK_STATE`; without the latter there is no signal. Reaching a local server on Android
  still needs a network security config for plain `http` local addresses, and `ACCESS_LOCAL_NETWORK` at target 37
  (docs/platform-notes.md, "Roaming on Android"); those come with the shell.
- On Android a DNS lookup runs outside every engine timeout: over a VPN the owner measured 6.0 s for a failed lookup of the internet
  name (docs/platform-notes.md, "Request timeouts on blocking engines"). `RequestDeadline` (`core:network`) bounds every request,
  probes included, so a probe ends at its 5 s timeout while the abandoned lookup finishes on its own. A failed lookup is cached for
  only 1-3 s on that phone, so a later check usually waits the full 5 s again rather than failing fast.
- A network change does not cancel requests already sent: one sent just before it can go to the old address and hang until its own
  timeout (17.8 s over a VPN on Android).
- Every probe of every address goes into the request log under the server's key, so roaming adds log lines but no hosts.
- The first request after start or a network change can wait up to the settle window plus twice the probe timeout (11 s for
  Jellyfin), longer while a burst of updates goes on. On a Mac (docs/platform-notes.md, "Apple path monitor and roaming on a Mac") a
  transition came as 7-11 path updates within 3-5 s, and opening the Wi-Fi menu alone sent some. Before the settle window each
  update restarted the check: mostly a few milliseconds, but on a phone hotspot, where the home address timed out instead of being
  refused, requests were held 4-7.5 s per update. With the window, a burst costs one check after it ends; an update with nothing
  changed still costs one check on platforms that report them; Apple's now compare the path first.
- A network change no signal reports leaves requests on the old address until one fails or times out, or until the next periodic
  check (up to 5 minutes): if another device answers there on the new network, it receives them, credentials included. Known cases: on
  desktop, a move to another network that keeps the same interface addresses (same subnet and lease); on Apple, whatever the path
  monitor does not report, such as a move between two Wi-Fi networks whose gateways have the same address, made without a moment in
  which the path is unsatisfied (the path's status, interfaces and gateways then look the same); on the web, most real switches:
  the owner's run in Chrome, Edge and Firefox fired no `online` or `offline`
  for switches between networks, and `navigator.connection` `change` follows the speed estimate, not the network
  (docs/platform-notes.md, "Roaming in browsers"). There the periodic check and failed requests, now mapped to `Unreachable`, are what
  notices a new network.
- Desktop polling reads the interfaces every 5 s while the app runs. Link-local addresses are ignored, and an interface left with no
  other address does not count, so Docker's `veth*` pairs and idle Hyper-V or WSL adapters do not trigger a check. Each of these still
  does, and makes requests wait for one check: a virtual adapter with a routable address appearing, disappearing or changing (Hyper-V's
  `vEthernet (Default Switch)` or WSL's adapter getting a new private address when the host or WSL restarts, Docker's `docker0` and
  `br-*` bridges, a VPN's tunnel), and an IPv6 temporary address that rotates.
- A server answering at several addresses is asked at all of them on each full check; with a handful of addresses per server that is cheap.
