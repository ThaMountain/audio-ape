# Plugin capability table — Audio Ape v0 protocol design

- Status: **DESIGN + CONTRACTS (AA-017) + pure-JVM boundary proof (AA-018)**. No plugin
  engine, no Android runtime, no UI.
- Scope: one diagram of truth for what a data-only plugin may express through the v0
  declarative grammar described in `docs/ARCHITECTURE_AND_CONTRACTS.md` §F, plus the
  host-enforced finite primitives that back it. Runtime enforcement of every row lands in
  `plugin/host` (execution plan AA-023); the typed boundary shapes and the
  `fixture.*.v0` primitives are implemented in `plugin/protocol` + `plugin/fixtures`
  (AA-018, see `docs/decisions/0006-plugin-protocol-boundary.md`).
- Contract artifact: `contracts/plugin-manifest.schema.json` (schemaVersion 0) and the
  fixture manifest `contracts/fixture-demo-plugin.json`.
- Threat-model context: `docs/ARCHITECTURE_AND_CONTRACTS.md` §F "Risk controls",
  `docs/RESEARCH_AND_RISKS.md` R09–R13, R22–R24, Q03/Q04/Q07,
  `docs/architecture/plugin-safety-review.md`.

## 1. Ground rules (invariants of the v0 protocol)

1. **Data only.** A plugin is JSON data + optional licensed logo; it ships no code, and
   the host never evaluates plugin-supplied logic.
2. **Finite host primitives.** Every network/parse side effect runs inside a host
   primitive with explicit budgets (bytes, time, count, depth). A manifest can only
   compose primitives; it can never define one.
3. **Explicit capability grant.** A manifest must declare at least one capability from
   the closed enum, declare permission hosts, and declare one allowed operation per
   declared capability. No capability is implied.
4. **Host-op-to-capability policy.** An operation is only recognized for capabilities
   listed in §3. A manifest may not assign `http.get_json.v0` to `acquisition_resolver`
   in v0 (a resolver returns descriptors through typed host actions, never a raw fetch).
5. **Deny by default.** Everything not named in `allowedHosts`, not shaped by an allowed
   host primitive, and not in the typed result schema is rejected at parse or runtime.
6. **Vault by grant.** `requiresOwnVault: true` only admits typed auth actions for the
   plugin's own `(pluginId, credentialId)`; it never unlocks raw secrets.

## 2. Capability catalog

Each capability below states the typed objects it **produces** and **consumes**, the
finite host primitives it may use, and its hard exclusions. Typed object names are
conceptual contracts for the host's strongly typed Kotlin boundary (see
`docs/ARCHITECTURE_AND_CONTRACTS.md` §A1/§F); they are not free-form JSON.

### 2.1 `catalog` — confirmed-audio edition candidate lists

- Produces: `catalog.SearchResult{ items: CatalogItem[] }` where
  `CatalogItem(workAliases, editionAliases, author, narrator?, language?,
  audioPublicationDate?, description?, coverUrl?, provenance,
  confirmedAudioEvidence)` — shape from architecture §E. `confirmedAudioEvidence`
  must be non-empty and provider-specific.
- Consumes: user-typed query fields and bounded paging cursor: `CatalogQuery{ term,
  page }`.
- Host primitives allowed: `fixture.catalog.v0` (static fixture); `http.get_json.v0`
  (single GET, JSON response) **only** against `permissions.allowedHosts` literal
  HTTPS hostnames.
- Cannot: write Room/SAF, start downloads, render UI, run code/JS, follow arbitrary
  redirect hosts, fetch an unbounded body, or mutate another plugin's data.

### 2.2 `metadata` — typed edition augmentation

- Produces: `EditionMetadata(backendEditionId, typedFields[], requestedBy,
  reviewed:false)`; fields are constrained to the fixed typed set (title, author,
  narrator, language, publication date, description, series, cover URL). Values are
  plain strings/URIs/bools — never HTML injected into UI.
- Consumes: exact `EditionLookup{ editionId, providerField }` plus the query string a
  user explicitly submitted.
- Host primitives allowed: `fixture.metadata.v0`; `http.get_json.v0` against
  `allowedHosts`.
- Cannot: enrich fields not requested, run arbitrary selectors, execute
  JS/HTML/templates, or fetch URLs taken verbatim from response bodies (§F).

### 2.3 `source_search` — match an edition against a source's release index

- Produces: `SourceReleaseCandidates{ matches[], uncertain[] }` where a match binds an
  `AudioEdition` key to a source release id and labels uncertain fields explicitly when
  the source did not return them.
- Consumes: confirmed edition evidence and the user-consented source query.
- Host primitives allowed: `fixture.sources.v0`; `http.get_json.v0` against
  `allowedHosts` — **only** if the source exposes a documented, stable, permissioned
  discovery endpoint (search + detail JSON). No scraping, no login bypass, no
  anti-bot evasion.
- Cannot: resolve magnets/torrents, authenticate to debrid services (that is
  `authorization` + `acquisition_resolver`), or read file lists.
- **Verdict (Audiobook Bay): see §6 — NOT expressible in v0 without a built-in,
  host-reviewed adapter. The generic primitives cannot parse the site's HTML.**

### 2.4 `authorization` — typed token acquisition

- Produces: `CredentialGrant(credentialId, scopes[], expiresAt?)` stored by the host in
  the app-private logical vault (Android Keystore-backed material, see §F "Vault").
- Consumes: `AuthRequest(provider, scopes)` composed from the plugin's
  `credentialSchema`-shaped stored settings.
- Host primitives allowed: `authorization.oauth.device.v0` (device-flow token exchange
  against an official provider endpoint in `allowedHosts`) and `fixture.auth.v0`
  (deterministic fixture token). These are **typed auth actions**; the host executes the
  provider flow, the plugin never touches the raw token.
- Cannot: read/write the vault directly, receive another plugin's secrets, put tokens
  in URLs/query strings, logs, webviews, screenshots or backup, or forward tokens
  across hosts.

### 2.5 `acquisition_resolver` — translate a grant + release into a download descriptor

- Produces: `AcquisitionPlan(providerJob?, fileManifest?, expiringUrl?)` bounded to one
  release and its files, matching `docs/ARCHITECTURE_AND_CONTRACTS.md` §G
  (`resolveLink` returns a short-lived HTTPS URL validated against `allowedHosts`).
- Consumes: `CredentialRef(credentialId)` (reference only — never the token) and the
  selected source release id.
- Host primitives allowed: `resolver.debrid.job.v0` (create/query a transfer job) and
  `resolver.debrid.files.v0` (select files / resolve expiring links) **for provider
  adapters**, or `fixture.resolve.v0`. Endpoints are typed and host-named, not a raw
  fetch.
- Cannot: trigger downloads itself (`core` transfer owns that), exceed the file/size
  budget, or substitute media without equivalence proof (§F / §4.3 acquisition retry).

## 3. Declared capability → allowed operations (mandatory pairing)

The v0 manifest `operations[]` entries must pair each declared capability with an
operation the host recognizes for it. Table: capability × `hostOperation` allowed.

| Capability | Allowed host operations (v0) |
|---|---|
| `catalog` | `http.get_json.v0`, `fixture.catalog.v0` |
| `metadata` | `http.get_json.v0`, `fixture.metadata.v0` |
| `source_search` | `http.get_json.v0`, `fixture.sources.v0` |
| `authorization` | `authorization.oauth.device.v0`, `fixture.auth.v0` |
| `acquisition_resolver` | `resolver.debrid.job.v0`, `resolver.debrid.files.v0`, `fixture.resolve.v0` |

Schema note: draft-2020-12 cannot express "same enum member appears in two arrays" with
a portable subschema, so pairing is enforced at parse time by the validator test battery
(same fixture set as the schema tests) and later in the host loader (AA-018/AA-023).
The schema therefore constrains each operation's `capability` to the closed enum and
constrains `hostOperation` to the closed finite enum; the test battery guarantees the
pairing invariants this document asserts.

## 4. Finite host primitives catalog (v0)

| Primitive | Kind | Input | Output | Host rules (all enforced at runtime) |
|---|---|---|---|---|
| `http.get_json.v0` | network GET | literal pathTemplate + fixed query, per-op `host` from `allowedHosts` | bounded JSON value | literal HTTPS host; DNS+redirect revalidation (§6 of master spec); TLS; maxResponseBytes; timeoutMs cap; request count/rate caps; content-type JSON; depth/nesting cap; no regex templates |
| `fixture.catalog.v0` | static data | none (or fixture key) | `CatalogItem[]` | bundled, checksum-pinned, no network |
| `fixture.metadata.v0` | static data | edition id | typed fields | bundled, no network |
| `fixture.sources.v0` | static data | edition key | `SourceReleaseCandidate[]` | bundled, no network |
| `fixture.auth.v0` | typed auth | fixed token (test) | `CredentialGrant` | fixture-only, no network, never in logs |
| `fixture.resolve.v0` | static data | release id | `AcquisitionPlan` with stable fixture URL | bundled, no network |
| `authorization.oauth.device.v0` | typed auth action | provider, scopes | `CredentialGrant` | official endpoint only, host-executed flow, PKCE, token to vault only |
| `resolver.debrid.job.v0` | typed provider action | release id, auth ref | providerJob + status | provider adapter in host with official API contract, rate/backoff caps |
| `resolver.debrid.files.v0` | typed provider action | job id, selected file ids | fileManifest + expiring URLs | file list/size caps, host-validated URLs, allowlist re-check |

## 5. What the grammar CANNOT do (hard ceilings)

| # | Forbidden | Blocks abuse / reason |
|---|---|---|
| C1 | No code of any kind (no JS, dex, JAR, native lib, macro) | PLG-001/PLG-005, R10, R13 |
| C2 | No plugin UI (no Compose, no webview) | PLG-001; UI is Audio Ape's |
| C3 | No Room/SAF/player access | PLG-004: plugin returns immutable structured data only |
| C4 | No raw intents / host API surface | manifests name primitives, not internals |
| C5 | No unbounded fetch (body, time, count, rate) | bounded primitives table above |
| C6 | No regex execution, templates, or conditionals | §6. T08 |
| C7 | No loops beyond host budget, no recursion/pipelines | §6. T09 |
| C8 | No arbitrary redirect hosts / cross-host token forwarding | §6. T03/T04 |
| C9 | No scraping / anti-bot bypass / login evasion | research protocol §2 |
| C10 | No raw secrets access; vault is host-private + namespaced | §6. T06 |
| C11 | No capability implied without an entry in `operations[]` | grant must be explicit |
| C12 | No wildcard/private/loopback hosts in `allowedHosts` | §6. T02 |

## 6. Verdict: is Audiobook Bay-style source discovery expressible in v0?

**No — a site-specific built-in host adapter is required; mark the Audiobook Bay source
adapter BLOCKED at the v0 grammar level (one built-in, signed, reviewed adapter is a
possible later design tradeoff, not a v0 plugin feature).**

### 6.1 Why not with `http.get_json.v0` alone

1. `http.get_json.v0` is a single GET whose response must be JSON with a depth/nesting
   cap. Audiobook Bay's discovery surfaces are HTML pages with embedded markup; a JSON
   endpoint is not documented and not permitted to be assumed (research protocol §2:
   "Do not hardcode fields based solely on mockups or a stale third-party blog").
2. Search → detail → release evidence is a multi-step chain with extraction and
   re-request logic. The v0 grammar forbids request chaining "outside a finite host
   library" and forbids arbitrary parsing (master spec §6: static definitions "must not
   be powerful enough to replicate arbitrary code through conditionals/loops/regex").
   A finite host library for _one_ site is a host adapter, not a grammar feature.
3. Interactive fields (login/captcha/anti-bot) are out of scope for a declarative
   data-only plugin by explicit decision (PLG-001, research protocol §2).
4. The dual-role design still separates cleanly: an Audiobook Bay **host adapter**
   (discovery + source release metadata) and a Real-Debrid/TorBox **provider adapter**
   (auth + torrent job + files + expiring links). These are the independent contracts
   PLG-001 names; they do not imply one privileged "AudiobookBayDebrid" plugin.

### 6.2 What the v0 grammar proves instead

- The **generic interface shape** is proven by the legal fixture source
  (`fixture.*.v0`, AA-018/AA-019) and by any permissioned provider that exposes the
  typed JSON endpoints v0 expects (e.g., a cooperative JSON API or a LibriVox-style
  verified audio index, subject to its current terms — see decision
  `docs/decisions/0002-catalog-provider-librivox.md`).
- The **Audiobook Bay-specific adapter** remains gated on: authorized test listing,
  documented permitted discovery path, terms/robots review, provider API confirmation,
  and reviewed host-side parser in a signed app update (or documented out). Until the
  gate passes (AA-022, Q03), the adapter is **not complete and not shipped** — matching
  AGENTS.md rule 12.
- Decision record pointer: `docs/decisions/0004-acquisition-feasibility.md` (and this
  table's §6.1 supersedes no research findings; it formalizes them).

## 7. Support matrix — capability × host primitive

Legend: **Y** = allowed in v0 manifest; **N** = forbidden (schema enum excludes it or
the loader rejects it); **H** = host-side primitive (manifest may not name it); "(r)"
= requires `requiresOwnVault: true` + typed auth action.

| Capability \ primitive | `http.get_json.v0` | `fixture.*.v0` | `authorization.*.v0` | `resolver.*.v0` |
|---|---|---|---|---|
| `catalog` | Y | Y (catalog) | N | N |
| `metadata` | Y | Y (metadata) | N | N |
| `source_search` | Y | Y (sources) | N | N |
| `authorization` | N | Y (auth, fixture-only) | Y (oauth.device, r) | N |
| `acquisition_resolver` | N | Y (resolve) | N | Y (job+files, r) |

Not expressible by any v0 manifest: read/write Room or SAF, player control, OS file
access, arbitrary hosts/URLs, code/UI/raw intents, and Audiobook Bay HTML discovery
without its built-in host adapter.

## 8. Open items and next tickets

- AA-018: **implemented** — `:plugin:protocol` typed boundary shapes and the
  `fixture.catalog.v0`/`fixture.sources.v0`/`fixture.resolve.v0` primitives now build
  and test as pure JVM (capability-gated, checksum-pinned, isolation-tested). See
  `docs/decisions/0006-plugin-protocol-boundary.md`.
- AA-019: fixture resolver/download descriptor refinement (expiring-link simulation,
  401/429, wrong edition, absent files) — not built here.
- AA-020: vault prototype; confirms `authorization`/`requiresOwnVault` semantics.
- AA-022/AA-023: Audiobook Bay/debrid go-no-go and the abuse-case battery that this
  table promises ("intentional malicious fixtures rejected and recorded").
- AA-055: checkpoint for the Audiobook Bay/debrid adapters; blocked until gate passes.
