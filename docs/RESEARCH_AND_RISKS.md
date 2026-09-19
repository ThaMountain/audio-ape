# Research, primary sources, feasibility checks and open risks

**Research checked:** 2026-09-19. Links lead to **primary publisher/platform documentation** where possible. A documentation page is evidence for the documented API/policy, **not proof that Audio Ape has used it or that any proposed adapter is authorized to redistribute media**. Version numbers, quotas, license grants, provider auth and store rules must be rechecked before each dependent milestone.

## 1. Primary-source reading list (URLs are embedded directly for Codex)

| Ref | Source and exact URL | Relevant engineering finding / decision |
|---|---|---|
| R01 | Android Media3 `MediaLibraryService` https://developer.android.com/media/media3/session/serve-content | MediaLibrarySession exposes a navigable library; use for future car integration. |
| R02 | Android Media3 background playback https://developer.android.com/media/media3/session/background-playback | Player/session belong in service; foreground media notification, media controller, playback resumption require explicit implementation. |
| R03 | Android for Cars https://developer.android.com/media/implement/surfaces/cars | Android Auto builds on MediaLibraryService/Session with driver-safe browsable tree; full UX future. |
| R04 | Media3 supported formats https://developer.android.com/media/media3/exoplayer/supported-formats | Support depends on container + decoder, not extension alone; test codec/device combinations. |
| R05 | Android Storage Access Framework https://developer.android.com/training/data-storage/shared/documents-files | User-selected document tree permits app-controlled files outside app-specific directory; files can remain after uninstall. |
| R06 | Android 11 storage directory limitations https://developer.android.com/about/versions/11/privacy/storage | Cannot select root or Downloads root via ACTION_OPEN_DOCUMENT_TREE on targeted Android 11+; don't promise predetermined path. |
| R07 | Android user-initiated data transfer https://developer.android.com/develop/background-work/background-tasks/uidt | API34+ JobScheduler UIDT for long user-initiated transfers, visibility/allowed-start constraints, notification requirements; select version-specific fallback. |
| R08 | Android offline-first architecture https://developer.android.com/topic/architecture/data-layer/offline-first | Local data immediately; network disconnected from core playback and library render. |
| R09 | Google Play IP policy https://support.google.com/googleplay/android-developer/answer/9888072 | No inducing infringement; unauthorized copyrighted download/cover promotion can bar distribution. |
| R10 | Google Play device and network abuse policy https://support.google.com/googleplay/android-developer/answer/16559646 | App updates via Play; downloaded dex/JAR/.so restricted, runtime interpreted code cannot bypass platform rules. Data-only bounded plugin reduces code-loading risk, not a blanket approval. |
| R11 | Apple App Review Guidelines https://developer.apple.com/app-store/review/guidelines/ | Sections 2.5.2 and 4.7 affect software/plugins supplied after install; iOS feasibility needs targeted review, no guarantee. |
| R12 | GitHub Releases REST API https://docs.github.com/en/rest/releases/releases | Public release asset discovery/metadata with API rate limits; GitHub releases are not magically safe just because HTTPS. |
| R13 | Android dynamic-code-loading security https://developer.android.com/privacy-and-security/risks/dynamic-code-loading | Avoid code loading where possible; integrity and trusted location vital. |
| R14 | Android ZIP path traversal https://developer.android.com/privacy-and-security/risks/zip-path-traversal | Validate all archive member target paths before extracting; similar risks for other formats. |
| R15 | Compose accessibility touch targets https://developer.android.com/codelabs/basic-android-kotlin-compose-test-accessibility | Minimum 48dp clickable target, semantics required for core controls. |
| R16 | Android Auto Backup + extraction rules https://developer.android.com/identity/data/autobackup | Default backup includes DB/prefs; `allowBackup=false` may not disable some OEM D2D transfers on modern Android; explicit extraction exclusions plus actual tests. |
| R17 | Media3 audio processors https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor | Silence skipping and gain primitives exist; full automatic loudness normalization/voice boost/EQ still need actual DSP feasibility and audio tests. |
| R18 | LibriVox official audiobook API https://librivox.org/api/info | Actual audiobook records, audio-first starter source and title/author/genre queries. Verify current use guidance/quotas/rights before large-scale requests. |
| R19 | Open Library APIs https://openlibrary.org/developers/api | Broad book metadata, editions/works; NOT proof of audio format nor unrestricted catalog replication rights. |
| R20 | Open Library Search API https://openlibrary.org/dev/docs/api/search | Work/edition search, source/provenance; use as optional enrichment only unless audio identity independently corroborated. |
| R21 | Open Library Covers API https://openlibrary.org/dev/docs/api/covers | Avoid crawling cover API; use intended displayed cover URLs/credit and respect policies. |
| R22 | Real-Debrid official API landing https://api.real-debrid.com/ | Verify current OAuth/device authorization and torrent/unrestrict endpoints through live docs and authorized credentials. Presence of API != copyright authorization. |
| R23 | TorBox official developer terms https://torbox.app/policies/api-developer-terms | Review permissions, API use and redistribution for specific adapter, never assume debrid service is a content license. |
| R24 | TorBox API collection https://www.postman.com/torbox/torbox-api/collection/b6l9hbv/main-api | Useful endpoint reference; independently verify publisher/current contract and test authorized sample. |
| R25 | Google Books Volume object https://developers.google.com/books/docs/v1/reference/volumes | `printType` BOOK/MAGAZINE is not audiobook evidence; don't use as sole Discover catalog. |
| R26 | Baseline Profiles in Compose https://developer.android.com/develop/ui/compose/performance/baseline-profiles | Create measured profile for launch/scroll and benchmark before/after; no subjective 'snappy' proof. |
| R27 | Room persistence releases https://developer.android.com/jetpack/androidx/releases/room | Choose current stable compatible Room version, migrations and testing; avoid stale hardcoding. |
| R28 | GitHub Actions Gradle CI https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle | Install JDK, use wrapper/setup-gradle, inspect action provenance and pin third-party actions where possible. |
| R29 | Android app backup manifest semantics https://developer.android.com/guide/topics/manifest/application-element | Backup false can still permit some OEM D2D; privacy goal must be evaluated with transfer rules and real testing. |
| R30 | Android car media testing https://developer.android.com/training/cars/testing/dhu | Desktop Head Unit test for full Auto milestone when prioritized. |
| R31 | GitHub artifact attestations https://docs.github.com/en/actions/concepts/security/artifact-attestations | Optional verifiable release provenance, not substitute for correct signing/reproducibility. |
| R32 | Android audio focus guidance https://developer.android.com/media/optimize/audio-focus | Configure content type/focus; explicit no-auto-resume behavior must be tested on OS/device. |

**What was *not* established by this research:** no authenticated Audiobook Bay -> Real-Debrid/TorBox end-to-end test, no permission for a given Audiobook Bay book, no official Audiobook Bay stable developer API, no tested schema/version of Real-Debrid/TorBox responses, no confirmed commercial audio-only metadata provider with global charts, no final Apple plugin approval, no measured processor/battery benchmark, no direct permission to publish the generated ape logo or fictional book covers commercially. The detailed older plan is a draft, not a substitute for these tests.

## 2. Audiobook Bay / debrid-specific feasibility protocol (conditional)

**Source site:** https://audiobookbay.lu/ (not a general-purpose documented official API verified here). User's requested source. Treat page layout, account/torrent/magnet mechanics, robots/terms, content rights, anti-bot/captcha, region/legal restrictions and rate limits as **unknown until documented with permission and a legitimate sample**. Do not assume a public info hash allows authorized downloading or account bypass. Avoid automated mass scraping; never work around login, anti-bot or paywall controls.

**Steps for a legitimately authorized proof:** (1) establish test item's rights and an acceptable test listing; (2) document permitted discovery/search path; (3) verify edition/narrator/title/size against release detail; (4) obtain provider-authorized torrent/magnet identifier *through allowed access*; (5) authenticate to chosen service using documented provider-sanctioned flow; (6) inspect provider-reported file list/cache state and select actual audio files; (7) resolve short-lived download links and use staged transfer under user consent; (8) verify SHA if available or actual file readability/completeness; (9) import and annotate edition confidence; (10) demonstrate expired link, no seeds, 401, provider timeout, wrong edition, deleted job and partial archive handling. Keep sample logs redacted. A plugin is approved for release only after separate compliance sign-off.

**Separation of concerns:** Audiobook Bay adapter = discovery + source release metadata; Real-Debrid and TorBox adapters = provider authorization + torrent job + selected files + expiring resolved URLs; Audio Ape core = transfer, verify, unpack and file placement. If APIs differ, expose typed host operations and provider adapters; do not collapse everything into a privileged 'AudiobookBayDebrid' plugin. Expose only permissions/hosts needed for each.

**No unconditional implementation promise:** If the source's stable permitted API cannot be supported by a finite declarative interpreter, create a written go/no-go: support a host-side reviewed parser in a signed app update, seek source cooperation/API access, or do not ship. Do not silently convert to executable sideloading against user priorities or store rules.

## 3. Risk / decision log (owner approval needed when triggered)

| ID | Risk | Evidence needed / safe fallback | Release rule |
|---|---|---|---|
| Q01 | primary catalog not confirming audio editions | audit 10+ provider records with verified audio IDs; LibriVox starter possible | never quietly show print/ebook records |
| Q02 | no credible new releases/popularity | actual provider chart/audio-edition dates; fallback 'Recently added at [provider]' | no invented global rankings |
| Q03 | no legal/stable Audiobook Bay integration | rights/terms/API + authorized fixture + device tests; legal test adapter instead | exclude blocked plugin from public release |
| Q04 | unbounded data plugin grammar | malicious fixtures, host capability map, deny-by-default parser | no unrestricted executable plugins |
| Q05 | third-party signing key compromise | signed registry + pinned trust + revocation/rollback plan | halt affected plugin auto-updates |
| Q06 | Apple compatibility uncertainty | Swift-side interpreter spike, policy review and UI/API mapping | portability goal only |
| Q07 | provider host redirected to LAN/local URLs | DNS+redirect protections and signed URL exceptions reviewed | reject unexpected hosts |
| Q08 | archive codec licensing/zip bomb | license audit, traversal/size/time constraints | defer RAR/7z if unproven |
| Q09 | Android device-to-device restoring history | explicit extraction rules and OEM device test, avoid sidecars | document unavoidable OEM limits |
| Q10 | SAF provider is cloud-backed/read-only or slow | test create/read/write/delete, reconcile import journal | prompt different storage, never lose source |
| Q11 | permissions/notification denied | actual Android-version lifecycle tests | degradation with truthful status |
| Q12 | audio DSP hurts battery/quality | instrumented audio and power tests | no fake effect toggle |
| Q13 | generated book art/logo publishing rights | asset inventory and permission/license approval | approved assets only in release |
| Q14 | GitHub -> Play signing mismatch | identity/signing ADR and test upgrade path | don't promise seamless migration |
| Q15 | original older Audio Ape repo unclear | explicit canonical repo from owner, read-only inventory | no unapproved source rewrite |
| Q16 | exact chip of current Android/Compose version unverified | official stable BOM/AGP matrix and compile smoke test | pin versions AFTER successful build |

## 4. External data and privacy map

- User's local audiobook files: stored in their chosen shared/document tree and can remain on uninstall; no implicit cloud mirroring by Audio Ape.
- Position/bookmarks/notes/collections, deleted/completed flags: app-private DB only, excluded from Android backups/device transfers as far as platform rules permit. No hidden personal sidecars in book folders.
- Catalog and metadata requests: book title/edition/author and device network information disclosed only to provider whose terms user accepted; no unnecessary listening-history upload. Cache TTL respects provider terms.
- Plugin credentials: per-plugin logical vault in private encrypted storage, no plugin cross-read, no analytics/screenshot/log backup. Provider may know account/token; disclose when connecting.
- Debug reports: redacted local-only until user previews and initiates share. 'Anonymous' is not guaranteed even with redaction: device/OS/timestamps can be identifying, so minimize.

## 5. Requirements-to-research index

- Player/media/Android Auto: R01–R04,R17,R30,R32.
- Shared user storage/uninstall/backups: R05,R06,R16,R29.
- Download jobs/reliability and archives: R07,R14.
- Plugins/security/platform distribution: R09–R13,R22–R24.
- Catalog and cover truthfulness: R18–R21,R25.
- Quality/CI: R15,R26–R28,R31.
