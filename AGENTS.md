# Instructions for Codex and coding agents

## Mission
Build a new, exceptionally responsive offline-first Android audiobook player called Audio Ape, with a portable, declarative, permission-checked source-plugin ecosystem, matching the bundled visual language. The user wants one-screen-at-a-time quality rather than a disposable prototype.

## Immutable rules
1. Read the whole handoff and report contradictions **before** coding. Do not infer features from fictional mockup text. Use written requirements over artwork.
2. Establish the user's canonical repository and working branch. If a repo already exists, audit it read-only before changes; preserve originals. Do not rewrite history, delete other work, publish releases, or open network accounts without authorization.
3. Do not promise a fully up-to-date commercial audiobook catalog from a generic book API. Use a confirmed audiobook-specific provider or explicit data-quality gate; show a correctly labeled empty/limited Discover otherwise.
4. No arbitrary downloaded APK/dex/JAR/native library, JS eval, script execution, arbitrary host HTTP, OS file access or plugin-controlled Compose UI in the V1 plugin format. An optional third-party GitHub package is a **data-only package** validated by the same interpreter and consent flow as the built-in directory.
5. Separate **source discovery** from **provider acquisition**. Do not ship or promote infringing catalog adapters. Audiobook Bay + Real-Debrid/TorBox is a *conditional feasibility investigation* using authorized samples, provider approval/terms check and distribution review. Do not silently substitute the user’s desired integration with unrelated sources; prove the interface with an openly licensed source while that gate is unresolved.
6. Store audiobook **files** in a user-granted, user-accessible folder that survives uninstall. Keep progress, bookmarks, notes, completion and collections only in app-private data; do not serialize them beside books and do not restore them via Android backup. Uninstall cannot trigger app UI; app deletion confirmation applies to individual books only.
7. Never silently delete/overwrite unrelated original files, erase a local library, replace an edition, apply guessed chapter timings, leak credentials, or upload diagnostics. Archive removal only after integrity-verified successful import and transaction reconciliation.
8. Player remains functional offline and without plugins. Navigation/rendering never waits on network or exhaustive storage traversal. No accounts/server/ads/telemetry by default.
9. Keep the existing generated ape/headphones visual *as a visual reference*. Do not claim you possess an isolated lossless logo/source vector. Obtain or recreate and get explicit approval of a separate legally usable brand asset before final icon publication.
10. Every PR/ticket must include changed files, requirements and tests covered, actual commands and test results, screenshots for UI, performance measurements when relevant, limitations/security concerns, and next task. Never report simulated tests as executed.
11. Never hardcode live credentials, API tokens, private paths or personal media in code, logs, screenshots, fixtures or CI. Public fixtures must be clearly licensed and attributed.
12. On an unresolved policy/API/licensing/accessibility/codec issue: write a decision record, build the nearest safe placeholder/fixture implementation, and mark the blocked deliverable as not complete. Do not quietly drop the requirement.

## Standard implementation loop
Read spec -> identify ONE ticket -> articulate acceptance criteria -> implement minimal cohesive diff -> compile -> unit/instrumentation tests -> real-device validation when required -> screenshot comparison -> update decision/traceability docs -> report results -> stop at gate. Use separate branches/PRs for bounded tasks. Do not make unrelated cosmetic changes.

## Default evidence gate
A feature is done only if its positive path, offline/failure path, data migration or recovery (if applicable), and at least one on-device end-to-end test pass. For features not requiring Android runtime, use fixture-based deterministic tests; do not claim an emulator is a real phone.

## Suggested first response from Codex
A) repository status and available images; B) exact pinned toolchain versions from current official sources, not assumed ones; C) proposed module skeleton; D) Phase 0 tickets and stop points; E) unanswered blockers; F) explicit statement no implementation has yet been validated.
