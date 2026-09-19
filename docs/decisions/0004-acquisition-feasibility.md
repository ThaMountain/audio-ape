# AA-006 / AA-022 — Acquisition Feasibility Checkpoint: Audiobook Bay + Debrid Resolvers (Real-Debrid / TorBox)

**Status:** INVESTIGATION-ONLY (owner decision gate for any public release)
**Date:** 2026-09-19
**Scope:** Feasibility + legality checkpoint for (1) an Audiobook Bay source plugin, and (2) Real-Debrid and TorBox `acquisition_resolver` adapters. This is an investigation gate only — a debrid subscription does **not** grant permission to download copyrighted audiobooks, and nothing here authorizes the acquisition or redistribution of copyrighted content.

> **Verification legend:** Claims below are flagged **[VERIFIED]** where confirmed against a live primary source during this pass, **[UNVERIFIED]** where the current terms/behavior could not be confirmed, and **[ASSESSMENT]** for analysis clearly distinguished from fact. Every claim is sourced; do not treat reliance notes as authorization.

---

## 1. Audiobook Bay — access model, terms, API status, sample feasibility

**Primary sources:** `https://audiobookbay.lu/robots.txt` **[VERIFIED live 2026-09-19]**, `https://audiobookbay.lu/` [VERIFIED live], no developer-API page located this pass.

- **Access model is site-only.** Audiobook Bay is a community index/tracker hybrid ("Unabridged Audiobooks Free Download … Download unabridged audiobook for free or share your audio books …" with per-title **Direct Download** and forum **request** flows) **[VERIFIED from home page]**. No stable developer API is documented on the site or referenced anywhere we could confirm **[UNVERIFIED — no documented developer API found; treat "none exists" as current working assumption]**. There is no developer landing, no API token/app model, and no OAuth, so the only integration surface is the HTML site itself.
- **robots.txt is permissive for browsing, but blocks the download and archive endpoints.** The live file `Disallow: /download.php?f=<…>` (the direct-download handler) and `Disallow:`-lists the `member/`, `forum/*` sub-trees, while leaving an `Allow: /` baseline. It also explicitly `Disallow: /` for an extensive list of scraper/offline-copier agents (HTTrack, WebCopier, WebReaper, WebSnake, BlackWidow, etc.) **[VERIFIED live]**.
  - Implication **[ASSESSMENT]**: an adapter that reads public book-index pages may be tolerated by robots, but any scrape of `/download.php?f=` (or evasion of the member/session controls it sits behind) is explicitly disallowed and would breach the site's stated crawl preferences. We will treat the download endpoint as off-limits.
- **No explicit permission/duplication terms were found.** We located no Audio Book Bay Terms of Service, redistribution license, or API contract granting any duplication or redistribution right **[UNVERIFIED — no such terms found; absence is not confirmation they do not exist, but the site presents no documented permission path]**.
- **The catalog is dominated by still-in-copyright works.** Home-page sample listings (Agatha Christie, Blake Pierce, S.L. Huang, Leia King…) are distributed for free without indicated authorization **[VERIFIED from home-page listings — these are commercial titles]**.
- **Authorized sample feasibility: NOT feasible as currently constituted.** Because the site offers no permission/API path and the predominant content is commercially copyrighted, there is **no honest "authorized" Audiobook Bay test sample** available through the site's own mechanisms. Requesting "permission" from the site operator would not fix the underlying fact that the *content* is unauthorized; a site "permission" is not a rights-holder license.

### Recommendation (proof of the plugin interface while unresolved)
Prove the `source_search` / `acquisition_resolver` / `convert-to-AudioEditionCandidate → SourceRelease → ResolvedDownload` interface against a **legally permissioned or public-domain corpus**, and keep any Audiobook Bay adapter out of public distribution:

- **Primary proof source — LibriVox** (audio-first, permissively free; its official audiobook API is already on the research list, R18): a real audiobook record, edition/narrator/title/size identity, and a provider-oriented torrent/magnet path where a public-domain work is genuinely freely redistributable.
- Also acceptable: **Project Gutenberg / Librivox audiobooks, archive.org Internet Archive public-marked audiobooks, or a rights-holder-supplied fixture** — any corpus where download is actually lawful.
- **Interface, not content:** the feature to be proved is the *plugin shape* (declarative source plugin manifest → typed `AudioEditionCandidate`/`SourceRelease`/`AcquisitionRequest`/`ResolvedDownload`, capability scoping, host-enforced allowlists, transfer/verify/import pipeline). Prove it on lawful data; swap only the host origin when/if a permissible source ever exists.
- **Hard lines:** never auto-scrape `/download.php?f=`, never bypass login/anti-bot/captcha, never exercise the member-area or forum download surface, and never route a working Audiobook Bay → debrid flow in a released build. Any Audiobook Bay adapter stays behind an advanced opt-in or, practically, excluded from the Play distribution entirely.

---

## 2. Real-Debrid — OAuth/device flow, resolver endpoints, restrictions

**Primary source (live this pass):** `https://api.real-debrid.com/` (official API documentation). All items below **[VERIFIED]** from that page.

- **Base URL / transport:** `https://api.real-debrid.com/rest/1.0/`. Methods grouped by namespace; verbs GET/POST/PUT/DELETE (override via `X-HTTP-Verb`). Successful calls → HTTP 200 JSON; errors → 4xx/5xx with `error` (string) and optional `error_code` (int). ETag/`If-None-Match` supported.
- **Auth:** authenticated calls send `Authorization: Bearer <token>` or the `?auth_token=` parameter. The token is **either your private API token or a token obtained via OAuth2 three-legged auth**. The docs state explicitly: **"Never ever use your private API token for public applications, it is insecure and gives access to all methods."** An app must be created in the user's control panel to obtain a `client_id` / `client_secret`. The docs publish a shared open-source `client_id` usable for OSS apps (scopes `unrestrict`, `torrents`, `downloads`, `user`) but warn it can carry stricter limits than service limits.
- **OAuth2 device flow (`https://api.real-debrid.com/oauth/v2/`):**
  1. `GET /device/code?client_id=<APP>` → `{ device_code, user_code, interval, expires_in }`.
  2. User is shown `user_code`, enters it on the OAuth site and logs in/authorizes.
  3. `POST /token` with `client_id`, `client_secret`, `code=<device_code>`, `grant_type="http://oauth.net/grant_type/device/1.0"` → `{ access_token, expires_in, token_type:"Bearer", refresh_token }`. This is the correct flow for a phone app (an `authorization_code` web flow is also documented using `/auth` then `/token`).
- **Endpoints needed for `acquisition_resolver` [VERIFIED]:**
  - `POST /torrents/addMagnet` — body `magnet` (*), `host` (from `/torrents/availableHosts`); returns 201.
  - `GET /torrents/info/{id}` — file list + IDs.
  - `POST /torrents/selectFiles/{id}` — body `files="all"` or comma-separated IDs; starts the transfer; 204 (`202` = already done). Select only the audio file IDs from `/torrents/info/{id}` for instant download.
  - `POST /unrestrict/link` — hoster link → new unrestricted link (with password opt; 0/1 `remote`). Also `GET /unrestrict/check` (link health, no auth required).
  - Supporting: `GET /torrents/instantAvailability/{hash}` (cache check), `GET /torrents/availableHosts`, `DELETE /torrents/delete/{id}`, `GET /downloads`, `GET /torrents`, `GET /torrents/activeCount`.
- **Documented restrictions [VERIFIED]:** API limited to **250 requests/minute**; everything over the limit returns HTTP **429 and still counts against the limit**; brute-forcing leaves the key blocked for an undefined period. 401 = bad/expired/invalid token, 403 = permission denied / account locked / not-premium. Credentials must be handled per the repo vault rules (a token in a query string is explicitly discouraged in the docs; headers are the documented transport).

**Real-Debrid verdict:** the provider API is **documented, stable and suitable** for an `authorization` + `acquisition_resolver` transfer adapter (device OAuth + torrent select + unrestrict). It must move through the same INVESTIGATION-ONLY gate as every resolver (see §5) because it is a transfer utility, not a content license.

---

## 3. TorBox — auth model, endpoints, usage restrictions

**Primary sources (live this pass):** official API docs via Postman network, servred at `https://api-docs.torbox.app/` (Postman "Main API" collection) **[VERIFIED]**; developer terms page `https://torbox.app/policies/api-developer-terms` returned an empty/JS shell and could not be read this pass **[UNVERIFIED]**.

- **Base / transport [VERIFIED]:** `https://api.torbox.app`, API version `v1`. All outputs JSON. Standard envelope `{ success: bool, error: string|null, detail: string, data: any }` — "always use the `success` key". Status codes: `200` success, `403` auth error, `400` client/input error, `500` server error. Dates are UTC `%Y-%m-%dT%H:%M:%SZ`.
- **Auth model [VERIFIED that it is token-based / OAuth-capable, PARTIALLY UNVERIFIED on flow specifics]:** the error table includes `NO_AUTH` (no credentials), `BAD_TOKEN` (invalid token), `AUTH_ERROR`, and `OAUTH_VERIFICATION_ERROR` ("server tried verifying your OAuth token…"). The generic phrasing and "per API token" rate limits confirm a **token/session model with OAuth support**. **Exact OAuth endpoints/scopes and the API-token creation flow could not be read this pass from the Postman shell — [UNVERIFIED] — they must be confirmed from the developer/account area at implementation time.**
- **Endpoints for `acquisition_resolver` [VERIFIED, via the docs' own Real-Debrid translation table and general info]:**
  - `POST /torrents/createtorrent` (≈ RD `addTorrent`/`addMagnet`) — add magnet/torrent.
  - `GET /torrents/mylist` (list) and `GET /torrents/mylist?id={id}` (≈ RD `torrents` / `torrents/info/{id}`).
  - `GET /torrents/checkcached` (≈ RD `instantAvailability`) and `GET /torrents/requestdl` (≈ RD `unrestrict/link` — resolved download).
  - `POST /torrents/controltorrent` (≈ RD `delete`).
  - **Notably, no `selectFiles` equivalent:** the docs state "**None, not needed. Torrents will download all files. This will not be changed.**" **[VERIFIED]** — an adapter must therefore post-filter requested audio from a multi-file download rather than pre-selecting, which changes the file-selection design (see §5).
- **Documented restrictions [VERIFIED]:** default rate limit **300 requests/min per API token** (no edge limiting); `POST /torrents/createtorrent` limited to **60/hour** for *uncached* items, 300/min if cached; `createusenetdownload`/`createwebdownload` 60/hour. No IP-whitelist requests accepted (limiting is token-based). **Download size caps per plan** are explicit (Free ~10737418240 B, Essential/Standard ~214748364800 B, Pro ~536870912000 B). Other enumerated errors relevant to audio transfer: `LINK_OFFLINE`, `MONTHLY_LIMIT`, `ACTIVE_LIMIT`, `COOLDOWN_LIMIT`, `DOWNLOAD_TOO_LARGE`.
- **Redistribution/usage terms: [UNVERIFIED]** — the "API Developer Terms" page is the intended authoritative source (per repo R23) but was not retrievable this pass. These must be read and logged before any TorBox adapter is approved.

**TorBox verdict:** a real, documented API exists and is transfer-capable, but auth-flow specifics and the redistribution terms remain unverified. Same INVESTIGATION-ONLY gate applies.

---

## 4. Google Play policy — R09 (IP), R10 (device & network abuse)

**Primary sources (live this pass):** `https://support.google.com/googleplay/android-developer/answer/9888072` (Intellectual Property) and `…/answer/16559646` (Device and Network Abuse).

- **R09 — Intellectual Property [VERIFIED verbatim]:** "We don't allow apps or developer accounts that infringe on the intellectual property rights of others (including trademark, copyright, patent, trade secret, and other proprietary rights). **We also don't allow apps that encourage or induce infringement of intellectual property rights.**" And under "Unauthorized Use of Copyrighted Content": "**We don't allow apps that infringe copyright. Modifying copyrighted content may still lead to a violation. Developers may be required to provide evidence of their rights to use copyrighted content.**"
- **R10 — Device and Network Abuse [VERIFIED verbatim, key passage]:** "An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, **an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).** Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) **must not allow potential violations of Google Play policies.**" The violation list also includes "**Apps that access or use a service or API in a manner that violates its terms of service.**" and "Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play."
- **Distribution-risk assessment for a downloadable source plugin [ASSESSMENT]:**
  - **Primary risk is R09, not R10.** A *downloadable source plugin whose functional purpose is to discover and acquire audiobooks from a site whose catalog is predominantly still-in-copyright titles distributed "for free" is, on its face, an app that **encourages/induces infringement** — regardless of whether the plugin itself emits only data. R09's "developer may be required to provide evidence of their rights" and the absence of any such rights for Audiobook Bay catalog content make this a **material distribution risk**. R09 risk is NOT removed by the deletable/append-only plugin architecture.
  - **R10 is the secondary risk and is largely mitigated but not eliminated by a data-only plugin.** Because the V1 plugin is a ZIP of JSON data files interpreted by a host with a bounded, allowlisted, deny-by-default grammar — no user-supplied executable/dex/JAR/.so, no runtime-loaded Python/Lua/JS — it does not fall into R10's downloadable-executable-code or runtime-interpreted-code-loading prohibitions. R10 does still bite in two places though: (a) if the plugin grammar ever becomes expressive enough to be an executable-code backdoor it is swept in; and (b) R10's "access or use a service or API in a manner that violates its terms of service" directly applies to an Audiobook Bay adapter that touches the robots-disallowed `/download.php?f=` path and to any debrid integration that conflicts with the provider's ToS.
  - **Net:** a downloadable Audiobook Bay source plugin carries a **real R09-induced distribution risk** that is independent of the (lower) R10 code-loading risk. A debrid resolver alone does not trigger R09 if it is kept transfer-neutral and never curates/propagates infringing magnets; the risk concentrates where the resolver is wired to an Audiobook Bay magnet.

---

## 5. ADR recommendation

### Adapters

| Adapter | Recommendation (this gate) | What must change to move to SHIP-APPROVED |
|---|---|---|
| **Audiobook Bay source plugin** | **BLOCKED for public distribution.** Investigation-only at best. | A lawful, rights-cleared source must exist — e.g. restrict adapter to works confirmed public-domain/CC-licensed, no `/download.php?f=`, no member-area/forum scraping, no automation that evades the site's stated crawl preferences, and legal sign-off that discovery does not induce infringement. Practically: prove the interface on LibriVox/public-domain audio and keep any Audiobook Bay origin out of the Play build. |
| **Real-Debrid resolver** | **INVESTIGATION-ONLY.** Documented and structurally approvable. | Register app in control panel (own `client_id`/`client_secret`, not the shared OSS id for a shipping app), run an **authorized** device-OAuth end-to-end on a permissioned sample, pin schema/version, confirm 250/min limits and refresh-token rotation, and obtain legal sign-off that the resolver is transfer-neutral (user-supplied magnet/link; app does not host or curate infringing content). |
| **TorBox resolver** | **INVESTIGATION-ONLY.** | Confirm OAuth/token flow and **read the API Developer Terms** (`torbox.app/policies/api-developer-terms` — unreadable this pass **[UNVERIFIED]**), authorized end-to-end test on a permissioned sample, adapt design to TorBox's auto-download-all files (no `selectFiles`) + plan size caps, legal transfer-neutrality sign-off. |

### Why Audiobook Bay is blocked while the debrid adapters are merely gated
The **copyright risk is concentrated at discovery**, not at transfer. Real-Debrid/TorBox are documented transfer utilities; the adapter is shaped like an authorized browser of the user's own provider account. Audiobook Bay is a site whose catalog is predominantly unauthorized copyrighted material with no rights-cleared sample path; a plugin that funnels it through a resolver is what triggers R09 inducement and the R10 terms-of-service coupling.

### Separation of concerns (unchanged, affirmed)
- **Audiobook Bay = discovery + source release metadata only** (`AudioEditionCandidate`, `SourceRelease`). It must never hold the transfer capability.
- **Debrid (Real-Debrid / TorBox) = provider authorization + torrent job + selected files + expiring resolved URLs** (typed `authorization` + `acquisition_resolver` capabilities). It must never discover or curate content; it acts only on a release the user/completed source step supplies.
- **Audio Ape core = transfer, verify (SHA if available), unpack, import** (transfer/verify/import pipeline). It must never embed provider-specific scraping or content decisions.
- Do **not** collapse these into a privileged "AudiobookBayDebrid" plugin; compose only after the individual interfaces are separately proved (consistent with `docs/ARCHITECTURE_AND_CONTRACTS.md` and `docs/RESEARCH_AND_RISKS.md`).

### Release rule
No Audiobook Bay–related source plugin ships until a rights-cleared origin and legal sign-off exist (adopts decision-log gate Q03 / "exclude blocked plugin from public release"). Real-Debrid/TorBox adapters may ship only after their INVESTIGATION-ONLY gates and legal transfer-neutrality sign-off are satisfied. Debrid subscription is **not** a content license.

---

## Primary sources consulted (live 2026-09-19)
- Audiobook Bay robots — `https://audiobookbay.lu/robots.txt`
- Audiobook Bay home/catalog — `https://audiobookbay.lu/`
- Real-Debrid API docs — `https://api.real-debrid.com/`
- TorBox API docs (Postman/Main API collection) — `https://api-docs.torbox.app/`
- TorBox developer terms — `https://torbox.app/policies/api-developer-terms` (**[UNVERIFIED]** — unreachable/JS shell this pass; repo R23)
- Google Play IP policy (R09) — `https://support.google.com/googleplay/android-developer/answer/9888072`
- Google Play Device and Network Abuse (R10) — `https://support.google.com/googleplay/android-developer/answer/16559646`
- Repo backing docs (read-only): `docs/RESEARCH_AND_RISKS.md`, `docs/ARCHITECTURE_AND_CONTRACTS.md` (R18–R24, Q01–Q16)