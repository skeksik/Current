---
description: Implement the headless PlayerEngine for one platform (apple | android | desktop | web)
argument-hint: <apple|android|desktop|web>
---

Implement the `PlayerEngine` for: $ARGUMENTS

Read docs/decisions/0007-playback-architecture.md and docs/platform-notes.md first. Rules:
- Engines are headless: no built-in controls. The player UI is the app's own (SwiftUI on Apple, Compose elsewhere).
- Implement the `PlayerEngine` interface from `core:playback` and report `EngineCapabilities` honestly so `PlaybackPlanner` can choose routes.
- Lifecycle: create on first use, reload when the source changes, release on dispose. Surface/view hosting is separate from the engine.
- Platform integrations (Now Playing, PiP, media keys) are part of the work; check docs/platform-notes.md for what is verified and verify the rest from primary sources.
- Propose the approach and any new dependency or licence implication first (add to the version catalog only after I approve). Update docs/platform-notes.md and docs/roadmap.md when done.
