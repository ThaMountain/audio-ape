# Audio Ape — Design reference asset manifest (AA-002)

Originals are preserved in `assets/mockups/` (checksums verified against `SHA256SUMS.txt`).
The ten PNGs are **AI-generated visual references** with fictional content. They are visual
targets, not runnable screens; written behavior in `../AUDIO_APE_MASTER_SPEC.md` overrides any
mockup text (e.g. no Profile tab, plugin-first source UX). No mockup is treated as production
catalog data.

Dimensions (verified 2026-09-19 by parsing PNG IHDR):

| Asset | W×H | Aspect | Verified SHA-256 (from SHA256SUMS) status |
|---|---|---|---|
| `00_visual_overview.png` | 1448×1086 | landscape poster | OK |
| `01_discover.png` | 941×1672 | portrait 9:16 | OK |
| `02_library.png` | 941×1672 | portrait 9:16 | OK |
| `03_player.png` | 941×1672 | portrait 9:16 | OK |
| `04_book_details.png` | 941×1672 | portrait 9:16 | OK |
| `05_source_results.png` | 941×1672 | portrait 9:16 | OK |
| `06_downloads.png` | 941×1672 | portrait 9:16 | OK |
| `07_chapters.png` | 941×1672 | portrait 9:16 | OK |
| `08_plugins.png` | 941×1672 | portrait 9:16 | OK |
| `09_plugin_permissions.png` | 941×1672 | portrait 9:16 | OK |

**Reference-viewport note (DESIGN_SYSTEM §3):** PNGs are 941×1672 px and are **not** a dp spec.
Screen layouts use Compose measurement (`WindowSizeClass` / `BoxWithConstraints`), never
hardcoded screenshot coordinates. 320–359dp → 2 cover columns; 360–479dp → 3; ≥480dp responsive.

**Brand asset status:** No isolated vector/transparent ape-headphones logo exists (spec §0 / §9).
A clean, high-resolution, legally-cleared brand foreground must be created and **owner-approved**
before any final icon publication. The bundled ape image is visual reference only.

**Palette seeds (starting points, DESIGN_SYSTEM §2 — re-measure from PNGs before freezing):**
bg/base `#060B12`, bg/raised `#101B29`, border/subtle `#273B50`, accent/primary `#168EFF`,
accent/pressed `#086BD5`, text/primary `#F2F6FF`, text/secondary `#A6B8CB`, text/muted `#74869C`,
success `#55D69C`, warning `#FFC46B`, error `#FF7682`.

**Approved use:** mockups used as visual reference only; no fictional book/provider/rating shown
as real content. Any production artwork requires rights/license sign-off (AA-063).