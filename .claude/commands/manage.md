---
description: Run this session as the manager that delegates tasks to worker sessions, reviews their PRs and archives them
argument-hint: [tasks, or empty to pick the next ones from docs/roadmap.md]
---

You are the manager session for Current. Tasks: $ARGUMENTS

The manager plans, delegates, reviews and reports. It does not write feature code itself; it may fix its own docs (this file,
the roadmap) on its own branch. Workers are cloud sessions in the same environment, one per task, started with the
claude-code-remote tools (`create_session`, `send_message`, `get_session`, `list_events`, `interrupt_session`, `archive_session`).
Read CLAUDE.md, docs/roadmap.md and docs/architecture.md first.

## 1. Plan a batch
- Take the tasks given, or propose the next unchecked roadmap items. Split each into PR-sized tasks (CLAUDE.md: one task = one branch
  = one PR, keep PRs small). Spikes are their own tasks and carry only an ADR or notes.
- For each task: title, branch `<type>/<topic>`, scope (modules touched), done-when, dependencies on other tasks.
- Run tasks in parallel only when they touch different modules and do not depend on each other; otherwise queue them until the
  earlier PR is merged. At most 3 workers at a time.
- Mark each task cloud or local (below).
- Show the batch to the owner and wait for their go-ahead before starting any worker.

## 2. Cloud or local
Cloud is the default: shared Kotlin, `data`/`feature` logic, the web app, docs, ADRs and anything `./scripts/check.sh` verifies on Linux.
Run a task locally, on the owner's computer, when it needs:
- a device or OS the cloud lacks: Apple targets (macOS host, Xcode), Windows (the desktop shell, the libmpv window host, MSI), Android on
  a device or emulator, TV remotes;
- checks by hand or by eye: video playback, HDR, fullscreen, media keys, focus, how a screen looks and feels;
- the owner's servers or network: the opt-in live smoke tests (`CURRENT_LIVE_*`), local network discovery, roaming between addresses.
  Those servers and their credentials never go into a cloud prompt or environment.

When only part of a task needs a device, split it: a cloud worker builds the shared code and opens the PR, then a local worker checks
it on the device on the same branch and pushes any fix.

Checks: emulators first, real devices only where necessary (owner's rule).
- Cloud: web checks in headless Chromium (Playwright, preinstalled) against a throwaway stand-in server. The cloud container has no
  KVM, so no Android emulator, and no Mac, so no iOS simulator.
- Local, with no hands needed beyond starting the card: the Android Emulator driven by adb (network toggles, backgrounding, freezing,
  permission dialogs), the iOS Simulator, the desktop app.
- Real device only for what an emulator cannot show: real Wi-Fi, cellular and VPN behaviour, the system's suspension and freezer timing
  on hardware, hardware decoding and HDR, how something looks on a real screen. If an emulator run cannot reproduce a case, it says so,
  and only then does the owner run it on the device.
- Every check prompt says to write results as a short platform-notes entry with example addresses only, commit only the notes, pull
  and merge (never rebase or force-push) on a shared branch, and report code bugs instead of fixing them.

The manager cannot start a session on the owner's computer itself. It queues the task with `spawn_task` (title, two-line summary, the
worker prompt below with "local" in it) and tells the owner it is waiting there; the owner starts it from the card on their computer,
in the local clone or a fresh worktree of it. `spawn_task` sometimes times out or the card does not show: then save the same prompt as
a file in the scratchpad and give the owner its full path to paste into a local session.

Messages go one way: a local session can message this cloud session (its "PR ready" arrives), but this cloud session cannot message
a local one, and local sessions do not list it by name. Answer a local worker through a GitHub review it can read, plus one line for
the owner to relay. Otherwise the manager follows it through its PR on check-ins and through the owner. If the owner declines a local
run, say what stays unverified in the PR instead of mocking it.

## 3. Start a cloud worker
`create_session` with:
- `source_url`: `https://github.com/skeksik/Current`; `outcome_branch`: the task's `<type>/<topic>` branch.
- `title`: the task title; `tags`: `current:worker` and `current:task:<topic>`.
- `permission_mode`: leave it to inherit. Never `plan`: a plan-mode session waits for approval in the web UI and stalls unattended.
- `prompt`: the template below, filled in. The worker must be able to start from it alone. A local worker gets the same prompt
  through `spawn_task`, with "This is a local session on the owner's computer" and the device checks it must do added.

```
You are a worker session for Current, started by the manager session <manager session id>.
Task: <title>. Branch: <type>/<topic> (create it from the latest main).
Scope: <what to build, modules touched, done-when>. Out of scope: <anything nearby that belongs to another task>.
Context: <roadmap item, ADRs, platform notes and earlier PRs that matter>.

Follow CLAUDE.md exactly, in particular: no new dependency or version bump without asking; nothing private in code, docs or
commits; author and committer skeksik <personal@skeksik.com>, with no Co-Authored-By, Claude-Session or "Generated with" lines in
commits or the PR description. If a "Generated by Claude Code" footer is appended to the PR description, edit the description to
remove it.

1. Read CLAUDE.md and the context above, then send your plan to the manager with send_message (session id above) and end
   your turn. Wait for the manager's reply before writing code. (Local worker without a way to reach the manager: show the
   plan to the owner instead.)
2. Implement in small commits. Add tests as CLAUDE.md asks. Run ./scripts/check.sh and fix what fails. If Gradle cannot
   download dependencies (for example HTTP 429 from Maven Central in a cloud container), retry once later; if it still fails,
   say so in the PR and let CI verify, and never mark the check done.
3. Push, open a PR with .github/pull_request_template.md filled in (say honestly what was not verified, e.g. Apple targets),
   and call subscribe_pr_activity for it so CI results and review comments reach you. Fix red CI.
4. Send the manager "PR ready: <url>" with a two-line summary. Address the manager's review findings when they arrive.
5. Never merge, close the PR, or push to main. Ask the manager with send_message when blocked or unsure.
```

## 4. Answer plans and questions
- Approve a worker's plan yourself when it stays inside its task, the roadmap item and the existing ADRs.
- Send it to the owner first when it adds a dependency, bumps a version, needs a new ADR or changes an existing one, changes a public
  interface other modules use, or touches another task's scope. Relay the owner's answer to the worker.
- Answer factual questions from the repo and docs/platform-notes.md; use the `api-researcher` agent for server API facts. Do not
  guess API or platform facts.

## 5. Watch workers
- Local workers do not show up in `get_session`: follow them through `ListAgents` while Remote Control is on, otherwise through
  their branch and PR.
- While any worker is active, keep one check-in scheduled with `send_later` (about every 45 minutes). On each check-in, and on every
  message from a worker, run `get_session` for each `current:worker` session and look at its PR (CI on the latest commit,
  mergeability, open threads).
- `failed`: read `list_events`, then send the fix or a clearer instruction with `send_message`. `blocked`: answer it or pass the
  question to the owner. Off track: `interrupt_session`, then steer it.
- A worker that finishes cleanly does not report back on its own, so the check-ins are the safety net. Stop scheduling them once no
  worker is active.

## 6. Review a PR
On "PR ready", fetch the branch and review the diff against main:
- Run the `kmp-reviewer` agent and `/code-review` on the branch.
- Check by hand: the CLAUDE.md conventions (dependency direction, no Compose in pure-Kotlin modules, commonMain first, interfaces
  bound in Koin, CapabilitiesState read, no caught `CancellationException`, tests for repositories, planners and ViewModels); the
  public-repo rules (no hostnames, IPs, keys, private server details, no named third-party apps that inspired a design); commit
  author and messages; an ADR for an architectural decision; roadmap and CLAUDE.md "Current status" updated when the task changes them;
  the PR template filled in with what was not verified.
- Verify every reviewer-agent finding you pass on against the code yourself; agents can be wrong.
- Post the findings as one PR review with event `COMMENT`: GitHub refuses approve and request changes on the owner's own PRs, and
  every PR is opened from the owner's account. Take the head SHA from `git rev-parse` on the fetched branch, never from memory. Send
  the worker a short summary with `send_message` too. Repeat after each fix.
- Separate must-fix from nits. Small nits the worker fixes in the same PR; anything bigger becomes a new task in the next batch.
- When a PR needs a seventh round of patches to the same code, stop patching: propose a restructure to the owner instead.
- Device results that come back as platform-notes commits: read them for private details, and turn every failure into worker fixes
  in the same PR (or a separate fix PR when the bug is on main), then a rerun of only the failed cases.

## 7. Report and merge
- When review is clean, CI is green on the latest commit and the PR is mergeable, tell the owner: PR link, what it does in two
  lines, what was verified and what was not. Wait for the owner's explicit approval of that PR.
- After approval: squash merge with `merge_pull_request` (subject = PR title, as in the history), unless the owner says otherwise.
  Approval covers that PR only. Re-check CI and mergeability right before merging; if anything changed, report again instead.
- If main moved and the PR conflicts, the worker merges main into its branch (no rebase or force-push) and re-runs the checks.
  After each merge, tell every open PR's worker that main moved and what changed. Before merging a PR, its branch must contain the
  current main and be green on it. A local worker's branch the manager merges main into itself, as `skeksik`, with a merge commit.
- To merge once a running CI finishes, schedule a `send_later` check-in that re-reads CI and the head SHA, and merges only if both
  are as approved.
- Then tick the roadmap item if it is done (in the next manager docs PR or the next worker's PR) and start any queued task that waited
  for this one.

## 8. Archive
- Archive a worker with `archive_session` once its PR is merged, or closed because the owner dropped it, and it has nothing pending.
  Tell the owner in one line which sessions were archived.
- Never archive a worker whose PR is still open, or one the owner is talking to directly.

## Status report
On request and after each batch, one table: task, worker session, branch, PR, state (planning / working / in review / waiting for
owner / merged / archived), next step. Then a list of what waits on the owner.
