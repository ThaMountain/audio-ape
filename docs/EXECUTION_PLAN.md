# Audio Ape — exact sequential execution plan / Codex tickets

**Scope:** new Android app, not an automatic update of the earlier repository. **State of every ticket at handoff:** NOT STARTED. Every action below is an instruction for future implementation, not a claim that it was run. Do NOT skip a gate by generating pretty screenshots. Use `../AGENTS.md` reporting protocol.

## Working conventions

Each ticket gets issue ID `AA-###`, a small branch `aa/###-short-name`, tests, artifact and risk note. At each gate: (1) confirm requirements trace; (2) list files changed and actual command outputs; (3) attach screenshot/device/build logs; (4) explain untested functions; (5) stop for owner review. Pin current stable compatible Android Studio/AGP/Kotlin/JDK/Compose/Media3 versions **after** checking official compatibility rather than blindly copying versions in a handoff. Example commands are contingent on a real Gradle wrapper existing.

### Phase 0: establish the foundation and resolve blocking facts

**AA-001 — Fresh project ownership.** Ask owner for canonical new GitHub repository or explicit permission to create one. If existing repo supplied, inventory it read-only; don't edit/replace existing project without owner decision. Create repo folder structure, README, AGENTS.md, docs and `.gitignore` avoiding signing keys, secrets, audiobook media. Done: Git repository, owner approval, clean baseline recorded.

**AA-002 — Asset audit.** Import all 10 reference PNGs into `/design/reference/` preserving hashes, measure their viewport dimensions, extract palette/spacing/contrast and typography samples. Identify that no isolated vector ape logo exists; plan separately approved mascot/icon asset with valid rights. Done: asset manifest (`filename`, sha256, dimensions, origin, approved use), annotated visual comparison sheet.

**AA-003 — Toolchain resolution.** On intended Linux machine install Android Studio stable, Android SDK platform-tools/build tools, matching AGP/Kotlin/JDK and emulator via official docs; record `java -version`, `adb version`, `adb devices`, Gradle and Android SDK versions. Do not assume user's devices/emulator are accessible until confirmed. Done: local toolchain matrix and real-phone debug USB connection confirmed; if unavailable mark device tests blocked.

**AA-004 — Signing and identity.** Determine available app package identifier and naming/trademark; plan stable package/signing history for GitHub first and future Play App Signing. Generate signing keys securely only when authorized; no plaintext private keys in repository/CI. Done: ADR identity/signing, safe protected key storage agreed.

**AA-005 — Catalog reality test.** Research an audiobook-specific primary catalog's actual API/sample responses, date fields, terms, rate quotas, audiobook-edition evidence, narrator, art license and covers. Evaluate LibriVox as authorized starter and any broader provider only if permissioned. Reject Open Library/Google Books work records as sole proof of audio edition. Done: 10+ real fixture records with provider-proven audio identity, source attribution, tests for non-audio rejection; a truthful rail capability matrix. If commercial/new-release claims cannot be verified, omit/honestly label.

**AA-006 — Acquisition legality/API checkpoint.** Define legally distributable sample; check Audiobook Bay site terms, copyright authorization, provider conditions and Play policy. Inspect current official Real-Debrid/TorBox API docs and credential flow; avoid site rate circumvention. Create an explicit 'investigation only / ship approved / blocked' ADR. Done: written API/rights review and legal fixture; do not ship source plugin merely because it downloads.

**AA-007 — Build shell.** Create Gradle Kotlin DSL with version catalog, application module, Compose Activity, minSdk proposal API29 adjusted to verified requirements, current target/compile SDK. Add stripped-down dark UI and SplashScreen without blocking network. Done: `./gradlew :app:assembleDebug` succeeds on dev setup, installs on real phone, start/exit passes, no branded design claim yet.

**AA-008 — Quality harness.** Configure ktlint/formatter, Android lint, unit tests, dependency/license inventory, room schema exports, CI Gradle cache with limited permissions and no secrets from untrusted PRs, screenshot test harness and Macrobenchmark skeleton. Done: CI runs `./gradlew lintDebug testDebugUnitTest assembleDebug` or resolved task equivalents, explains skipped instrumented tests, captures artifacts; no ignoring failed tests.

**G0 EXIT:** repository/toolchain/brand asset inventory, legal sample, catalog gate, first build on real phone and working CI. Stop before expanding scope.

### Phase 1: storage and player vertical slice (mandatory local-only capability)

**AA-009 — Pure domain IDs/models.** Build `Work`, `AudioEdition`, `LibraryBook`, `MediaPart`, `Chapter`, `Checkpoint` as immutable Kotlin types; separate media edition from printed work; duration/URI validation. Test UUID aliases and two different narrator editions.

**AA-010 — Database base schema.** Add Room entities/DAOs/migrations, indexed FKs, `MediaPart` ordering, user-private completion and positions. Export version-1 schema JSON. Unit tests for constraints, cascades, checkpoint writes, re-open; no `fallbackToDestructiveMigration`.

**AA-011 — Storage picker.** Build first-run select/create user-accessible audiobook folder with SAF, persisted URI read/write grants and permission capability check; reject forbidden storage root/Downloads root and handle provider refusal. No media deletion. Test on Android 11+ real provider and emulator; check folder remains visible in file manager.

**AA-012 — Initial import consent.** Ask first whether to import existing media; introduce separate source URI picker; copy fixture into chosen target using streams; original stays untouched. Test denied/canceled grants and revocation.

**AA-013 — Playback service.** Create one MediaLibraryService/Session and ExoPlayer in service; Activity uses MediaController; appropriate foreground media permissions/notification; browse root stub for future Auto. Test launch, screen-off continuing, lock-screen play/pause, click notification to player.

**AA-014 — Two-part book.** Import authorized two-part fixture into Room with known duration; order by reliable track metadata rather than lexicographic filenames alone; feed Media3 playlist. Verify timeline mapping and no extra intentional app delay at transition; document encoded gap if fixture includes it.

**AA-015 — Checkpoint and paused restore.** Save periodically and on pause/seek, restore last position paused after process killed/relaunch. Test OS force-stop limitation vs normal process death, phone reboot and external hardware play; no unsolicited audio.

**AA-016 — Uninstall survival contract.** Add `allowBackup=false` + data extraction and legacy full-backup exclusions, excluding DB/prefs/vault; test uninstall on disposable library, external audio survives, reconnect same tree with fresh app and fresh progress/notes/bookmarks. Test D2D transfer if device pair available; mark OEM unknown rather than promising absolute prevention.

**G1 EXIT:** actual external media survives uninstall; local audio plays screen-off; no plugin or Internet needed; app-private progress does NOT restore. Provide real-device video/test log.

### Phase 2: critical plugin acquisition proof before full catalog/UI investment

**AA-017 — Formal capability table.** Define v0 JSON schema for metadata, source discovery, provider resolver, typed auth; show which host-provided finite primitives support Audiobook Bay-style lookup. Determine whether site-specific parsing requires built-in host adapter; no arbitrary remote scripts. Done: explicit supported/not-supported matrix and safety review.

**AA-018 — Fake/legal source.** Implement fixed fixture source returning one confirmed public-domain audiobook with 2 releases. Plugin cannot directly render UI or modify Room. Test shape/error/empty and distinct plugin-scoped results. Done: `:plugin:protocol` + `:plugin:fixtures` pure-JVM modules (typed boundary shapes, capability-gated `fixture.catalog/sources/resolve.v0` primitives, checksum-pinned Heart of Darkness record from `contracts/catalog-librivox-fixtures.json`, isolation + pairing + immutability JVM tests). See `docs/decisions/0006-plugin-protocol-boundary.md`.

**AA-019 — Fixture resolver.** Implement fixture acquisition provider that returns a stable permissioned audio download; map source release -> provider job/files -> URL. Test expiring-link simulation, 401, 429, wrong edition, absent files. Done: `:plugin:protocol` `DownloadDescriptor`/`ResolvedDownload`, `ProviderJob(Status)`/`ProviderFile`/`ResolveSession(Step)`, typed 401/429 rejections + `RetryPolicy` (no tight-loop retry, bounded backoff), and a deterministic `FixtureResolverSimulator` plus static `prepareSource/queryStatus/selectFiles/resolveLink` covering all failure states — pure JVM, no host engine/network.

**AA-020 — Secure vault prototype.** Typed plugin authorization screen; Android Keystore-backed encryption for plugin-specific alias (implementation from maintained current library/Android APIs); ensure ciphertext only at rest, keys not logged/backed up; simulate invalidation and re-auth. Show host-private logical isolation accurately. Done: `:plugin:host` vault (per-plugin AES-256 Keystore keys, AES/GCM ciphertext-only at rest, typed `VaultResult`, deterministic invalidation seam + re-auth, namespace isolation by construction, no crypto dependency — `androidx.security:security-crypto` deprecated upstream, direct Keystore + JCA chosen). Permission-sheet UI is AA-023; no `:app` wiring. Decision: `docs/decisions/0007-plugin-credential-vault.md`; evidence: `docs/testing/AA-020-vault.md`.

**AA-021 — Download vertical slice.** Download authorized zipped multi-part sample into private staging, verify size/hash and extraction paths, convert to managed tree, commit Room record. Simulate crash at every journal step and recover without losing originals/duplicating items. No unverified torrent tests. Done: `:core:download` pure-JVM engine (streaming stage + SHA-256, v1 line manifest, safe extraction with traversal/duplicate/size/ratio rejection, managed-tree commit that NEVER overwrites, write-ahead journal + 11-checkpoint crash matrix, completion marker, journal-as-hint recovery), `RoomDownloadCommitter` (idempotent transactional commit; `bookId` PK + UNIQUE `(book_id, part_order)` backstop), debug-only loopback-HTTP E2E on the emulator (real two-part download → Room record; idempotent replay; torn-journal + marker-loss recovery preserves rows; garbage journal refused). Headless: 202 tests / 0 failures across 8 modules; E2E 1/1 PASS. Evidence: `docs/testing/AA-021-download-slice.md`. Managed files are app-private this slice; user-visible placement is post-AA-021.

**AA-022 — Source-specific feasibility spike.** On an expressly permissioned public-domain Audiobook Bay listing *if available*, test real search/detail/torrent metadata against site rules; inspect current Real-Debrid/TorBox official auth and transfer responses using user's voluntarily configured test credentials only when available. Do not ingest copyrighted titles or embed API keys. If site forbids API/scraping or authorized media unavailable, label the specific adapter blocked and preserve generic legal fixture proof.

**AA-023 — Parser/security review.** Test host allowlist, redirect domain, private-IP denial, payload limits, signing/manifest tamper, ZIP slip/bomb, cross-plugin credentials. Done: intentional malicious fixtures rejected and recorded.

**G2 EXIT:** source->plugin->verified book import demonstrated with legal sample and data-only interpreter. Audiobook Bay/debrid provider marked separately proven or blocked; absence of access/legal proof is NOT a pass for that adapter.

### Phase 3: design system and native screens

**AA-024 — Design token library.** Implement palette, semantic colors, typography, shape, insets, icon sizing, motion, 48dp controls and screenshot fixtures. No generic Material purple. Compare palette to original PNG with annotated intentional changes.

**AA-025 — Launcher/brand.** Establish approved clean ape-with-headphones asset from authorized source; vectorize/isolate and make adaptive icon foreground/background/monochrome when possible. No sharing third-party font files. Use placeholder wordmark only until actual brand asset approved.

**AA-026 — Navigation shell.** Implement proposal Discover/Library/Now Playing/Downloads/Plugins, Settings via menu. At 320dp evaluate five-tab crowding and get approval of alternative if needed. Test back stack preservation and full-screen Now Playing collapse.

**AA-027 — Discover shells.** Build search field, featured and rails with fixture **audiobook** data, skeleton/empty/offline cases, fake ratings intentionally absent. Submit-only search test verifies zero network calls on each keypress.

**AA-028 — Library screen.** Native cover grid, persistent grouping selector, stable sorted list, completed text/dimming, progress bar, named collections entry. Screenshot compare to `02_library.png` with fixtures and check long titles.

**AA-029 — Player shell.** Real controller state drives art, title, current chapter, skip, pause/play, speed and timer, bottom Up Next card; no fake responsive controls. Implement precision preview with chapter title/time; fixture image screenshot compare.

**AA-030 — Details and plugin choice.** Cover/author/narrator/description, actual local state, background per-plugin discovery. Build missing plugin-selection step (not pictured); one plugin only in result route.

**AA-031 — Source results.** Reinterpret sources mockup as single selected plugin, detailed truthful technical fields, preference match highlight and download actions; missing values explicit. No global combined list.

**AA-032 — Downloads, chapters, plugins, settings.** Implement all as stateful functional shells including queued/error/empty; permission diff sheet; no Profile/accounts or fictional auth state. Compare screenshots on 360/393dp + compact landscape.

**G3 EXIT:** ten visual references mapped to native Compose screens and corrected written behaviors; screenshots, touch paths and screenshots test evidence attached. Do not mark finish if mockup copied as one raster.

### Phase 4: deep playback capabilities

**AA-033 — Media format fixtures.** Assemble authorized formats M4B/MP3/M4A/FLAC/OGG/WAV with container+codec manifest; probe actual decoders; clear unsupported/DRM error. No promise all Opus/WAV variants work.

**AA-034 — Chapter extraction.** Add tested parser adapters for metadata types found in fixtures; sort/validate spans and names, do not fabricate titles. Add online exact-edition lookup adapter interface with always-confirm flow, and +/-30m fallback switch.

**AA-035 — Full-book position and precision scrub.** Implement binary-search timeline, drag preview, commit-on-up and cancel behavior; test boundaries 0, chapter edge, part edge, >24h, unknown duration and moving between parts.

**AA-036 — Smart rewind and speed.** Implement time-since-last-active thresholds with toggle; per-book speed and pitch preservation verified by audio tests, not just a label. Restore true checkpoint before one-time rewind only on explicit play.

**AA-037 — Sleep/bookmarks.** Presets/custom/end-of-chapter; supported notification extension with tested behavior and paused countdown policy. Bookmarks optional name/notes, jump/edit/delete.

**AA-038 — Device integration.** Implement route removal/noisy signal, pause/wait on focus interruption/reconnect, Bluetooth media buttons, notifications, reboot state and session browsing root. Test real headphones/car as available.

**AA-039 — DSP prototype/gate.** Explore normalization/skip silence/voice boost/EQ using actual supported Media3 or other license-compatible APIs. Validate sound, clipping, rate, offload and battery. Do not ship dummy controls: disable/defer any impossible effect with documented owner decision.

**AA-040 — End-of-book.** Stop, completed display, next-series offer, 30-day keep/delete local prompt, conservative 90–95% only when final-content ending identifiable or user marks finished; manual 'mark unfinished'.

**G4 EXIT:** complete player device test matrix, chapter fallback, notifications, 2h screen-off soak, actual audio fidelity checks and no accidental loudspeaker spill.

### Phase 5: library reliability

**AA-041 — Multi-file detection.** Parse tags and track numbers, create stable part ordering, create one book; files remain independent with seamless playback. Detect conflicting narrator/author/series and prompt.

**AA-042 — Safe archive import.** ZIP validated with entry canonicalization, maximum expanded bytes and ratio, nested depth, time cap and zip bomb tests. Additional RAR/7z only after decoder license/security proof; unsupported files get readable warning.

**AA-043 — Metadata resolver.** Embedded/local -> permitted online match -> filename fallback; provenance and per-field confidence; user override wins; rename/move only Audio Ape-owned media with reversible journal; online art rights tracked.

**AA-044 — Incremental scan.** Cached rows render immediately, bounded URI comparison off main, prompt full expensive scan if necessary, manual reindex, permission reconnect. Test 500 and 5k synthetic records without app first frame blocked.

**AA-045 — Series / groups / search.** None/Series/Authors grouping; publication order and sourced recommended order; FTS title/author/narrator/series; combined activity sort. Test ties and missing metadata.

**AA-046 — Collections and duplicates.** Multi-membership; SHA exact and edition candidate detection; keep both/replace/cancel; replacement moves checkpoints/bookmarks only after equivalence proof, original preserved on failed migration.

**AA-047 — Delete/history.** Single irreversible book deletion confirm; remove only managed owned media and cover, leave minimal local tombstone, filter finished in Discover. Test shared file ref and revoked permission failure; uninstall wipes tombstones.

**G5 EXIT:** no unintended file loss across rescan/import/replace/delete/reboot; canceled import leaves usable originals; output files visible via file manager.

### Phase 6: real audiobook catalog and plugin ecosystem

**AA-048 — Confirm source provider.** Use Phase 0 proven audiobook-only API; implement pagination, cache TTL, attribution and provider host quotas; reject generic printed-book results. New Releases/Popular rails only if source provides verified data; otherwise truthful substitute/omit.

**AA-049 — Discover complete.** Search submit, details online fields, recommendations author/genre/next series, finished filtering, deleted label; offline cached behavior, stale/unknown fields explicit.

**AA-050 — Plugin registry.** Static signed registry hosted on approved GitHub Pages/Releases, signed manifest and publisher keys, searchable cards, last verified metadata and pinned key. Test tampered registry/replay/version rollback and offline cached listing.

**AA-051 — Install/update.** Allowlist HTTPS/hosts, fetch staged data-only package, validate size/schema/hash/signature/compatibility/consent, smoke test, atomic active-pointer swap with rollback. Updates default automatic when scopes unchanged; user opt-out.

**AA-052 — Advanced GitHub sideload.** Enable Advanced preference then separate explicit per-plugin permissions consent; accept a defined GitHub release artifact or local package, not arbitrary repository JavaScript or arbitrary ZIP file. Reject missing manifest/unknown schema/oversized/unsigned where configured; truthful unverified badge.

**AA-053 — Typed credentials.** Plugin-specific config/settings rendered by host, provider OAuth/token exchange only with official supported methods; secure vault cleanup and scope checks. Repermission on domain/capability expansion, repeated-error auto-disable and nonblocking notification.

**AA-054 — Per-plugin prefetch and selection.** Book details warms only eligible installed plugins with bounded concurrent requests; Get Book shows each status; selected plugin results remain isolated and sort according to global + per-plugin overrides.

**AA-055 — Audiobook Bay/provider checkpoint.** If AA-006/AA-022 passed, complete a specifically authorized Audiobook Bay source plugin and independently Real-Debrid/TorBox resolver under current terms. If not, maintain a blocked ticket, no hidden integration or misleading release claim. Still fully test generic plugin system with legal fixtures.

**G6 EXIT:** actual audiobook-specific search, honest metadata, signed store and third-party permission flow, package isolation/rollback, one authorized download source.

### Phase 7: production transfer/notification reliability

**AA-056 — Queue transitions.** Build Room-backed typed states, transaction owner, slot scheduler 2 default/3 optional; decompose transfer, verify/extract/import concurrency.

**AA-057 — Android jobs.** Evaluate user-initiated data transfer API34+ and valid older foreground alternatives on target SDK. Short jobs via WorkManager only. Real background/lock screen/reboot tests; notifications with true state/action PendingIntents and Android notification permission behavior.

**AA-058 — Resume/retry.** HTTP Range/ETag correctness, signed URL refresh, Retry-After and bounded exponential retry, auth failures to reauthorization; source-switch only after matching edition/narrator/language and selected plugin. Never download unexpected huge payload without budget check.

**AA-059 — Storage/connection constraints.** Wi-Fi only default/cellular opt-in, metered transitions, free-space estimated preflight + margin, low-space prompt/no auto cleanup, active queue persistence.

**AA-060 — Processing stages.** Partial archive cleanup, file count/extraction/verification, journal reconciliation; stage-specific expandable diagnostics, overall percent/ETA only meaningful when denominator known.

**AA-061 — Notification and surface consistency.** Persistent progress with pause/resume/cancel, in-app identical job state and completed/failure alerts. Test notification click opens corresponding job, revoked permission and duplicate action no-op.

**G7 EXIT:** download + import survives process loss, denied notifications, Wi-Fi switch and low storage without library corruption; no jobs falsely Completed.

### Phase 8: security, licensing, performance, beta

**AA-062 — Threat-model review.** Enumerate plugin package, registry, credentials, redirect/DNS/IP, archive paths/size, SAF provider identity, downloader partial data, logging and CI supply chain. Add malicious fixtures for each.

**AA-063 — Legal/rights clearance.** Confirm logo use, covers, fonts, licenses, each third-party service rights and download authorization, publisher agreement, distribution-specific Play/Apple requirements. Explicitly decide not to distribute any source adapter whose authorization is unclear.

**AA-064 — Crash reporting privacy.** Local rotated redacted logs, user-facing preview/edit and share sheet, no default remote upload, retention and clear action. Test secret scan on exports, backup exclusion and no third-party analytics SDK.

**AA-065 — Benchmarks.** Release-build Macrobenchmark cold startup + library scroll + player open, memory/CPU screen-off, 500/5k book stress, three-transfer load, optional DSP power impact, Baseline Profile benefit measured vs baseline.

**AA-066 — UI and usability.** Screenshot comparison every screen; test 320/360/393/600dp, landscape, large font, system bars, long multilingual titles, contrast, basic TalkBack descriptions, 48dp effective hit targets, reduced motion.

**AA-067 — Accessibility future-ready audit.** Essential audio functions must already be labelled/operable; write remaining deeper accessibility roadmap without claiming fully compliant release until tested. Address any release-blocking inaccessible essential control now.

**AA-068 — Beta protocol.** Release candidate for invited testers using legal media only; structured reproducible issues, opt-in redacted diagnostics, prioritization security/data-loss > playback > UX > polish. Prevent regression via each fixture.

**G8 EXIT:** no open critical data loss/security/audio focus bugs, source rights and brand rights documented, device matrix and benchmark artifacts, privacy backup tests.

### Phase 9: GitHub release and future lanes

**AA-069 — GitHub release.** Signed release APK from protected tag/branch, versionName/versionCode, reproducible dependency snapshot, SHA-256 checksum, license notices, privacy disclosure, install/update instructions, tested upgrade from preceding beta. Never claim release built before commands succeed.

**AA-070 — Source/feature transparency.** Publish tested format/container/codec matrix, confirmed catalog data limits, plugin trust explanations, blocked provider descriptions, notification and storage limitations, clear uninstall semantics.

**AA-071 — Future Play readiness ADR.** Audit exact target API/declarations, code loading, IP policy, signing lineage and official app bundle build; do not publish until compliant. Version differences may require feature availability changes that must be explained to owner.

**AA-072 — Future Android Auto/iOS/account seams.** Keep parked until core launch; future tickets require separate architecture/security review. Never call the current Android app 'iOS-ready' solely because it has JSON plugins.

**G9 EXIT:** owner-reviewed released APK is actually downloadable, signed/tested, technical/legal limitations accurately stated, source/test artifacts and release checksum accessible.

## Test coverage matrix (mandatory end-to-end journeys)

| ID | Steps | Required observation |
|---|---|---|
| E2E-01 | fresh install -> select child Documents tree -> import sample | source unmodified, user-accessible copy created, app library row appears |
| E2E-02 | play two-part -> screen off -> transition -> pause -> process loss -> reopen | uninterrupted logical order, bounded checkpoint restore PAUSED |
| E2E-03 | unplug headset and reconnect | immediate pause; never speaker spill or auto-resume |
| E2E-04 | uninstall and reinstall -> reconnect same folder | audio files remain; no former notes/bookmarks/completion/progress/credentials restored |
| E2E-05 | Discover type query without submit -> press Search | no network before submit; only confirmed audio editions |
| E2E-06 | select book details -> Get Book -> select plugin -> source | per-plugin prefetch status, only selected plugin's truthful releases |
| E2E-07 | advanced GitHub plugin install | two approvals, sandbox restrictions and signature/status correctly shown |
| E2E-08 | authorized source -> provider -> download -> verify -> import | one correctly identified playable book; no leaked tokens |
| E2E-09 | disconnect Internet/low storage/mid-download kill | queue persists, bounded retries and no false Completed |
| E2E-10 | malicious ZIP and redirect manifest | extraction/host authorization rejects without file overwrite |
| E2E-11 | online chapters exact edition match -> cancel or approve | no silent application; quick switch to +/-30min |
| E2E-12 | delete one book -> confirm | only selected managed files gone; local Discover history; after uninstall history gone |
| E2E-13 | narrator/abridged mismatch on source retry | no silent wrong-edition switch |
| E2E-14 | plugin crashes repeatedly | plugin disabled with notice; local playback unaffected |
| E2E-15 | 2-hour screen-off player and 500-book grid | benchmark metrics recorded, not invented |

## Daily Codex progress report template

```text
Ticket AA-___: <name>; requirements covered: [IDs]
Changed files: ...
Actual commands executed: ...
Tests: unit ...; instrumented ...; real phone model/OS ...; screenshots ...
What works: ...
Known limitations / unverified claims: ...
Risk, data migration, rights/policy implications: ...
Proposed next ticket: ...
Owner decision required: ...
```

**Do not skip a gate or silently reprioritize because an AI can code faster than the test process.**
