---
description: Run the same checks as CI and summarize failures
---

Run `./scripts/check.sh` and summarize the outcome. For each failure: name the module and task, quote the key error line,
and fix it if the fix is small and clearly in scope. Do not weaken tests or lint rules
to get green. If a target cannot be built on this machine (e.g. iOS on Linux), say so instead of skipping silently.
