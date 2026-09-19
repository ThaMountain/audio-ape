# Audio Ape

An **Android-first, offline-first local audiobook player** with a fast, distinctive playback experience and a portable, capability-based, **data-only** plugin ecosystem.

> **Status:** Phase 0 foundation in progress. This is a build in progress, not a released application. All implementation tickets in [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md) follow the gates G0–G9.

## Read first
1. [`START_HERE.md`](START_HERE.md) — handoff orientation
2. [`AGENTS.md`](AGENTS.md) — mandatory operating rules for agents
3. [`AUDIO_APE_MASTER_SPEC.md`](AUDIO_APE_MASTER_SPEC.md) — authoritative product/engineering spec
4. [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md) — ordered tickets, tests, gates

## What this is
- Offline-capable local player with **no accounts, no server, no ads, no telemetry**.
- User-controlled, user-accessible audiobook folder that **survives uninstall** (progress/bookmarks/history stay app-private and are wiped on uninstall).
- Data-only declarative plugins (no arbitrary executable code), plugin-first acquisition UX.
- Signed GitHub release is the first delivery target; Google Play is a separate, reviewed step.

## Developer setup
See [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md) AA-003 / AA-007 for the pinned toolchain and build commands.

## License
Open source. See [`docs/RESEARCH_AND_RISKS.md`](docs/RESEARCH_AND_RISKS.md) and the legal/rights register for the explicit compliance gates before any public distribution.