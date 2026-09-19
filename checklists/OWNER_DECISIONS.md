# Owner decisions and post-prototype approvals

All product decisions from the conversation are recorded in the master spec. These are **not questions Codex should silently answer**; they are proof/approval gates when implementation reaches them.

- [x] Canonical new GitHub repository and app/package identifier confirmed — repo `ThaMountain/audio-ape` (public; owner decided everything open source), package `com.audioape.player` (ADR-0001).
- [ ] Source or newly isolated ape-with-headphones brand icon approved, ownership/licensing checked.
- [x] Actual physical Android test phone model/OS recorded; minSdk/targetSdk resolved — Samsung Galaxy S25 Ultra (SM-S931U), arm64, Android 16, SDK/API 36 = compile/targetSdk 36 (matches device). Wireless adb connected 2026-09-19.
- [ ] Document-tree user-accessible folder first-run picker tested on target phone; user agrees UI wording.
- [ ] Five-tab navigation vs compact alternative approved after 320dp test; never add Profile/login.
- [ ] First provider meets confirmed-audiobook-only requirement and legally permitted artwork/usage; gaps documented.
- [ ] Authoritative current Real-Debrid/TorBox API auth and contract verified with test credentials when owner supplies them; avoid embedding credentials.
- [ ] Audiobook Bay-specific authorized content + site terms + store/IP review approved separately before public integration.
- [ ] Data-only plugin capabilities can express tested integrations without arbitrary code; if not, owner approves limited host adapter or explicit exclusion.
- [ ] Optional DSP normalization/voice boost/EQ/silence skip each demonstrated sonically and in performance traces or explicitly deferred.
- [ ] Google Play signing lineage and policy reviewed before store distribution; GitHub APK first.
- [ ] Open security/data loss defects resolved before first public release.
