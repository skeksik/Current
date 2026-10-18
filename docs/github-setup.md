# GitHub setup

Checked 2026-10-18 against GitHub and Claude Code docs; re-check limits and prices before relying on them.

## Plan limits that matter if the repo goes private (it starts public)
- Branch protection and rulesets on private repositories need GitHub Pro, Team or Enterprise. On GitHub Free they exist only for public repositories.
  Sources: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches ,
  https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets
  Without protection, the rule "never commit to main" is enforced only by discipline and by `.claude/settings.json` (denies `git push origin main`).
- Actions on private repos: Free plan includes 2,000 minutes and 500 MB artifacts per month. Per-minute rates beyond that: Linux 2-core $0.006, Windows $0.010, macOS $0.062.
  The page lists rates, not minute multipliers, so assume macOS minutes burn the allowance much faster than Linux.
  Source: https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions
- The repo starts public, where standard runners are free and rulesets work, so CI (`.github/workflows/ci.yml`) runs Linux, Windows and macOS on every push to `main` and every pull request.
- If the repo goes private: restore `if: github.event_name != 'pull_request'` on the `windows` and `macos` jobs so only Linux runs on PRs, and expect to lose rulesets on the Free plan.

## Using Claude with the repo
- Local: run `claude` in the project folder; it uses `CLAUDE.md`, `.claude/`, and your local `git`/`gh`.
- Cloud sessions (claude.ai/code, Claude app): need GitHub access, via the Claude GitHub App (private repos it is installed on) or `/web-setup` (sends your local `gh` token).
  Cloud builds are limited by the network allowlist, so Gradle builds may fail there; use cloud sessions for docs, reviews and small changes until verified.
  Source: https://code.claude.com/docs/en/claude-code-on-the-web
- `@claude` in issues/PRs (GitHub Action): optional. Set up with `/install-github-app`; it needs a repository secret (`ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`) and costs Actions minutes plus tokens.
  The GitHub App asks for a broad permission set (it is shared by all Claude GitHub features). Source: https://code.claude.com/docs/en/github-actions
  Not needed to start.
