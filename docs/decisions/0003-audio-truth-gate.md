# AA-005 — AUDIO TRUTH REQUIREMENTS (confirmed-audio gate)

Rule that governs which catalog records Audio Ape will accept, show, and play. It prevents the
app from treating a *book* (print) record as an *audiobook* (the exact failure AA-005 and AA-048
call out for Open Library / Google Books, which are metadata-only work/edition sources, not audio).

Primary backing: LibriVox official API docs (https://librivox.org/api/info) and the requirement
"a confirmed audiobook edition REQUIRES non-empty confirmedAudioEvidence (e.g. a track/download
or compose API returning audio, or a curated audio-only listing), verified from a primary source."

---

## 1. Definition of a "confirmed-audio" record

A catalog record is **accepted only if ALL of the following are true**:

- [ ] `confirmedAudioEvidence` is **non-empty** and contains at least one **audio file/track URL** returned by the provider's real audio-bearing API (for LibriVox: `sections[].listen_url` — e.g. `https://www.archive.org/download/heart_of_darkness/heart_of_darkness_1a_conrad_64kb.mp3` — or the whole-book `url_zip_file` archive.org MP3 zip).
- [ ] The audio URL points at an actual playable file/source (mp3/m4b/zip/stream), not a print-edition page.
- [ ] The record was fetched directly from a **primary source** (the provider's own API/web page); the `provenance` field records the exact URL fetched.
- [ ] **Bonus/strong** (not required, but recorded): per-track `playtime`/`totaltimesecs` durations and `readers[].display_name` narrators present.

## 2. What is INSUFFICIENT (REJECT)

Reject as not-audio evidence, and **do not surface as playable**:

- [ ] Open Library work/edition records, Google Books volume records, or any print-book ISBN/EAN/OLID key that carries **no audio URL** — even if the page says "audiobook." AA-005: "Do not treat Open Library or Google Books work/edition records as proof of an audiobook."
- [ ] A cover image, description, or ISBN alone.
- [ ] A "narrator"-labeled string with no backing audio file.
- [ ] Any un-verifiable page (fetch error, WAF/403/429, or content that did not load) — treat as **UNKNOWN**, not confirmed.
- [ ] Any URL a provider returns that 404s or is a redirect to a non-media page (checked via the provider contract, not fetched per-keystroke).

## 3. Recommended record shapes for rejection

Record state after a provider call:
- `CONFIRMED_AUDIO` — non-empty confirmedAudioEvidence (above). → **SHOW in rails, search, details; allow play.**
- `NO_AUDIO_EVIDENCE` — present in provider but no audio URL returned. → **NOT a confirmed audiobook. Hide from Rails/play; may show only as a plainly-labeled metadata-only result (never playable), or omit.**
- `UNKNOWN` / `FETCH_FAILED` — could not verify. → **Exclude entirely from rails and search; do not fabricate.**
- `NOT_AUDIO_SOURCE` (e.g., an Open Library/Google Books print record offered as a substitute). → **REJECT — never used as proof of audio.**

## 4. The FAIL GATE / "show limited Discover" rule

When confirmed-audio coverage is low or the provider is unreachable, **Do NOT show fabricated or
metaphor-book results.** Instead:

1. **Fail gate:** If a rail's candidates all fail the confirmed-audio check, or the provider fails
   (timeout / 4xx / 5xx / quota), the rail renders its **empty state** and is dropped from Discover
   rather than being filled with unverified rows. No rail is ever populated with records lacking
   confirmedAudioEvidence (matches AA-027 "fake ratings intentionally absent", AA-048 "reject
   generic printed-book results").
2. **Limited Discover:** If at least one confirmed-audio record exists but confidence is low (e.g.,
   only some rails verified), Discover shows **only** the confirmed-audio subset, with the failing
   rails hidden/empty and a truthfully-worded state (e.g. "Couldn't load X"). Never backfill with
   non-audio records.
3. **Every surfaced record carries its `provenance` (source URL); unknown/unverified fields are
   explicitly labeled `UNVERIFIED`, never guessed.**
4. This is a **fail-closed** invariant: `play` is only reachable from `CONFIRMED_AUDIO` records.

## 5. LibriVox-specific gate note

LibriVox inherently satisfies this gate: every catalog row is a completed public-domain recording
and already returns `sections[].listen_url` / `url_zip_file`, so every accepted LibriVox record is
`CONFIRMED_AUDIO` by construction. The rail-listing rule still applies for the "Recently added at
LibriVox" rail label (no New Releases/Popular data exists — see DECISION.md §7).