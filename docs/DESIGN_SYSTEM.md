# Audio Ape design system — implementation specification

**Authority:** `../AUDIO_APE_MASTER_SPEC.md` owns behavior. Screens are generative mood/layout references, not accurate datasets. Measure original PNG colors and spacing via a visual QA tool and get human signoff before freezing production tokens; approximate values below are engineering **starting points**.

## 1. Brand identity

A dark, cool-blue, cinematic audiobook experience. Ape with blue headphones is the core brand identifier. Do not replace the ape with a stock emoji, another primate, or unrelated generic audio waveform; source image sits in bundled overview and headers. Original standalone transparent logo/vector has **not** been extracted or validated; create a proper high-resolution isolated brand foreground and request approval before release. App icon should be Android adaptive icon with safe central figure; separately create monochrome icon when feasible and legally cleared. Do not ship promotional taglines with fictional claims as system UI.

## 2. Tokens, independent of device pixels

| Semantic token | Proposed value | Purpose |
|---|---|---|
| `bg/base` | `#060B12` | application/root and skeleton backdrops |
| `bg/raised` | `#101B29` | buttons, cards, raised menus |
| `bg/overlay` | `#162537` | drawers/scrim surfaces |
| `border/subtle` | `#273B50` | 1dp card borders |
| `accent/primary` | `#168EFF` | CTA and active indicators |
| `accent/pressed` | `#086BD5` | pressed/drag state |
| `text/primary` | `#F2F6FF` | titles and active labels |
| `text/secondary` | `#A6B8CB` | author, metadata |
| `text/muted` | `#74869C` | unknown/disabled metadata |
| `status/success` | `#55D69C` | provider success only |
| `status/warning` | `#FFC46B` | retry/warnings |
| `status/error` | `#FF7682` | errors/deletion |

Values are proposals; sample dominant neutral/accent palette from mockups, then check legibility. Accent blue only for interactive state, progressing media and rare emphasis—not a blue halo around every surface. Artwork-color backdrop must pass overlaid text contrast and avoid image analysis on every frame.

Typography: prefer freely distributable, legally confirmed geometric sans (system Roboto fallback). Book titles 26–32sp on detail/player, screen heading 26–32sp, cards 14–16sp medium, metadata 12–14sp; scale via fontScale and available width rather than hardcoded pixels. Use serif only *inside genuine book cover images*, not arbitrary typesetting of a title onto a cover. No unlicensed font redistribution.

Spacing: base 4dp; horizontal gutters 16–20dp narrow / 24dp larger; card gap 10–14dp; section vertical gap 24dp; card radius 12–18dp; pills 22–28dp; thin outline 1dp; cover corner 10–14dp. All clickable controls effective touch target >=48dp and semantics for screen reader baseline. System status/navigation insets always respected.

## 3. Responsive layout

Reference PNGs are 941x1672 px, **not** a dp spec. Use `BoxWithConstraints`, `WindowSizeClass` or current supported adaptive tools, and Compose measurement for real devices; never hardcode screenshot coordinates. 320–359dp: 2 cover columns; 360–479dp: 3; >=480dp: choose based on min cover width ~104–128dp and gutter; tablet feature panes are future-ready. On short landscape prefer vertical scroll plus permanent transport visibility; don't crush art onto controls. Long titles support 2–3 lines in cards and full accessibility label. For RTL and large fonts, no clipped primary buttons.

## 4. Navigation

Production proposal: 5 bottom destinations Discover, Library, Now Playing, Downloads, Plugins; Settings via top right gear/overflow. The generated mockups show inconsistent nav slots and an invalid Profile option. **Do not implement Profile** while accounts are excluded. If five tabs prove crowded at 320dp, benchmark one of: compact labels for inactive tabs; 4 destinations with Downloads surfaced in header; more menu. Document one chosen solution with device screenshots and owner review. Never render a second redundant hamburger drawer merely because older concepts had one.

Back behavior: detail -> Discover; sources -> plugin selection -> detail; Now Playing collapse -> previous screen; Downloads back -> previous route; app minimized while playing does not interrupt audio. On navigation to a book in library, single tap is a user-issued resume command. Control state derives exclusively from service's controller.

## 5. Component inventory

- `ApeBrandHeader`: isolated approved logo, Audio Ape wordmark and optional brief slogan only on onboarding/marketing; row degrades to small mark on long page.
- `BookCover`: ImageLoader with memory/disk thumbnail cache, exact image aspect ratio (typically square cover), gradient placeholder, inset stroke, no permanent GPU blur. Exposes loading/error/completed/progress states.
- `BookCard`: cover, title, author, optional validated info, click/long-click; don't add fabricated ratings. `SeriesStack` shows group count and order.
- `SectionRail`: `LazyRow` with anchored title, See All action that really navigates to a paginated view; skeleton state length <=visible+1.
- `ApeSearchField`: text edit + IME action, debounced local library filter permitted; **remote Discover must submit**; empty query is a no-op/clear state.
- `HeroCard`: verified audio-edition art, concise metadata, open details; do not place 'Play Now' for a title not locally acquired.
- `MiniPlayer`: persistent only if current item exists, cover, title, pause/play, expand gesture, no competing session.
- `TransportCluster`: skip10/pause/skip10, real chapter prev/next elsewhere, tap feedback, stable pause/play glyph size.
- `PrecisionScrubber`: book-wide progress, preview bubble and drag semantics, haptic feedback only at chapter boundaries if available and not excessive.
- `ChapterModeSwitch`: segmented `Chapters | ±30 min` or equivalent quick action, accessible labels; online chapter application requires confirmation.
- `SourceRow`: detailed release fields provided by plugin, best-match tag tied to saved preferences, Download command with explicit result status. One plugin context per screen.
- `DownloadCard`: known bytes/ETA/stage, progress, action controls and expandable technical details. Handle unknown total explicitly, not fake 0%.
- `PluginCard`: actual publisher/verified status, version, permission summary, enable/disable, configure, update history; never fictional official status.
- `PermissionSheet`: display diff on update, required vs optional scopes; allow enabling only after approvals; show exact allowed network hosts and own credential vault wording.
- `ErrorState`, `EmptyState`, `SkeletonTile`, `ConfirmDeleteDialog`, `StoragePermissionRequest`, `RecoveryBanner`, `OfflineBanner` for all flows.

## 6. Motion and haptic rules

Motion must communicate relationship, not decorate: press scale ~0.98 with 100–150ms return, selected tab underline 150–220ms, book-cover-to-player expand 220–320ms **only if frame benchmarks allow**, sheets 220–280ms. Easing choose standard native spring/ease-out with no oscillatory bounce. Respect Android animator-duration/reduced-motion settings. Avoid infinite animated backgrounds, giant dynamic glow, per-tick recomposition of whole screen. Prefer one `GraphicsLayer` art shadow and blurred precomputed low-res background if measured cheap. Haptics on chapter snap/bookmark saved only if user/device permits and has no accessibility downside. No splash hold for branding.

## 7. Screen design details and errors

**Discover:** top logo/notifications only when real notifications exist; search; featured hero from confirmed audio catalog; recommended rails (based on local authors/genres/series only); 'new releases' only with audio-edition date; popular only if actual ranking data from provider; See All navigates. Offline shows cached cover + 'You’re offline; downloads unavailable'; no infinite spinner. Text links/covers have action labels.

**Library:** top title, search, grouping; recent sort `max(lastPlayed, imported)`; grid; darkened completed art with text; optional progress strip; display scan banner unobtrusively; actions in long-press/overflow: edit, add collection, delete with confirm. No nonfunctional checkmarks and placeholder artwork assumed real.

**Player:** keep full primary action visible at screen height >=640dp and use scroll on compact devices; artwork focal area roughly 43–50% of available height before ancillary section; text and seek below; primary skip/pause cluster large. Up Next is next chapter row; at final chapter show next series book only when known and available; no cross-book auto-start without explicit permission. Seek preview uses precise full-book time + chapter title.

**Details:** title, author, narrator (if known), publisher/edition, genres, description; never falsely claim 'available from plugins' until search completed. Get Book opens plugin choice; if already installed show Play/Resume plus Find another edition. Web metadata attribution/links per provider requirements.

**Plugin choice:** screen missing from mockups: build as sheet with per-plugin name, capabilities, authorization state, fetching indicator and result count if known. Choosing only one navigates to that plugin's results. If disabled/not authorized, direct to permissions/settings then return.

**Sources:** adapt mockup: header selected plugin (not All Sources), high-density rows without tiny touch targets; literal unknown for missing narrator/bitrate/size, badge based on verified API response. Download button in row; keep owner override controls for per-plugin preferences.

**Downloads:** pinned active group and queue, fields consistent with actual data; normal permanent OS notification. A clicked cancel requires explicit job cancel confirmation only if user might lose a large partial transfer; never silently cancel merely from swiping card. Retry suggested alternative same-edition source; allow intervention after bound retries.

**Chapters:** when embedded chapters available list them; online match offers review/apply sheet; `±30m` mode label and controls rather than falsely marking 30m segments as chapters. Current row visually prominent; normal chapter transitions continuous. Bookmarks optionally named/note.

**Plugins:** separate installed/available tabs; source-host data packages; third-party install entry tucked under Advanced; display verification trust honestly; update success only after artifact validation and activation.

**Settings:** storage picker/reconnect/rescan; skip amounts; smart rewind; per-book audio overrides; download Wi-Fi/cellular preference; advanced third-party allow; plugin permissions; privacy/report; About/licenses. No Audio Ape login or Profile tab.

## 8. Visual acceptance and screenshot comparisons

For each screenshot: (1) render one reproducible UI fixture at the same Android viewport *dp* / density normalization; (2) compare composition and proportional zones side by side to target PNG; (3) assess background, artwork position/size, typography hierarchy, spacing, glow moderation, active tab and inset handling; (4) manually verify every button works, long text and font scales, offline/empty/errors; (5) use image-diff only as an aid because mockups contain noncanonical text and fictional book data. Record every approved intentional deviation. Include screenshot evidence for player, Discover, library, book details, plugin choice, sources, downloads, chapter list, plugin store, permission sheet and Settings. Never claim visual equivalence from a screenshot without launching the real app.

### Discrepancy register to resolve (mandatory)

| Mockup detail | Correct implementation |
|---|---|
| Bottom tab 'Profile' | Settings under overflow; no Audio Ape account; explicit Downloads navigation |
| Multi-plugin release list | plugin selection FIRST; then single-plugin results |
| Fictional ratings/reviews/availability | only actual provider-backed values; omit otherwise |
| 'Play Now' for undownloaded featured book | 'View details' or 'Get Book' depending verified state |
| Connected Google Drive in sample | show only if user actually connects permitted future plugin |
| Book names/chapters mismatch across screens | test fixtures share one canonical Edition/Chapter JSON |
| App logo depicted embedded in full poster | separately cleared foreground needed before production adaptive launcher icon |
| Completion check in art | completion stored privately; reimport after uninstall resets |
| Up Next depicted as another book | next chapter until end, then optional next series offer |
| Official/verified plugin badge | only with real verification records and checked key chain |
