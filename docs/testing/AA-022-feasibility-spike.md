# AA-022 — Source-specific feasibility spike: verified contract + live findings

> Status: **INVESTIGATION-ONLY, PARTIALLY VERIFIED, two blockers documented** (per AGENTS.md rule 12:
> unresolved policy/access issues get a decision record + nearest safe proof; blocked deliverables stay
> marked incomplete). No copyrighted content ingested. Credentials kept local+gitignored.
> Date: 2026-09-23. Repo base: main @ `4a43381` (post PR #16).

## Scope recap (from EXECUTION_PLAN AA-022)

Test a real acquisition path against live provider APIs using **expressly permissioned / legal
samples only**; inspect current TorBox auth + transfer responses; do not ingest copyrighted titles or
embed keys; if the site forbids scraping or authorized media is unavailable, label the adapter blocked
and preserve generic legal fixture proof.

## What was verified against the LIVE TorBox API (all real curl output)

Account: user-supplied TorBox API token (stored at `.creds/env.test` — **gitignored**, redacted in all
logs/screenshots/commits).

| Contract | Endpoint (live) | Result |
|---|---|---|
| Auth | `GET /v1/api/torrents/mylist` with `Authorization: Bearer <token>` | `success:true` — token valid |
| OpenAPI | `GET https://api.torbox.app/openapi.json` | 96 KB spec, 87 paths served |
| Cache probe | `POST /v1/api/torrents/checkcached` body `{"hashes":["SHA1:<h>"]}` | `success:true, data:{}` = **not cached** |
| Add magnet | `POST /v1/api/torrents/createtorrent` (**multipart/form-data**, field `magnet`) | `success:true`, returned `torrent_id` + matching infohash |
| State machine | `GET /v1/api/torrents/mylist?id=<id>` | reports `download_state` (`metaDL`), `progress`, `seeds`, `peers`, `eta`, `download_present` |
| Resolve link | `GET /v1/api/torrents/requestdl?token=&torrent_id=&file_id=` | on a not-ready torrent → `DATABASE_ERROR` 500 (docs "not ready/download offline" family) |
| Delete | `POST /v1/api/torrents/controltorrent` (**JSON** body `{"operation":"delete","torrent_id":N}`) | `success:true` |
| Web-dl | `POST /v1/api/webdl/createwebdownload` (**multipart**, `link`) | `DOWNLOAD_SERVER_ERROR` on a redirecting archive.org CDN link (link unscannable) |

### Authoritative request-shape corrections (from the live OpenAPI spec, supersede guesses in ADR-0004)
- `createtorrent` / `createwebdownload` are **`multipart/form-data`**, NOT JSON. `seed` is an **int** (0/1), not boolean; bools live in `as_queued`, `add_only_if_cached`, `allow_zip`.
- `controltorrent` body is **JSON**: `{"operation": "delete", "torrent_id": N}` (or `{"operation":"delete","all":true}`).
- `checkcached` POST body field is **`hashes`** (array of `"SHA1:<h>"`), not `hash`.

## Legal test sample used

**"An Easter Lily" by Amanda Minnie Douglas** — public-domain audiobook, LibriVox
(identifier `an_easter_lily_1705_librivox`), per ADR-0004's primary legal proof source.
Official archive.org `.torrent` (19,674 B) → bencode-parsed → btih
`e7104e12a36ad94e97b355494aefbcb42cf81a55` (247.7 MB, 49 files, 473 pieces). Magnet added to TorBox,
verified via mylist, then deleted. **No copyrighted content involved.**

## Findings / blockers

1. **Audiobook Bay is DOWN and this host is network-blocked from it.** `audiobookbay.lu` resolves
   (176.97.124.219) but every TCP/HTTPS attempt times out at the network layer — from curl AND from the
   browser (browser verified healthy on example.com). Cannot scope/search/detail the source-site
   torrent metadata from this host. Root cause (geo-block vs Cloudflare vs outage) unresolved.
2. **No reachable legal seeder / no cache for the tested items.** LibriVox torrent stayed `metaDL`,
   0 seeds/peers after ~2 min; the 170 official Ubuntu ISOs (Canonical-distributed) all returned
   `data:{}` (not cached) from `checkcached`. The resolved-download SUCCESS path
   (requestdl → short-lived link → bytes) was therefore **not fully demonstrated**; the *unready* path
   was (DATABASE_ERROR 500 = "not ready/offline" family). This is an environment/seed-availability
   condition, not an API-contract failure — the API contract itself is verified across
   auth/add/probe/state/list/delete.
3. **archive.org CDN unreachable / unscannable from this host** — `dn601709.us.archive.org` content
   CDN timed out, and TorBox's own link-scanner rejected the redirecting archive.org URL. librevox
   direct files would need an alternate path.
4. **Copyright gate (unchanged):** Cradle (Will Wight) is **commercially copyrighted, not public
   domain**. User holds authorized copies and offered them as a permitted private test, but any
   public/Play distribution of an Audiobook Bay source plugin remains **BLOCKED** (ADR-0004 R09 /
   AGENTS.md rule 5). Not shipped; not part of any build.

## Complete path — FULLY PROVEN (Debian 13.7.0, added 2026-09-23)

- **Legal sample:** Debian `debian-13.7.0-amd64-netinst.iso` — official Debian-project
  BitTorrent distribution (`https://cdimage.debian.org/.../bt-cd/`), freely redistributable.
  `.torrent` (60,948 B) → btih `7acf8fb590b2060dd9c3146ef770169d593433b0`.
- Added via `createtorrent` with the `.torrent` **file** (not bare magnet) → `Found Cached Torrent`,
  `torrent_id 103485442`.
- `mylist?id` → state **`cached`, `download_present:true`**, file list populated
  (`debian-13.7.0-amd64-netinst.iso`, 792,723,456 B).
- `requestdl?token=&torrent_id=&file_id=0` → **`success:true`**, resolved short-lived link
  `https://store-079.wnam.tb-cdn.io/dld/<id>?token=…`.
- **Link verified to serve real bytes:** HEAD → `200 application/x-iso9660-image`; range fetch
  (2 KiB) → `206`, bytes identified as a genuine ISO El Torito MBR boot sector
  (`4552 08 00 00 … 90 90`) via `file`/hexdump. Not an error page.
- Test torrent then **deleted** (`controltorrent` → `success:true`); account returned to its exact
  prior state (367 pre-existing torrents, 0 AA-022 entries).

**Lesson:** a bare magnet (no `tr=`trackers) forces DHT-only metadata fetch and stalls on thin-seed
libris/archive.org content, whereas adding the **`.torrent` file** (which embeds the publisher's
trackers) lets TorBox locate/seed the item — Debian resolved to cache immediately. For an active
resolver, prefer .torrent-file ingestion over bare magnet, and treat TorBox's "Found Cached Torrent"
create-time message as provisional until `mylist` confirms `cached:true` + populated `files`.

## What's proven vs not

- **PROVEN (end-to-end):** token auth; OpenAPI discoverability; magnet add; **.torrent-file add**;
  infohash matching; state-machine observation; **cache resolution + file-list enumeration**;
  **`requestdl` → live resolved download link serving real ISO bytes**; `controltorrent` delete;
  `checkcached` (POST `hashes` array) and web-dl (multipart `link`) request shapes; failure-path
  (unready/offline) response family (`DATABASE_ERROR` 500 on a not-ready torrent).
- **Documented env limits:** Audiobook Bay down + this host IP-blocked from it (findings 1/3 —
  unresolved root cause); LibriVox book had no reachable seeder but the Debian path fully proves the
  resolver+transfer mechanics. LibriVox remains the primary legal *audiobook* proof for the final
  adapter, pending a reachable mirror/seed.

## Next steps

- Re-test when Audiobook Bay is back up **from a non-blocked network** (owner device/browser) to
  capture real search/detail/torrent-metadata responses for a permissioned public-domain listing.
- For the success path: use a **cached** legal torrent (correlate `checkcached` hits) or a
  non-redirecting direct file link so `requestdl`/`createwebdownload` returns a live short-lived URL.
- Keep LibriVox as primary legal proof; Cradle/Audiobook Bay remain private-only, publicly BLOCKED.
- Record the corrected request shapes above into the next touch of ADR-0004 before any code implements
  the TorBox adapter.