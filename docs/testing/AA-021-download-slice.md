# AA-021 — Download vertical slice (testing evidence)

Status: **IMPLEMENTED + on-device verified** (2026-09-20) — **PR #16, merged AFTER owner review**
(this file records pre-merge evidence; Nick reviews the implementation, not the summary).

## Scope (owner-bounded)
Authorized multipart archive → app-private staging → size/hash verification → SAFE extraction →
managed tree commit → Room record, with write-ahead journal + deterministic crash recovery.
NOT included (separate gates): Audiobook Bay / Real-Debrid / TorBox adapters, visual redesign,
catalog/UI work.

## What changed

| File | Change |
|---|---|
| `core/download/.../DownloadEngine.kt` | NEW — transfer/verify/extract/commit engine (pure JVM): streaming stage + SHA-256, manifest cross-check, per-part extract with bounds, managed-tree commit without overwrite, journal + completion marker, checkpoint fault seam, fatal/cancellation/interrupt propagation |
| `core/download/.../DownloadJournal.kt` | NEW — write-ahead per-part journal (durable atomic replacement: `.tmp` + rename), torn-line tolerance, inconsistent-journal detection, 11 checkpoints + `SimulatedCrash` |
| `core/download/.../DownloadManifest.kt` | NEW — strict v1 line manifest (grammar-enforced safe filenames), codec |
| `core/download/.../DownloadModels.kt`, `DownloadSafety.kt`, `DownloadCommitter.kt`, `DownloadSource.kt` | NEW — types, limits, name checker, seams |
| `core/database/.../RoomDownloadCommitter.kt` + `DownloadCommitDao` | NEW — transactional idempotent Room commit (bookId PK pre-check + UNIQUE (book_id, part_order) constraint backstop + `@Transaction`) |
| `app/src/debug/AndroidManifest.xml` + `res/xml/network_security_config_debug.xml` | NEW — DEBUG-ONLY INTERNET + loopback-only cleartext (production manifest unchanged) |
| `app/.../download/DownloadVerticalSliceE2ETest.kt` | NEW — real loopback-HTTP two-part E2E on-device |
| CI `ci.yml` | ADDED `:core:download:test` to the explicit module list |

## Headless (all 8 modules, real run)
**202 tests / 0 failures**: model 38, database 14 (incl. RoomDownloadCommitter 3), download 26,
storage 28, protocol 19, fixtures 25, host 29, app 23. `spotlessCheck` + `lintDebug` clean.

`:core:download` breakdown: manifest codec 2, engine behavior 17 (happy path, idempotent second
run, adopt, collision-never-overwrites, archive hash mismatch, staged-corrupt refusal, extra
member, traversal member, wrong bookId, malformed zip, zip-bomb ratio, commit-failure
propagation, missing manifest, torn journal, **corrupt-after-commit recovery**, **garbage
journal refusal**, **tampered-extracted re-extraction**), crash matrix 3 (all 11 checkpoints
converge to fully-committed; marker+stray-staging and marker-without-record inconsistent
states), archive safety 4.

## On-device E2E (emulator dev36, Android 16/API 36) — PASS 1/1
`DownloadVerticalSliceE2ETest` (real network stack over loopback HTTP, real Room DB):
1. **Run 1**: HTTP fetch → staging → size+sha256 verify → manifest cross-check → safe extraction
   → managed tree → Room record. Asserted: 2 managed WAVs hash-match the served bytes,
   `library_book` row present (`userOwned=false`), exactly 2 `media_part` rows.
2. **Run 2**: verified no-op (`alreadyComplete=true`, row counts unchanged).
3. **Run 3**: journal corrupted to torn-but-parseable lines + marker deleted → recovery
   converges; file content + file count + DB row counts UNCHANGED; marker recreated.
4. **Run 4**: garbage journal → explicit refusal; row counts still unchanged.

## The reviewer's recovery concern — how corrupted-journal recovery avoids duplicates/overwrites
- **Journal = hint, not truth.** Every resume/reuse/discard decision re-verifies actual artifacts:
  managed-file adopt only on size+hash match (mismatch = explicit failure, NEVER overwrite);
  claimed-EXTRACTED staging files are hash-verified before the move and re-extracted on tamper
  (`tamperedExtractedPartIsReExtractedNotMoved`).
- **Room commit idempotent by identity + constraint + transaction.** Stable canonical `bookId`
  PK + pre-check returns `AlreadyPresent`; `(book_id, part_order)` UNIQUE + `@Transaction`
  aborts atomically on any replay violation (`RoomDownloadCommitterTest` 3/3, incl. a
  duplicate-order replay that leaves zero rows).
- **Torn vs foreign journal distinguished.** Known-prefix short lines → skipped (PENDING;
  recovery re-derives). Unknown-prefix/garbage → explicit refusal (`failedFiles["journal"]`),
  nothing restarted, nothing deleted.
- **Completed downloads survive corruption.** With the marker present, a garbage journal is
  never even consulted (completion is authoritative); without the marker, torn-corruption
  recovery adopts all existing artifacts and re-commits nothing.

## Simulated vs demonstrated on Android
- **Demonstrated (real runs on the emulator):** HTTP transport (loopback), streaming download,
  size + SHA-256 verification, safe zip extraction, managed-tree commit, Room insert via the
  real DAO + constraints, idempotent replay, torn-journal + marker-loss recovery, garbage
  journal refusal, no-duplicate assertions.
- **Simulated (deterministic headless):** process death (fault-injection `SimulatedCrash` at
  all 11 transitions — a real process kill mid-engine is not instrumented on-device this
  ticket), network retry/resume semantics (the engine re-stages from the source; a real
  connection-drop mid-transfer test is future work), hash-verified WAV "playability" (files are
  real PCM WAVs; decode-and-play is AA-013/014 territory, duration is stored null).
- **Not exercised:** physical-device run (emulator-only per policy; phone at major milestones),
  auth'd plugin hand-off (`:plugin:fixtures` resolver → engine), SAF/user-visible placement of
  managed files (downloads currently land in app-private storage — a later placement ticket).

## Known limitations (honest)
- Managed files are app-private `filesDir` for this slice; user-visible folder placement is
  post-AA-021.
- Manifest filenames restricted to `[A-Za-z0-9._-]` (no Unicode/space part names yet).
- Engine blocks (no coroutines); transport stays injected (`DownloadSource` seam) so HTTP
  auth/retries plug in later without engine changes.
- Unverified: physical-device run; network-drop-mid-flight resume; duration decode.

## Security/correctness hardening round (2026-09-20, codex-assigned review of PR #16)

Six findings addressed with regression tests (headless count: **219 tests / 0 failures across 8 modules**: model 38, database 19, download 38, storage 28, protocol 19, fixtures 25, host 29, app 23; spotless + lint + assemble clean; emulator E2E 1/1 PASS):

**1. Block false completion during recovery.** Completion is honored ONLY with proof: expected part identities come from `ResolvedDownloadRequest.expectedParts` (rule 10 changes: requests now carry the validated part list; the archive manifest must exactly match it). The fast path, marker entry, and "already complete" claims all verify every managed file (size + SHA-256) AND the exact Room book + part set (`DownloadCommitter.verify`) before skipping. An empty/incomplete journal can never establish completion; journal claims of completion with nothing provable on disk → explicit failure, no marker, no deletion. Regression tests: `archivedDeletedJournalWithoutRoomRecordFailsExplicitly`, `recordsCommittedJournalMissingManagedFileFailsWithoutMarker`, `completionMarkerWithMismatchedMediaFailsAndPreservesBytes`, `emptyJournalCanNeverEstablishCompletion`, `tornJournalClaimingRecordsCommittedWithNothingOnDiskIsRefused`.

**2. Guarantee no-overwrite publication.** Android forbids hard links (real on-device `AccessDeniedException` caught during E2E — SELinux), so publication uses the platform's no-clobber primitive: journal `PUBLISHING` (write-ahead) → `CREATE_NEW` (O_EXCL) claim → verified copy + fsync → re-verify → `MOVED`. The claim itself fails if the name exists; a competitor's bytes are adopted only on exact match, else collision-failure — never replaced. A wrong-hash dest is OURS to discard only while the journal shows `PUBLISHING` (interrupted copy, recoverable); otherwise it's foreign → collision. Concurrent same-book downloads serialize on a process-wide per-book lock. Regressions: `competingDestinationAppearingBeforePublicationIsNeverOverwritten` (deterministic race: bytes preserved), `concurrentSameBookDownloadsPublishExactlyOneRecord`.

**3. Room replay is identity-aware.** `RoomDownloadCommitter` compares the COMPLETE identity set (title + part order/content URI/bytes/hash) inside one `@Transaction` (`commitOrCompare`): exact replay → `AlreadyPresent` (zero mutation), mismatch/missing parts → `Conflict` (engine fails the run; no marker), no row → insert. Regressions (8 tests): exact replay, book-with-zero-parts, differing hash, differing URI, missing part, differing title, concurrent commits (exactly one insert + one AlreadyPresent), `verify()` rejection cases.

**4. Bound the initial ZIP scan.** Every archive member is now DRAINED through budgeted streaming (entry count ≤ 513, per-entry expanded ≤ 4 GiB, cumulative ≤ 16 GiB, inflate ratio ≤ 200×) before the manifest is trusted; the archive itself is capped by `maxArchiveSizeBytes` enforced during staging AND by request validation (`expectedArchiveSizeBytes <= maxArchiveSizeBytes`). Regressions: `oversizedCompressibleEntryIsRejectedDuringScanWithNoExtraction`, `requestRejectsExpectedSizeAboveConfiguredArchiveCap`.

**5. Book ID validated before filesystem access.** `validateBookId` (canonical UUID) now runs FIRST, before any book path is constructed/read/written; the book dir is confined under `downloadsRoot`. Regression: `invalidBookIdThrowsBeforeAnyFilesystemMutation` (asserts the downloads root is untouched for traversal-style and malformed IDs).

**6. Journal durability contract corrected.** `rewrite()` now: full-temp write → `FileChannel.force(true)` → `ATOMIC_MOVE` (with `AtomicMoveNotSupportedException` → explicit journal failure, no silent downgrade) → best-effort directory fsync. `discard()` also removes stale temp. Regression: `staleJournalTempFileIsInertAndCleaned` (interrupted-write artifact recovery). Documentation: this section + DownloadJournal KDoc.

Preservation guarantees summarized: the engine still only ever operates under `downloadsRoot/<bookId>/`; user media (Nick's audiobook folder) is never touched; collisions preserve foreign bytes byte-identically; completion markers are never written without verified media + verified record.

## Simulated vs demonstrated (hardening round)

- **Simulated (headless, deterministic):** process death at every checkpoint via `SimulatedCrash` (still no real Android process-kill instrumentation — real process-death testing is not available in this environment); the publication race via the checkpoint side-effect hook; concurrent commits via threads.
- **Demonstrated on Android (emulator):** the full two-part loopback-HTTP download E2E (runs 1-5): download → verify → extract → CREATE_NEW publication on real Android FS (exposing + fixing the SELinux hard-link block) → Room record; idempotent replay; torn-journal recovery preserving rows; garbage-journal refusal; marker-with-tampered-media failure preserving tampered bytes.

## The earlier `IndexOutOfBoundsException` (documented, NOT proven)
A transient batch failure (original stack never captured) is **unproven in cause**.
`DownloadJournal`'s unconditional `cols[1]`/`cols[2]` indexing was a CONFIRMED latent defect of
the same exception signature; it is fixed with field-count validation and covered by
`tornJournalLineNeverCausesAnIndexFailure`. The `putIfAbsent` duplicate-detection code was
reviewed and is correct (reverts a mistaken earlier claim).