# AA-005 — LibriVox as the confirmed-audiobook-ONLY primary catalog: DECISION

**Verdict: GO** (with two honest-labeling conditions — not blockers).

LibriVox can serve as Audio Ape's confirmed-audiobook-ONLY primary catalog. It is a
"proven audiobook-only API" (AA-048 wording): every catalog record corresponds to a completed,
volunteer-recorded, published audiobook; the API returns per-track audio file URLs on every record;
and the hosting party explicitly authorizes third-party apps to share/re-share its public-domain
recordings. All facts below were verified live from primary sources on 2026-09-19.

---

## 1. Official API base URL and endpoints (from the official developer docs, not a blog)

Primary documentation page (the official developer page, fetched directly):
- **https://librivox.org/api/info** — page title "API Info"; states *"The API is currently in: released"* and lists endpoints, parameters, and response fields below.

Official endpoint base: **`https://librivox.org/api/feed/`**

| Endpoint | Purpose | Key parameters (documented on /api/info) |
|---|---|---|
| **`/audiobooks`** | Catalog of published audiobooks | `id` (single record), `since` (UNIX timestamp → all projects cataloged after it), `author`, `title`, `genre`, `extended=1` (full record), `coverart=1` (cover links), `fields={...}` / `fields[]=…` (field subset), `limit` (default **50**), `offset` (default **0**), `format` (default `xml`; `json`, `jsonp`, `serialized`, `php array` also documented) |
| **`/audiotracks`** | Per-track audio | `id` (track id), `project_id` (all tracks for a project) |
| **`/authors`** | Author records | `id`, `last_name` |

Example verified live:
`https://librivox.org/api/feed/audiobooks/?id=47&format=json&extended=1&coverart=1` (HTTP 200; full extended record).
`https://librivox.org/api/feed/audiotracks/?project_id=64&format=json` (HTTP 200; returns `sections[]` with `listen_url` MP3 files).

Anchor search is supported on title/author/genre via `^` prefix, e.g.
`https://librivox.org/api/feed/audiobooks/title/^all` (documented on /api/info).

## 2. Request / authentication model

- **No authentication.** The official docs define **no API key, no OAuth, no token, no header** — the API is an open, unauthenticated public feed. Verified: all requests above succeeded with **no auth headers/params** and a neutral `User-Agent`.
- Responses are plain HTTP GETs to the public `librivox.org` host. `format=json` returns JSON; default is XML.
- The docs explicitly describe this as an open API *"for developers to build apps & other services on top of our audiobooks"* (see https://librivox.org/2011/04/28/librivox-api-opds/ and https://librivox.org/api/info).

## 3. Rate limits and quotas

- **No rate limit or quota is documented** in the official developer docs (`/api/info`). This is **UNVERIFIED** as a hard number.
- Documented structural limits (restraint mechanisms): default `limit=50`, `offset`-based pagination, `fields` selection to shrink payloads, `since` for incremental sync. These are the intended tools for keeping request volume low.
- **Recommendation (AA-048 "host quotas"):** the app must (a) cache records with a TTL, (b) page with `limit`/`offset` instead of dump requests, (c) use `fields`/`extended` judiciously, and (d) keep a modest request pace. No assumption of a high per-hour quota should be built. Document this as an operator-set cap since none is published.

## 4. Response fields available (verified in live responses)

Plain record (`extended=0`, default): `id`, `title`, `description`, `url_text_source` (Project Gutenberg text the reading is based on), `language`, `copyright_year`, `num_sections`, `url_rss`, `url_zip_file` (Archive.org MP3 zip), `url_project`, `url_librivox` (project page), `url_other`, `totaltime` (hh:mm:ss), `totaltimesecs` (**duration in seconds**), `authors[]` (`id`, `first_name`, `last_name`, `dob`, `dod`).

With `extended=1` (+ `coverart=1`) additionally: `sections[]` (each: `id`, `section_number`, `title`, `listen_url` — **actual audio file URL**, `language`, `playtime` — seconds, `readers[]` with `display_name` = **narrator**), `genres[]`, `translators[]`, `coverart_jpg`, `coverart_pdf`, `coverart_thumbnail`, `url_iarchive`.

Field mapping to the Audio Ape fixture shape:
- **title** → `title` ✓
- **id** → `id` (workId/editionId) ✓
- **author** → `authors[].first_name + last_name` ✓
- **narrator** → `sections[].readers[].display_name` (book may have multiple readers — real, collaborative readings) ✓
- **language** → `language` ✓
- **duration** → `totaltimesecs` (seconds) / `sections[].playtime` (seconds) ✓ (real numeric durations)
- **cover** → `coverart_jpg` / `coverart_thumbnail` ✓ (hosted on archive.org CD-cover art items)
- **description** → `description` (HTML) ✓

## 5. AUDIO evidence (the decisive requirement)

**YES — the API returns actual audio file URLs on every catalog record.**
- Per-track MP3 URLs: `sections[].listen_url` (e.g. `https://www.archive.org/download/heart_of_darkness/heart_of_darkness_1a_conrad_64kb.mp3`), exposed both in `extended=1` audiobook records and via `/audiotracks?project_id=…`.
- Whole-book MP3 zip: `url_zip_file` (an Archive.org `64KBPS MP3` zip).
- LibriVox is audio-native: every catalog row is a completed, published recording; a "record with no audio" does not exist in its catalog schema. This satisfies "confirmed-audiobook edition requires non-empty confirmedAudioEvidence, verified from a primary source."

## 6. Terms / usage rights (primary sources)

- Homepage (https://librivox.org/): *"LibriVox | free public domain audiobooks … Read by volunteers from around the world"* and *"the LibriVox catalog has grown to include more than **21,000 completed projects**. Our only official outlet is the LibriVox.org web site, but because **all LibriVox recordings are in the Public Domain**, third parties – **app developers**, archivists, streaming channels and curators – are **more than welcome to share and re-share**"*. → Explicit license for app-developer reuse of the audio.
- Source texts are public-domain works (Project Gutenberg link in `url_text_source`, e.g. `https://www.gutenberg.org/etext/1184`).
- **Attribution expectations (good practice, aligned with AA-048/AA-070):** the app must attribute each book to LibriVox (project page `url_librivox`) and retain the archive.org source links; audio and cover files themselves are served from archive.org. Cover-art provenance is the `LibrivoxCdCoverArtNN` archive.org items (cover artists) — keep the cover URL as-is for attribution.

## 7. New releases / popularity data

- **Popularity/rating: NONE.** There is **no rating, download-count, or popularity field** in the documented field list on `/api/info` and none appears in live responses. **Conclusion: a "Popular"/"New Releases"/"Trending" rail (Amazon-style, implied by consumer catalogs) has NO backing data — do NOT fabricate one.** Audio Ape must omit the popularity rail (or show it only as an explicitly-unsupported state), matching AA-027 ("fake ratings intentionally absent") and AA-048 ("New Releases/Popular rails only if source provides verified data; otherwise truthful substitute/omit").
- **New/recently-added: partially available — must be honestly labeled.** There is no dedicated "new releases" endpoint, but the documented **`since=<UNIX timestamp>`** parameter returns *"all projects cataloged since that time"*. Verified live: `…/audiobooks/?since=1755504000&limit=5&fields=id,title,totaltimesecs,url_librivox&format=json` returned recently-cataloged projects (e.g. id 7949 "Handbook of Nature-Study, Part 2"). **Conclusion:** Audio Ape's "new" rail must be labeled **"Recently added at LibriVox"** (not "New Releases"), populated from `since=<now-Nd>` with a bounded paginated count — exactly the `'Recently added at [provider]'` rule in this ticket.

---

## Recommendation

**GO.** LibriVox is a legitimate confirmed-audiobook-ONLY primary catalog for Audio Ape:
- Every record is a real, published audiobook with per-track and whole-book audio file URLs (non-empty confirmedAudioEvidence by construction).
- Open, unauthenticated API with documented endpoints/params and honest pagination controls; no auth burden.
- Recordings are Public Domain and third-party app developers are explicitly welcomed to share/re-shape them.
- Real numeric durations, narrators, covers, and languages verified live. 12 fixtures produced (see `catalog-fixtures.json`).

**Required (non-blocking) conditions:**
1. Label the "new" rail **"Recently added at LibriVox"** (data: `since=<ts>`); do **not** implement a "New Releases"/"Popular"/"Trending" rail — no ratings/popularity data exists.
2. Build modest host-quota handling (pagination via `limit`/`offset`, `fields`, cache TTL) since **no rate limit is published (UNVERIFIED)**.
3. Attribute every record to LibriVox + archive.org (project page, cover, audio origin).

Not used as proof: Open Library / Google Books work records — LibriVox is evaluated wholly on its own audio-bearing records.