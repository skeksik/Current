---
description: Add a new feature (screen + ViewModel + navigation wiring)
argument-hint: <feature-name> [short description]
---

Add a feature: $ARGUMENTS

Read CLAUDE.md and docs/architecture.md first. Then:
1. Propose the plan (screens, data sources, navigation entry, whether it warrants its own `feature/<name>` module yet) and wait for approval.
2. Put the ViewModel and UI state in `feature/<name>` (no Compose, ADR 0005), the Compose screen in `composeUi`, and note the SwiftUI view that Apple will need (ADR 0006). Implement in small steps following the conventions in CLAUDE.md. No new dependency without asking.
3. Add a ViewModel unit test in `commonTest` with fake repositories.
4. Run `./scripts/check.sh` (or the relevant tasks) and report honestly.
