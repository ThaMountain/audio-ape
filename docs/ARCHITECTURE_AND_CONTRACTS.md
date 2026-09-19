# Architecture and contracts — data-level detail for implementers

See `../AUDIO_APE_MASTER_SPEC.md` for behavior and `../AGENTS.md` for constraints. This doc gives **proposed interfaces and invariants** rather than falsely claiming a complete compiled Android implementation. Concrete framework method names/manifest requirements must be checked against the selected pinned Media3/Android version at implementation.

## A. Architecture dependency graph

```text
Compose / ViewModels -> application use cases -> pure domain interfaces
                                          |-> Player coordinator -> Media3 service -> ExoPlayer
                                          |-> Catalog -> confirmed audio provider(s)
                                          |-> Plugin manager -> bounded data-only interpreter -> allowlisted HTTP/auth
                                          |-> Downloads -> persisted state machine -> provider transfer -> safe staging
                                          |-> Library -> metadata resolver -> SAF documents + Room
```

Plugins output structured `AudiobookEditionCandidate`, `SourceRelease`, `AcquisitionRequest` and `ResolvedDownload`. A plugin cannot emit code, UI fragments, raw Android `Intent`s, a database handle, a `ContentResolver`, or arbitrary file paths. Host reserves all state mutations and error handling. A source adapter is not automatically a debrid resolver; compose only after the individual interfaces are separately proved.

### A1. Suggested Kotlin interface sketches (conceptual; not copy-paste-compiled)

```kotlin
interface AudioCatalog {
  suspend fun searchAudiobooks(query: String, pageToken: String?): Page<AudioEdition>
  suspend fun edition(id: EditionId): AudioEdition?
  suspend fun recommendations(signals: RecommendationSignals): Page<AudioEdition>
}
interface PluginHost {
  suspend fun compatiblePlugins(edition: AudioEdition): List<PluginSummary>
  fun searchSources(plugin: PluginId, edition: AudioEdition): Flow<SourceSearchState>
  suspend fun resolve(plugin: PluginId, release: SourceRelease): AcquisitionPlan
}
interface LibraryRepository {
  fun observeBooks(grouping: LibraryGrouping): Flow<List<LibraryBook>>
  suspend fun checkpoint(bookId: BookId, positionMs: Long, speed: Float)
  suspend fun importValidated(candidate: ImportCandidate): ImportResult
  suspend fun deleteConfirmed(bookId: BookId): DeleteResult
}
interface PlaybackGateway {
  val state: StateFlow<PlaybackUiState>
  suspend fun open(bookId: BookId, autoplay: Boolean)
  suspend fun play()
  suspend fun pause()
  suspend fun seekBook(positionMs: Long)
}
```

Distinct cancellation scopes: `SupervisorJob` for concurrently refreshing plugins; cancellation of a detail screen search must not cancel an already selected persistent download job. HTTP/CPU work never on Compose main thread. Use structured concurrency and typed error results rather than swallowing exceptions.

## B. Room schema and constraints

Actual schema/migration code must be generated and tested; use entities below as design input. Android `content://` URI strings are opaque; do not normalize as POSIX paths.

| Entity/table | Primary/foreign fields | Hard constraints |
|---|---|---|
| `Work` | `work_id UUID`, title, authors normalized | no narrator in generic Work |
| `AudioEdition` | `edition_id UUID`, `work_id FK`, narrator, language, duration, abridged nullable, publisher, publicationDate, external aliases | provider IDs are typed `(provider,id,type)` and unique together |
| `LibraryBook` | `book_id UUID`, `edition_id? FK`, display title, folder tree URI + document ID, importedAt, lastPlayedAt, finishedAt?, user ownership marker | valid destination grant; no protected private history sidecar |
| `MediaPart` | `part_id UUID`, `book_id FK`, ordered index, content URI, cached duration, SHA256?, bytes?, codec/container | unique `(book_id,part_order)`; >=1 part to play |
| `Chapter` | `chapter_id UUID`, `book_id FK`, startMs/endMs?, title?, provenance, acceptedOnline boolean | increasing valid spans; imported exact-edition match only |
| `PlaybackCheckpoint` | `book_id PK/FK`, canonical positionMs, updatedAt, speed, lastPlayingIntent=false on restore | durable batch and bounds clamp |
| `Bookmark` | `id UUID`, `book_id FK`, positionMs, label?, note?, createdAt | ordered by book position/time; deletion local only |
| `Collection`, `CollectionItem` | IDs, `(collection_id,book_id)` unique | multi-membership |
| `MetadataCandidate`, `UserOverride` | item, field enum, typed value JSON, provenance, editAt | manual overrides supersede resolver updates |
| `BookHistoryTombstone` | normalized/typed edition aliases, finished/deleted flags + timestamps | no retained media, stored only in app-private DB |
| `PluginInstall` | ID/version/signing key fingerprint, origin, active asset digest, previous digest, crash count | activation after validation only |
| `PluginGrant` | `(plugin_id,scope)` + approved hosts, approval time, version of requested grant | deny by default; reapproval on expansion |
| `DownloadJob` | UUID, edition/release/plugin refs, stage enum, bytes, total nullable, ETag nullable, retry policy, staged URI/manifest ID | transactional stage transition, idempotent event processing |
| `ImportJournal` | UUID, job ID, staged destinations, SHA256, commit flags, createdAt | crash reconciliation before cleanup |

Maintain exports of Room schema JSON under version control. Use `MigrationTestHelper` and actual representative historical DB snapshots; never silently `fallbackToDestructiveMigration` for listening state. Add FTS index of title, author, narrator, series with sync triggers/data checks. `LibraryBook` represents an individual audio copy, so same Edition may legitimately have multiple copies if user chose keep both.

### B1. Core transactions

- `markFinished`: save checkpoint/end marker, finishedAt and recommendation filter info in one DB transaction; do NOT delete audio.
- `deleteBook`: user has confirmed exact book, walk only files tagged as owned for that book, verify not referenced by another book; move transaction record through pending deletion; delete media via granted tree and reconcile any errors; preserve history tombstone inside app; only report success after verifying targeted owned files absent. If provider refuses delete, report partial failure and retain actionable record.
- `replaceBook`: verify edition/duration/chapters, commit new media before removing previous owned files; if mapping uncertain, user decides and original stays intact. Book ID mapping can be stable only when semantics verified.
- `importBook`: reserve deterministic destination with collision-resistant safe basename (normalized Unicode, length limits, avoid slashes/device names); metadata is committed after file integrity. Original imports remain untouched.

## C. Storage, install/uninstall and backups

Android 11+ does not allow `ACTION_OPEN_DOCUMENT_TREE` at storage or Downloads root. Provide initial picker with friendly instructions to create or select a child `Audiobooks/Audio Ape`, persist URI permission via `takePersistableUriPermission` only for the flags actually granted, and use `DocumentsContract` / `DocumentFile` and `ContentResolver` streams to create/open/rename children. A document provider may be slow, cloud-backed, read-only, or revoke permissions; explicitly probe capabilities and storage before import. Never pretend every `Uri` maps to `java.io.File`. [R05,R06]

- Persistent library lives in chosen user-accessible tree; only book audio + permitted artwork/folder naming goes there. **Never write progress/bookmarks/history/credentials/settings alongside media.**
- App-private Room, DataStore, encrypted plugin vault, download staging and logs are deleted by standard uninstall; temporary archive files under private cache are also deleted. App must not intercept OS uninstall. If external backup copies independently exist, app cannot delete those.
- Set `android:allowBackup="false"`; ALSO configure data-extraction cloud, device-to-device and any supported transfer categories with explicit exclusions for app data on Android 12+ and legacy full-backup rules, because manufacturer handling of `allowBackup=false` can differ. Run platform/OEM reinstall and device-transfer verification. Some OEM/system backups or manual copies are beyond app control. [R16]
- On reinstall the app has no persisted URI permission; ask user to choose surviving folder; infer media only and rebuild a NEW DB, all positions start fresh. Book cover art/media metadata may remain in files because they aren't personal listening state.

### C1. Import transaction journal pseudocode

```text
onImport(payload):
  calculate available headroom; validate source ownership and length; reserve staging slot
  download/copy bytes to private staging; hash whole payload when sensible
  if archive: stream validate entry names, type, totalExpandedBytes, ratio, fileCount,
      depth, symlinks and duplicate paths; reject traversal and bombs
  parse audio containers off main thread; reject non-playable set with diagnostics
  derive candidate title/author/series/edition and show uncertainty choices
  create IMPORTING journal with destination document IDs / byte expectations
  create file(s) in approved tree with temporary names; stream copy + verify
  verify readable media, metadata, chapter availability and file manifest
  Room transaction: insert edition, book, parts, chapters, cover and checkpoint defaults;
      mark journal COMMITTED and job COMPLETED
  best-effort finalize document names and remove ONLY journal-owned temporary docs
  after durable success remove original verified downloaded archive from private staging
onRestart():
  enumerate nonterminal journals; for each validate staged docs & DB references;
  roll forward or recover safely; never blindly delete root or overwrite original
```

SAF providers may not offer true atomic rename and a partially failed transfer can leave orphan docs. Treat durable journal/reconciliation and verified file ownership as non-optional. Bound recursion, compression ratio, expanded byte sum and timeout. Test `../` and absolute path cases [R14].

## D. Playback session, codec, chapters and audio

Service contains exactly one ExoPlayer instance and a MediaLibrarySession; app UI controllers connect asynchronously and observe state. Declare current appropriate foreground media playback permission/service types and browse compatibility per pinned Media3 official docs. Persist/restore a compact playlist plus book-wide checkpoint using Room. Replay is paused on cold launch/reboot, regardless of whether Android offers media resumption button; explicit user-initiated media commands may start it. Gate controller commands by caller permissions and available commands; ensure Android media notification controller isn't accidentally rejected. [R01–R04]

For continuous multipart playback use ordered `MediaItem`s with next item prepared sufficiently ahead. Tag parser adapters for M4B MP4 `chpl`/chapter tracks where validated; MP3 ID3 CHAP/CTOC; Vorbis/Opus comments where supported; OGG/FLAC wrappers as appropriate. Record extractor coverage by verified fixtures rather than assuming `MetadataRetriever` supports all chapter encodings. Book-wide positions use 64-bit milliseconds and exact prefix sums; convert Media3 current index/local position both ways. Handle unknown duration with pending discovery and disable impossible precision seeks until resolved.

On seek preview, update only draft state at controlled animation rate, compute book chapter via binary search; issue one actual seek on pointer-up. Do not write a DB checkpoint until seek committed; current player position stays unchanged on canceled drag. Clamp at time `0` and `totalMs`; avoid overflow on long books.

Audio focus: set speech content type; use Media3-supported audio focus/noisy handling. Distinguish phone call/nav focus loss and user-generated headset play from automatic focus gain. Suppress spontaneous resume on reconnect or focus regain, but allow actual explicit Bluetooth play command. Per-book speed pitch preservation; audio FX in separate optional DSP chain with gain limit and sample tests. Check audio-offload tradeoffs and maintain an effects-disabled battery path. [R02,R17]

End-of-chapter timer pauses after current chapter boundary, not at random file boundary. Smart rewind applies once to explicit user resume based on time since last listening; no repeated rewind on internal track switches. Use safe class of smart rewind defaults (0/5/10s configurable) and clamp before chapter start.

## E. Discover metadata and recommendations

One primary provider at launch, but **it MUST identify real audiobook editions independently**, with documented usage rights, request limits, search filter accuracy, narrator evidence, edition relationships and update cadence. **Generic Open Library works/editions and Google Books `printType` are not reliable proof of audiobook editions**. Open Library is suitable as auxiliary metadata/cover where licensed; LibriVox is a potential verified audio-only starter, subject to current API terms and coverage. If no primary provider provides trustworthy new-release/popularity data, omit those rails or label as `Recently added by [provider]` not 'Global Popular'. [R18–R21]

Catalog adapter normalized return: `AudioEdition(id, workAliases, editionAliases, author, narrator?, language?, audioPublicationDate?, description?, coverUrl?, provenance, confirmedAudioEvidence)`. `confirmedAudioEvidence` must be nonempty and validate source-specific logic (e.g., audiobook content type/track download API or curated provider listing). Log request failures only after redaction; rate-limit, cache with TTL and respect no-crawl artwork rules. Display metadata attribution/link per provider.

Recommendation V1 deterministic: eligible = confirmed-audio titles not in finished tombstones and not already in current library unless next-series; candidate score for **sorting only** = series-next 5, same author 3, shared genre 1, recently viewed optional 0 (no telemetry); stable tie-break publication date if source credible, otherwise title. The math is only a local catalog ordering heuristic, not an objective rating or prediction. No model or server; limit API requests; no personal listening data sent unless provider request inherently includes query chosen by user.

## F. Plugin protocol and credential model

**Package:** a ZIP of small JSON data files and optional publicly licensed logo/icon; never JS, dex, JAR, native libs, executable macros or templating power equivalent to arbitrary scripts. Host implements all operations named in the schema. `plugin-manifest.schema.json` in package is an **illustrative v0 design artifact**, NOT an audited security boundary. Freeze actual v1 only after abuse-case tests. Example permissions are narrowly enumerated, network hostnames literal HTTPS and immutable per version until re-consent. No plugin code running under independent UID in V1.

Capabilities:

- `catalog`: returns confirmed-audio objects from supported hosts/GET endpoints.
- `metadata`: fetches typed fields for exact edition IDs, not arbitrary raw HTML injection into UI.
- `source_search`: matches AudioEdition against source release index/search on allowed hosts, fields labelled uncertain when not returned.
- `authorization`: typed user-consent OAuth/provider-supported token action; plugin-specific vault alias and scopes.
- `acquisition_resolver`: translates an authorized source token/release to an ephemeral download descriptor or provider job, using approved host operations.

Declarative interpretation must **not** allow user-supplied regex execution without timeout/limits, recursive pipelines, arbitrary loops, request chaining outside finite host library, HTML script execution, cross-origin token injection, or unrestricted arbitrary URL fetch from a response. The host may need approved site-specific parsing *built into a signed app release*; this is a design tradeoff vs universal source flexibility. Build a capability table and formal go/no-go for desired source before publishing V1 schema.

**Install:** `repository URL -> find explicit manifest at documented release asset -> fetch to private staging -> size/bomb limits -> parse canonical JSON -> verify digest/signature/trust -> compare permission diff -> consent -> smoke test -> activate pointer -> retain last-known-good rollback`. Third-party packages: advanced toggle + per-install confirmation, lower-trust label, same hard runtime restrictions. Curated: registry signed key embedded in host; plugin publisher signature verified using pinned accepted key or transparent explicit trust-on-first-use for advanced sources. No automatic updates that introduce new scopes without repermission. Maintain audit trail of installed digest and previous version, not raw API keys.

**Vault:** host-private encrypted bytes keyed by `(pluginId,credentialId)`, Android Keystore-managed key material, typed access methods only. The plugin logically controls auth lifecycle via schema, but does not have its own OS-private storage. Review Android Keystore API and actively maintained encryption wrappers at implementation time. Do not use a deprecated crypto library blindly. Tokens never enter webview, URL query string, redirect cross-host, analytics, logs, screenshot fixture, or backup.

**Risk controls:** allowed domains strictly checked after DNS and each redirect; public HTTPS only by default, no loopback, link-local, private-address or metadata-service targets. Cap request count, concurrency, downloaded bytes, decompression, response depth, JSON nesting, regex runtime, retries and background refresh. Validate scheme and content-type. Permit plugin-specific domains only after install grant; deny newly introduced redirect hosts. Disable repeatedly failing plugin and keep playback unaffected. Verify signature schemes against a vetted Ed25519 implementation and canonical JSON rules before finalizing.

## G. Download/transfer state machine

Allowed simplified transitions:

```text
QUEUED -> RESOLVING -> WAITING_PROVIDER -> TRANSFERRING -> VERIFYING
                                                        -> EXTRACTING? -> IDENTIFYING -> IMPORTING -> COMPLETED
              \-> WAITING_WIFI / WAITING_STORAGE / RETRY_SCHEDULED / PAUSED
              \-> FAILED / CANCELLED
```

Each state transition is a persisted event with idempotent handler and a coordinator that owns slot allocations. `resume` from paused returns to appropriate previous stage and validates file identity; `cancel` stops OS worker and only deletes its app-private staged files/journal-owned temporary docs. Failed validation must not enter Completed. On app/UI process death, a separate recovery worker queries persisted jobs and reconciles OS job handles; do not incorrectly schedule user-initiated Android 14+ jobs from a disallowed background context [R07].

Transfer provider interface:

```text
prepareSource(release,auth) -> provider-job or signed ephemeral URL metadata
queryStatus(providerJob) -> READY / PENDING / ERROR (+ file list/expiry)
selectFiles(providerJob, matching media) -> approved file manifest
resolveLink(fileId) -> expiring HTTPS URL, host allowlist, optional length/hash
openTransfer(url,Range?,If-Range?) -> HTTP response with verified identity
```

Any provider API endpoint/parameter/auth behavior must come from its *current official documentation* and an authorized test; don't hardcode fields based solely on the mockups or a stale third-party blog. Rate limits and caching need their own provider adapter policy. Where no stable API exists, preserve a blocked integration interface instead of scraping aggressively/evading logins/rate limits.

**Notification:** ongoing user-initiated downloads display Android progress notification with explicit immutable action pending intents for `pause`, `resume`, `cancel`; actions validate job UUID and current state. Android 13+ notification permission prompt where relevant; if denied, follow required OS foreground job/system UI behavior and clearly inform user. Never claim a custom notification action guaranteed to appear on every device/Android Auto.

## H. Tests, CI and release engineering

Unit: time-index mapping including huge durations, stable sorting/grouping, edition canonicalization, metadata overrides, downloader transitions, retry cap, manifest canonicalization, signature verification, host allowlist/private IP, archive traversal/bomb checks and in-memory journal reconciliation. Instrumented: SAF provider test double + real documents provider; Room migration snapshots; Media3 focus, noisy/bt interruptions; UI navigation screenshot fixtures; long-lived transfer process death.

Device acceptance: Android min version emulator plus current Android emulator and at least one actual mid-range phone; audio/Bluetooth car device when available; headphone disconnect, notification denial, battery saver, 3 simultaneous downloads, low storage, reboot/force-stop, uninstall-reinstall with user-managed library, blocked source URL, wrong edition fallback.

Quality gates: test artifacts attached to PR, no blindly `ignoreFailures`, no manual patch to generated release APK, dependency verification/lockfile where feasible; release signing keys protected; GitHub release tagged/versioned with hashes and license notices. Play publishing requires separate policy and signing review; GitHub is first delivery. [R12,R23,R24]
