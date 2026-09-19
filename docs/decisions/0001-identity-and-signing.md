# ADR-0001 — Repository identity, package identifier and signing lineage

**Status:** Accepted (2026-09-19) · **Owner:** Nick · **Tickets:** AA-001, AA-004

## Context
Audio Ape must have a stable, human-decided identity that signing and app lineage can be
built on. These choices are expensive to change after distribution begins, so they are recorded
now rather than inferred.

## Decisions
1. **Canonical repository:** `ThaMountain/audio-ape` — public (owner decision: everything open
   source). Tracking remote configured; default branch `main`. Development happens on short
   `aa/###-short-name` branches with PRs into `main` and stops at each gate G0–G9.
2. **Application / package identifier:** `com.audioape.player`.
   - `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29` (proposal per spec §4.1; reassess against
     the target physical device during G0 / AA-007).
3. **Distribution lineage:** GitHub release is the first public delivery (signed APK + SHA-256 +
   hashes per G9). Google Play is a **separate, reviewed step** (AA-071) and does NOT inherit the
   GitHub signing automatically — Play App Signing uses its own key lineage.
4. **Signing keys:** generated locally, protected, and **never committed** to the repository,
   CI, or logs (spec §6). Debug builds use the Android Studio-style debug keystore only for local
   development. A release keystore plan and protected storage location are owner decisions to be
   confirmed before the first distributed release (ticket AA-004 / AA-069).

## Consequences
- Repo name and package id are now fixed and traceable through the build (`app/build.gradle.kts`).
- No plaintext keys, secret properties, or media will be committed (`.gitignore` covers
  `*.jks`, `*.keystore`, `secrets.properties`, `local.properties`, audio extensions).
- Package id is authoritative for signing lineage; changing it later breaks in-place upgrades.

## Owner gate remaining
- [ ] Confirm signature of this ADR and proceed with AA-007 build shell + CI verification.
- [ ] Record the physical Android test phone model/OS before final minSdk/target resolution.