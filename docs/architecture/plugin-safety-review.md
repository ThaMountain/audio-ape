# Plugin safety review — abuse classes vs. v0 schema/host rules (AA-017)

- Purpose: enumerate the abuse classes the audio-ape v0 declarative grammar must resist,
  and record for each class the **schema-level** rule that blocks it and the
  **host-level** rule (AA-023 battery) that enforces it where a JSON schema alone
  cannot.
- Status: DESIGN + CONTRACTS only. The runtime checks below are scheduled AA-023
  ("intentional malicious fixtures rejected and recorded") and are **not yet
  implemented or executed**.
- Related: `docs/ARCHITECTURE_AND_CONTRACTS.md` §F risk controls, master spec §6,
  `docs/architecture/plugin-capability-table.md` §§4–5, `docs/RESEARCH_AND_RISKS.md`
  Q03/Q04/Q07, R11.

## 1. Orientation

A manifest is validated in two stages, and both must pass before any primitive runs:

1. **Parsing stage (schema)** — `contracts/plugin-manifest.schema.json`. Rejects
   shape-level abuse (bad hosts, unknown ops, extra properties, oversized budgets).
2. **Execution stage (host)** — allowlist, DNS/redirect revalidation, budgets,
   isolation, vault rules. NOT a plugin choice; the host always enforces it.

"NOT enforceable at schema level" below means the schema cannot observe runtime data
(redirect targets, resolved IPs, response bytes after decompression, per-run timing),
so the listed item is **HOST-enforced (AA-023)** and must land in the abuse-case test
battery.

## 2. Abuse-class table

| # | Abuse class | Attack shape | Schema rule (blocks at parse) | Host rule (AA-023; enforcement point) |
|---|---|---|---|---|
| T01 | SSRF to private/localhost/metadata service | allowedHosts typo or later host reuse resolving to 127.0.0.1 / LAN / 169.254 / 0.0.0.0 | literal-hostname-only `permissions.allowedHosts` + per-op `host`; no IP literals, no wildcard, no punycode alias | Resolve DNS and reject private/loopback/link-local/reserved/ULA addresses before connect and after each redirect; IPv6 mapped-IPv4; metadata-service (169.254.169.254) blocked; re-resolve per attempt; no user-supplied DNS pinning |
| T02 | Hostname ambiguity / scheme trick | `http://host`, `host:8443`, `*.host`, `host/path`, `192.168.x`, `MIXED-case`, trailing dot, idn | `allowedHosts` + `host` pattern is a bare lower-case literal (letters, digits, hyphens, dots); max 253; regex anchors both ends; case normalized to lowercase in the docs; port/scheme/path/wildcard rejected | Scheme forced HTTPS; connect uses the literal hostname only; no host from response bodies; redirect host must be re-allowlisted (T03); punycode aliases rejected by canonicalization check |
| T03 | Redirect cross-host leak | HTTP 30x to an unapproved host (or to HTTP) | schema names the allowed literal; schema cannot see redirects | Follow at most N redirects, each revalidated: scheme https, host in allowedHosts (or signed-URL exception), DNS private-IP recheck; drop Authorization/credentials on any cross-host hop; never send vault values in query params |
| T04 | Token exfiltration via logs/headers | secrets echoed by error logs, headers, URLs, screenshots | `credentialSchema` fields are typed + secret-flagged; manifest may not reference raw secrets; `requiresOwnVault` gates auth actions | Vault values are reference-only (`CredentialRef`); host redacts tokens/authorization headers/query params from logs; error strings parameterized; keystore-backed vault excluded from backup/transfer; per-process vault lock + re-auth on key invalidation |
| T05 | Unbounded response body / decompression bomb | multi-hundred-MB or zip-bomb-style response; chunked stream | `maxResponseBytes` uses `maximum: 8388608`, `minimum: 1`, `default: 1048576` (schema rejects larger caps) | Byte-counted cap — counted after decompression — counts gzip/brotli/zip, enforces streaming abort, request time + concurrency caps, retry/backoff caps, response depth/nesting cap (e.g. 32), no range chasing |
| T06 | Cross-plugin credential grab | plugin A names plugin B's credentialId | `requiresOwnVault` + namespaced `credentialSchema`; no global secret reference grammar | Vault keyed `(pluginId, credentialId)`; typed auth actions take only this plugin's refs; no cross-plugin key; vault content never in notification/backup |
| T07 | Unbounded package / archive | gigabyte package, many entries, deep paths, symlink escape (a later install-time concern; AA-021 zip handling too) | schema has no package fields in v0 (package layout defined separately; size/digest validated at install AA-021) | Install-time: package size cap, entry count/depth caps, zip-slip path resolution, symlink reject, extracted-bytes cap, SHA-256 integrity, digest-pinned fixtures |
| T08 | Regex / template abuse | catastrophic backtracking; template substitution that becomes code | regex only in `settingsSchema` `pattern` (maxLength 200, optional), and *no* templating/extraction grammar exists; `pathTemplate` is `/[...]` static with no `..` or query injection | Host runs patterns against a safe engine (JVM `kotlin.text.Regex` in tests; Android `java.util.regex` has a documented fixed-size backtracking limit) with runtime timeout; no template engine, no selectors, no JS/HTML evaluation; `fieldMapId` only names a host-side frozen map |
| T09 | Infinite loops / unbounded composition | recursive pipelines, request chains, deep response recursion | No loop/recursion/conditionals grammar exists; operations are a flat array (max 8); per-op budgets are finite integers | Host enforces per-request count caps, per-run time budget, max chain length = 1 primitive per op, response nesting cap, recursion ceiling in any response-directed processing |
| T10 | Data-shape smuggling / SQL-ish injection | response fields reinterpreted as code, paths, SQL, or URLs | Typed results only (catalog items, edition fields, release candidates, acquisition plans); no `$ref`/`$eval`/URL-from-response fields in v0 | Host strictly deserializes into typed objects; unknown properties dropped+logged; URLs produced only by host primitives validated against allowlist; no raw strings injected into Room/UI |
| T11 | Capability smuggling | declaring `catalog` but writing an ops entry that behaves like resolver | `operations[].capability` constrained to the closed enum; `hostOperation` closed finite enum | Parse-time pairing battery (§3 of capability table): `http.get_json.v0` only for the three discovery capabilities; `resolver.*` only for `acquisition_resolver`; loader re-checks; a manifest may not add capabilities via settings |
| T12 | Permission creep over updates | v0 package auto-updates into broader scope | v0 frozen; schemaVersion enum constant 0; installs carry a permission diff before consent (see §F install flow) | Update applies only after re-consent for expanded scope/domains; last-known-good rollback; audit trail of digests without raw keys |
| T13 | Background refresh abuse | polling battery drain / overnight metered bytes | `allowBackgroundRefresh` is a required explicit boolean; `false` (fixtures) never polls | When true: host caps background rate, concurrent requests, and daily byte/time budget; `false` ⇒ operations start from user interaction only; periodic background fingerprinting still logged redacted |
| T14 | Content-type / parser confusion | HTML or binary served where JSON expected | no schema-level signal (content-type is runtime) | Host validates declared response content-type (e.g. `application/json`); strips BOM; rejects mixed/HTML; depth/nesting caps; safe-bom parse; no eval of embedded scripts/expressions (T08/T10) |
| T15 | Punycode / unicode host abuse | visual-confusable domains or `xn--` aliases to private IPs | pattern allows ASCII letters/digits/hyphen/dot only (no `xn--` start token), length cap | Host canonicalizes to lowercase IDN form and re-validates literal equality against allowedHosts; DNS revalidation makes mismatches fatal; no display-name trust |

## 3. Schema-level vs. HOST-enforced (AA-023 tracking list)

**Schema-level (already encoded in `contracts/plugin-manifest.schema.json` v0):** literal
HTTPS-only hostnames; no wildcard/IP/port/scheme/path in hosts; hostOperation +
capability closed enums; extra properties rejected everywhere (`additionalProperties:
false`); fixed budgets (`maxResponseBytes` ≤ 8 MiB, `timeoutMs` 100–30000, op count ≤ 8,
allowedHosts ≤ 8, no arbitrary positivity in settings/credentials).

**HOST-enforced only (AA-023 battery — NOT yet implemented; do not claim otherwise):**

- T01 DNS/private-IP and metadata-service rejection (+ redirects), T03 redirect
  revalidation and cross-host credential drop, T05 decompressed byte counting and
  streaming abort, T06 cross-plugin vault isolation in the real vault, T07 package
  size/zip-bomb/traversal at install, T08 regex runtime limit, T09 per-run time/count
  budgets, T13 background refresh budgets, T14 content-type verification, T15 punycode
  canonicalization, plus manifest digest/signature verification and rollback.

## 4. Residual risk notes (accepted for v0, tracked)

1. **Schema ≠ sandbox.** Nothing here makes a manifest "safe"; the grammar just limits
   what a plugin can ask for. Enforce every host rule (AA-023) before any package able
   to reach the network is accepted beyond local fixtures.
2. **Pairing invariants** (capability × operation) are enforced by the deterministic
   parse-time battery in the AA-017 test tree and later by the host loader, because
   draft-2020-12 cannot tie two arrays in a portable subschema. The schema itself keeps
   both enums closed as the first line.
3. **Audiobook Bay HTML discovery** is not expressible in v0 (§6 capability table) — the
   attempted abuse class would be "scraping/anti-bot evasion induced by a declarative
   grammar", which T08/T10/T11 and section 6 of the capability table preclude by
   construction; the safe alternative is host-side reviewed parsing in a signed app
   update after the AA-022 gate.
4. **Vault strength** is a separate prototype ticket (AA-020); this review fixes the
   property ("tokens never leave vault except typed refs") and the isolation boundary,
   not the crypto implementation.
