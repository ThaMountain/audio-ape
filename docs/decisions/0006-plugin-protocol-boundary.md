# AA-018 · Audio Ape — Plugin Protocol Boundary & Fixture Source (v0 typed shapes)

**Owner:** Nick · **Status:** IMPLEMENTED on branch `aa/018-fixture-source` (not merged) · **Gate:** Phase 2 proof that the v0 capability table is buildable; feeds AA-019/AA-020/AA-023
**Source-of-truth refs:** `docs/architecture/plugin-capability-table.md` §2/§3/§4,
`docs/ARCHITECTURE_AND_CONTRACTS.md` §E/§F/§G, `contracts/plugin-manifest.schema.json`,
`docs/EXECUTION_PLAN.md` AA-018 (and AA-017/AA-019 context), `AGENTS.md` rules 4/5/10/11/12.

## 1. Decision

Audio Ape's v0 plugin stack gets two new **pure-JVM** Gradle modules:

- `:plugin:protocol` — the strongly-typed, immutable boundary objects the host and any
  plugin primitive exchange (`CatalogItem`, `EditionMetadataField/Type` +
  `EditionMetadata`, `SourceReleaseCandidate`/`Match`/`Uncertain`/`Candidates`,
  `CredentialGrant`, `AcquisitionPlan` + `ReleaseFile`/`Provenance`/`MatchConfidence`,
  `PluginResult`/`PluginRejection`/`PluginErrorCode`, `PluginCapability`).
- `:plugin:fixtures` — the bundled, checksum-pinned, network-free fixture source
  implementing exactly the three v0 primitives the demo manifest declares:
  `fixture.catalog.v0`, `fixture.sources.v0`, `fixture.resolve.v0`.

Both modules deliberately **depend on nothing except the Kotlin/JVM standard library
(and `:plugin:protocol` for fixtures)**. The fixture modules must not import
`:core:database`, `:core:storage`, `:app`, Room, SAF, Android, or the player. The
capability `PluginCapability` enum mirrors the schema's closed capability enum
one-to-one; this is the first executable expression of the pairing table (§3).

## 2. Typed envelope shape (host boundary)

Every primitive returns `PluginResult<T>` — an immutable envelope carrying **exactly
one** of:

- `value: T` — the typed payload (never Android/Room/SAF/player types), or
- `rejection: PluginRejection(code, detail?)` — a typed error (`PluginErrorCode` closed
  enum) with an optional parameter-free human string.

**No exceptions cross the boundary.** Constructor-time validation uses Kotlin JVM
assertions (`require(...)`, same style as `:core:model`), so callers cannot even build
an inconsistent envelope. `detail` never carries credentials, tokens, paths, or URLs.

## 3. Uncertainty semantics (source_search)

Mirrors capability table §2.3 exactly: `SourceReleaseCandidates` carries **two lists** —
`matches[]` (usable; each carries `SourceMatchUncertainty.NONE`) and `uncertain[]`
(each carries explicit, nonzero uncertainty). A release never falls into both. This lets
the host offer confident matches and label uncertain ones truthfully instead of guessing.
Unknown optional fields (bitrate, part count, size, container, codec, title, URL) stay
`null`; nothing is fabricated.

## 4. Capability gating

`FixtureCapabilityGate` pairs each primitive with exactly one capability
(`fixture.catalog.v0 -> catalog`, `fixture.sources.v0 -> source_search`,
`fixture.resolve.v0 -> acquisition_resolver`). Every `FixtureSourceService` call checks
the held capability first and returns a typed `CAPABILITY_MISMATCH` rejection otherwise.
This is the runtime twin of the schema-level pairing battery from AA-017; the host
interpreter (AA-023) will re-enforce the same rule at its own boundary.

## 5. Fixture data integrity

The single catalog record is **Heart of Darkness** (LibriVox id 64), copied from
`contracts/catalog-librivox-fixtures.json` (12 real records; see
`docs/decisions/0002-catalog-provider-librivox.md` for the legal permission and
attribution register). The fixture module pins the SHA-256 of that repository file
(`72fe32fe…c928e9`, 2026-09-19) and a test re-hashes the file, so any drift fails CI.
Two distinct releases are emitted (`mp3-64kbps-parts` — 6 parts; `mp3-64kbps-zip` —
single zip, `partCount = null`), each with real archive.org source URLs, and `null`
for every field the source did not state. Resolve is deterministic; unknown release ids
return a typed `NOT_FOUND` rejection, unknown editions return a **typed empty** match
list (never an error).

## 6. Isolation proof

3. **Structural:** `:plugin:fixtures` declares only `:plugin:protocol`; neither module
   references `:core:*`, `:app`, or any Android/Gradle-Android plugin.
4. **Reflective:** `FixtureIsolationTest` walks the module's compiled `.class` output
   and asserts no class constant pool references `android.`, `androidx.room.`,
   `androidx.documentfile.`, `androidx.datastore.`, `com.audioape.core.database.`,
   `com.audioape.core.storage.`, or `com.audioape.player.`.

## 7. Out of scope (honest limits; not built here)

- No host interpreter/engine (AA-023), no manifest loader, no real browser/catalog
  search, no UI, no downloads, no Room/SAF/player touch, no network primitives.
- `EditionMetadata`/`CredentialGrant` protocol shapes are present (they are the rest of
  the capability enum) but **no** `fixture.metadata.v0`/`fixture.auth.v0` primitive is
  implemented — that is AA-020.
- Real-debrid/TorBox/Audiobook Bay remain out of scope (AA-022 gate; see ADR-0004).
- No Android runtime or device has exercised these modules (pure JVM only), by design.

## 8. AA-019 resolver addendum (fixture acquisition resolver, implemented)

- **`ResolvedDownload`/`DownloadDescriptor`**: §G `resolveLink`'s short-lived HTTPS URL
  plus `expiresAt` and an optional deterministic `sha256`. `AcquisitionPlan.download`
  (nullable) carries it; when set, its URL must be the direct URL of one plan file (the
  host never treats a null URL as fetchable media). Fixture links are expiring on
  purpose and the fixture expiry is a named forward constant so the simulation behaves
  identically in CI and on real clocks.
- **Transfer surfaces**: `ProviderJobStatus` (READY/PENDING/ERROR), `ProviderJob`,
  `ProviderFile`, `ResolveSession` + `ResolveSessionStep`
  (PREPARE_SOURCE → QUERY_STATUS → SELECT_FILES → RESOLVE_LINK).
- **Typed failures**: `pluginAuthRequired()` (401 ⇒ `FORBIDDEN`, never auto-retried) and
  `pluginRateLimited(retryAfterMs)` (429 ⇒ `LIMIT_EXCEEDED` with a bounded
  `RateLimitRejection`); `RetryPolicy` enforces the "no tight-loop retry" rule and caps
  provider `Retry-After` at 300 s with a 3-attempt budget. `DeferredDownload` is the
  fixture-honest bridge: the static resolver produces the job, the host's provider
  worker produces the link (AA-021).
- **Failure-state simulation**: `FixtureResolverSimulator` (injectable
  `FixtureSimulatorSession` + `FixtureResolverClock`) and the static
  `FixtureSourceService.prepareSource/queryStatus/selectFiles/resolveLink` cover
  expired links, 401, 429, wrong-edition (typed mismatch, never silent substitution),
  and absent files (typed empty).
