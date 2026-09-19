# AA-004 · Audio Ape — Release Signing & Distribution Plan

**Owner:** Nick · **Status:** PROPOSAL for owner review · **Gate:** feeds G9 / AA-069, prepares AA-071
**Source-of-truth refs:** `AUDIO_APE_MASTER_SPEC.md` §6 (security/legal/distribution gate, G9),
`docs/decisions/0001-identity-and-signing.md` (identity + signing lineage),
`docs/EXECUTION_PLAN.md` AA-004 / AA-069 / AA-071,
`AGENTS.md` **rule 11** (“Never hardcode live credentials, API tokens, private paths or personal media in code, logs, screenshots, fixtures or CI”).

Design only. **This document authorizes nothing** — no key generation, no build, no publishing, no secret handling may begin until the owner completes the DECISIONS checklist (section 5) and approves this plan.

---

## 1. Recommended key architecture

### 1.1 Two distinct concepts that must not be conflated

For this project there are **two different “signing” realities** with different guarantees:

1. **Self-distribution signing (GitHub release APK).** APKs delivered as GitHub release assets are installed
   by the user via sideload ("unknown sources"/file-manager install). Android does **not** verify a third-party
   identity chain for sideloaded APKs the way it does for Play-served builds — the artifact signature is thin.
   The real integrity guarantees for a GitHub-first release are:
   - a **pinned SHA-256** publicized at the download point,
   - **HTTPS + GitHub's own release signing/release asset integrity** as the transport trust anchor,
   - the **package signing identity** (`com.audioape.player`) being **constant**, which is what Android and the
     app itself use to reason about in-place upgrades and data ownership.
2. **Google Play App Signing.** When Audio Ape later goes to Play (AA-071), Play uses a different model:
   you generate/upload a developer **Play App Signing key** (Google historically expects an RSA-2048/1024 app
   signing key) into the Play Console, and **Google resigns the APK** with its own platform keys for
   Play-served distribution. Play thus has its **own key lineage** independent of anything we self-sign.

### 1.2 Recommendation: ONE release keystore now + a SEPARATE Play App Signing key later

| Decision | Recommendation | Why |
|---|---|---|
| Use a single **release keystore** for all GitHub-first release APKs | **YES — reuse one keystore** (a dedicated release keystore, **not** the Android Studio default debug keystore) for all self-distributed APKs | Keeps the signing identity and in-place upgrade lineage **uninterrupted**; matches ADR-0001's intent that package id is authoritative for lineage; avoids the cost/complexity of per-release keystores with negligible security gain at this stage |
| Reuse that same GitHub key material for **Play App Signing** | **NO — use a separate Play App Signing key at AA-071** | See §1.3 |
| Signing identity | `com.audioape.player` as the `signingIdentity`, constant across all releases | Changing it mid-life breaks in-place upgrade lineage (ADR-0001 consequence) |

**Clear recommendation:** Store all GitHub-first distribution signing material in **one protected release
keystore**, sign every release APK with it, and keep the package `signingIdentity` constant. Treat **Play App
Signing as a separate, later, independently managed lineage** (fresh Play upload key, Google-side managed
re-signing) — do **not** fold the self-distribution key into Play, and do **not** try to make Play "inherit"
the GitHub signature (spec §6 explicitly says same package/signing lineage is **not** automatic with Play App
Signing).

### 1.3 Tradeoffs considered

**Signing-lineage.**
- Pro single keystore: one identity, no ambiguity across releases, in-place upgrades keep working, one thing
  to back up and rotate.
- Con single keystore: a compromise of the one key undermines every past release's claimed integrity (though
  the *practical* impact is low for sideloaded APKs because the SHA-256 + HTTPS chain is the real trust anchor,
  and Play rebuilds are re-signed by Google anyway).
- Play continuity idea (import GitHub key into Play): **rejected.** Google manages and re-signs Play APKs with
  its own keys; uploading our self-distribution key into Play Console would (a) widen exposure of a key that
  also signs our direct downloads, (b) give no legitimacy benefit since Play users already get Google's chain,
  and (c) complicate revocation — if either lineage needs rotation you'd be reluctant to rotate the shared key.
  Keep them separate.

**Key rotation.**
- Rotate the GitHub release key **when**: (a) the key may be compromised, (b) the holder changes, or (c) once
  per **major** version family (e.g. a new 1.x key vs a later 2.x key) — cheap, bounded rotation that keeps
  lineage but limits blast radius. Do **not** rotate on every minor/patch release.
- Rotation procedure: generate a new keystore, sign thereafter with the new key, keep the old key only for
  verifying prior releases, document the rotation date + reason in the release notes, publish the new public
  certificate.
- Because signing identity (`com.audioape.player`) is separate from the key, rotating the key does **not**
  break in-place upgrades.

**Play "upload key" vs "signing key".**
- The **Play App Signing upload key** is the developer-controlled key you hand to Play Console; Google stores
  and uses it to re-sign your APK for Play. It never leaves Google's custody after upload.
- Our self-distribution **signing key** stays entirely in our custody and is used to sign APKs we host on
  GitHub.
- Recommendation: generate a **fresh** Play App Signing upload key under AA-071 from the same protected local
  key-generation environment (offline), upload it to Play Console, and **do not reuse** the GitHub release
  keystore for it. Record both lineages in ADR-0001's successor decision record.

---

## 2. Secure key generation & storage (OWNER runs these — offline/local, never in CI)

> These are the exact commands **you** will run on a trusted, **offline** machine. **Do not** run key
> generation on the CI runner, on a shared server, or in the repo working tree. Blocks producing any real key
> until the §5 decisions are approved.

### 2.1 Prereq
- A trusted, offline (or network-isolated), single-user machine with the JDK/`keytool` used by the project
  (JDK 17+; matches `app/build.gradle.kts` toolchain).
- **No real key is generated by this plan.** The commands are the approved recipe.

### 2.2 Recommended: a single JKS/PKCS#12 release keystore with one self-signed RSA-2048 release certificate

```bash
# 1) Choose a non-repo, non-CI, OWNER-owned location. Example only — pick your own:
export KEY_DIR="$HOME/.audioape/keys"          # NEVER under the repo, NEVER in CI
mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"

# 2) Generate a strong store password via a password manager / OS keychain.
#    If you must mint one here, do it offline and write it ONLY into your OS keychain:
#    (macOS) security generate-random -b 256 | base64
#    (Linux) openssl rand -base64 48
#    Store it in your OS keychain (Keychain Access / GNOME Keyring / Bitwarden / 1Password).
#    NEVER paste it into a shell history you keep, a file in the repo, logs, or CI.

# 3) Create the release keystore with one self-signed signing certificate.
#    ALIAS: use a stable alias, e.g. "audioape-release".
keytool -genkeypair -v \
  -keystore "$KEY_DIR/audioape-release.jks" \
  -storetype jks \
  -alias audioape-release \
  -keyalg RSA -keysize 2048 \
  -validity 1095 \
  -dname "CN=audioape.dev, O=Audio Ape, OU=Release Signing" \
  -ext "BC=ca:false" -ext "KU=digitalSignature" \
  -storepass "$(security 2>/dev/null || echo 'FROM_OS_KEYCHAIN')" \
  && chmod 600 "$KEY_DIR/audioape-release.jks"
```

> Swap the `-storepass` source for whatever your OS keychain exposes; the line above is illustrative. The hard
> rule: **the store password lives only in the OS keychain / password manager**, never in a shell variable
> written to logs, `.bashrc`, a committed properties file, or CI.

**Export the public certificate only** (for clarity and for anyone auditing signatures). The private key stays
in the keystore and never appears as a file:

```bash
keytool -exportcert -keystore "$KEY_DIR/audioape-release.jks" \
  -alias audioape-release -rfc -file "$KEY_DIR/audioape-release.crt" \
  -storepass "$(from your keychain)"
```

### 2.3 Where to store the keystore (approved pattern)

| Item | Where it lives | NEVER |
|---|---|---|
| Release keystore `*.jks` | `$HOME/.audioape/keys/` (owner-only, `chmod 700` dir, `600` file), **outside the repo** | repo, CI, container image, artifact cache |
| Store password | OS keychain / password manager | plaintext files, logs, CI secrets exposed to build logs, `.env`, `secrets.properties` |
| Public cert `*.crt` | *May* be committed at `docs/signing/` for auditors / `keys/public` for verification docs (public only) | never the private keystore |
| `local.properties` / `secrets.properties` | app-local, gitignored; may hold *paths/IDs* but no private key material | committing anything secret |

### 2.4 do-NOT list (violates AGENTS.md rule 11)

- **Do NOT** create the keys in the repo directory or in CI.
- **Do NOT** commit `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `secrets.properties`, `local.properties`, or any
  private key / store password — including inside screenshots, logs, fixture data, or CI config.
- **Do NOT** echo or log the keystore, `-storepass` value, or keystore path in any build/log/CI output.
- **Do NOT** put the keystore on a shared drive, in a container image, in a package artifact, or in a
  password manager sharing group the repo can read.
- **Do NOT** hardcode the alias/keystore path into committed source; read it from `local.properties`
  (gitignored) or an environment variable provided at release time.
- **Do NOT** upload the release keystore to a public code-hosting secret store used by CI unless section 4's
  CI-with-protected-secrets option has been explicitly chosen by the owner (default is local-only, so keep
  it out of CI entirely).
- **Do NOT** reuse the auto-generated `android-keystore/` debug keystore (the "android"/debug identity that
  Android Studio fixtures) as the release keystore.

### 2.5 `.gitignore` baseline (already adopted per ADR-0001; keep it)

```gitignore
*.jks
*.keystore
*.p12
*.pfx
secrets.properties
local.properties
```

---

## 3. Release workflow (GitHub-first, per G9 / AA-069)

> Per spec §6 and G9: GitHub is the first public delivery lane; **Play is a separate, reviewed step (AA-071)**
> and there is **no automatic in-Play self-update**. This workflow covers the GitHub lane only.

### 3.1 Preflight (owner-gated, matches AA-069 "never claim release built before commands succeed")

- [ ] `./gradlew :app:test` and release-build test suite pass from a clean tree.
- [ ] No open P0 data-loss / security / audio-focus bugs (G8 exit).
- [ ] Legal/brand/rights clearance recorded (AA-063/G8).
- [ ] Target device tested and device/OS matrix updated (G0/G8).

### 3.2 Local signed release build (default; see section 4)

```bash
# 0) Load keystore path from local.properties (gitignored) or env, NOT committed source.
export AA_KEYSTORE="$HOME/.audioape/keys/audioape-release.jks"
export AA_KEY_ALIAS="audioape-release"
#    store password comes from your OS keychain into the process env at build time, e.g.:
#    export AA_KEY_STOREPASS="$(keyring get audioape release-store)"

# 1) Production APK + reproducible dependency snapshot (AA-069).
./gradlew :app:assembleRelease --no-daemon

# 2) Sign the assembled APK with the release keystore.
#    (Your Gradle release task should set signingIdentity + keystore from the env above; the
#     equivalent standalone command — substitute the real APK path): 
keytool -importcert   # (public cert into trust as needed by tooling)
#    jarsigner / apksigner (Android) — example using Android apksigner:
"$(find ~/android-sdk -name apksigner -path '*/tools/*' 2>/dev/null | head -1)" \
  sign --key "$AA_KEYSTORE" --key-pass-passenv AA_KEY_STOREPASS \
  --ks-type JKS --ks-key-alias "$AA_KEY_ALIAS" \
  build/app/outputs/apk/release/audio-ape-<VERSION>-release.apk

# 3) Produce the SHA-256 (G9 / AA-069 hash requirement).
sha256sum build/app/outputs/apk/release/audio-ape-<VERSION>-release.apk \
  > build/app/outputs/apk/release/audio-ape-<VERSION>-release.apk.sha256
```

**Release artifact set** (each item published with the GitHub release; G9/AA-069):

| Artifact | Purpose |
|---|---|
| `audio-ape-<VERSION>-release.apk` | signed release APK |
| `audio-ape-<VERSION>-release.apk.sha256` | **pin-able SHA-256** callout in release notes |
| `audioape-release.crt` | public signing certificate (docs/signing/) for signature verification |
| `SBOM` / dependency-snapshot + `NOTICE` | reproducible dependency snapshot + license notices (AA-069) |
| `LICENSES.md` / `PRIVACY.md` | license notices + privacy disclosure (G9 / AA-069) |
| `RELEASE_NOTES_<VERSION>.md` | release notes (see 3.4) |
| source tag + `.github/workflows` record | provenance: exact commit, CI artifacts, lockfile hash |

### 3.3 Verify before publishing

```bash
sha256sum -c audio-ape-<VERSION>-release.apk.sha256      # must PASS
./gradlew :app:verifyReleaseSignature                    # or apksigner verify
```

### 3.4 Release notes content (G9 / AA-069 responsibilities)

- **CHANGELOG:** user-visible changes since previous release, each traceable to a ticket/gate.
- **Hashes:** the pinned **SHA-256** of the APK, repeated prominently; note the signing certificate
  (`audioape-release.crt`) so it can be independently checked.
- **License notice:** reaffirm third-party license inventory + Audio Ape license + no-unattributed-fixtures
  statement (spec §6, AA-063).
- **Privacy disclosure:** no accounts/server/telemetry (CTA-ROADMAP §6 / PRV-001); local logs only.
- **Install/update instructions:** how to sideload, how to verify the SHA, tested upgrade path from preceding
  beta (AA-069), and the **explicit statement that the Play variant does NOT self-update** (spec §6).

### 3.5 Tag, commit, and attach to a GitHub release

```bash
# 1) Tag a protected release commit (annotated).
git tag -a v<VERSION> -m "Release v<VERSION> — <one-line summary>" <release-sha>

# 2) Push the tag.
git push origin v<VERSION>

# 3) Create the GitHub release + attach assets via gh (no secrets in any arg/output).
gh release create "v$VERSION" \
  --repo ThaMountain/audio-ape \
  --title "Audio Ape v$VERSION" \
  --notes "$(cat RELEASE_NOTES_$VERSION.md)" \
  build/app/outputs/apk/release/audio-ape-$VERSION-release.apk \
  build/app/outputs/apk/release/audio-ape-$VERSION-release.apk.sha256

# 4) Attach SBOM / license / privacy / public-cert assets if not already in the release.
gh release upload "v$VERSION" \
  --repo ThaMountain/audio-ape \
  sbom.json RELEASE_NOTES_$VERSION.md LICENSES.md PRIVACY.md audioape-release.crt
```

- Keep the tag immutable/`--sign-tags` in CI so it can never be rewritten to point at a different tree.
- **Never** generate keys or secrets inside `gh release *` commands or their environment (rule 11).

### 3.6 G9 exit check

- [ ] APK is **actually downloadable** from the GitHub release page.
- [ ] Signed + SHA-256 published + verify command passes.
- [ ] Upgrade from preceding beta tested on a real device.
- [ ] Technical/legal limitations accurately stated; source/test artifacts + checksum accessible.

---

## 4. CI posture

**Signing in CI is risky** because a CI secret that can sign release APKs is a permanent, high-value,
automatable signing oracle; any supply-chain or PR-cache compromise or secret-leak in build logs then yields
plantable, "validly signed" APKs. For a **single-maintainer, open-source** project the simple, safe answer is:

> **RECOMMENDED: local-only signing with a manual release step.** The owner builds the release APK on a
> trusted local machine, signs it there, verifies the SHA, and uses `gh release create` to publish. CI stays
> for **verification only** (SHA recompute, signature verify against the committed public cert, artifact
> checksum checks, no write access to signing material).

Rationale for this single-maintainer project:
- Lowest risk surface — the signing key never leaves the owner's machine (rule 11 stays trivially obeyed).
- Simple, auditable, no secret-management infrastructure to maintain for a one-person project.
- No CI secret can ever be exfiltrated to mint release APKs.
- CI still gives reproducibility/QA value without ever needing the key.

**Alternative (only if the owner explicitly chooses it):** CI with **protected secrets** — a
`RELEASE_SIGN_*` secret group restricted to the `main` branch, never forwarded to dependabot/PR workflows, a
manual `workflow_dispatch` release job with `write`-scoped `GITHUB_TOKEN`, short-lived key via a cloud KMS
(e.g. GitHub Actions + a KMS-backed signing subsystem), and strict log redaction. Cost: key custody in an
external secret store, more moving parts, larger blast radius, ongoing maintenance — not justified for a
single maintainer at this stage.

**Decision for this project (default recommendation):** local-only signing + manual release; revisit
CI-with-protected-secrets only if the project gains multiple maintainers or an automated release cadence.

---

## 5. Owner DECISIONS checklist

Owner must check each box to approve this plan (and, where noted, create/annotate the parallel ticket).

- [ ] **D1 — Keystore location & custody:** approve keeping the release keystore at an owner-only path
  **outside the repo/CI** (e.g. `$HOME/.audioape/keys/`), dir `700`, file `600`. (Ticket AA-069.)
- [ ] **D2 — Keystore format & cert:** approve one JKS/PKCS#12 release keystore, RSA-2048 self-signed
  `audioape-release` cert, and the exact `keytool` recipe in §2.2.
- [ ] **D3 — Store-password protection:** approve OS-keychain/password-manager custody; **no** plaintext
  password file, shell-history export, log, or CI exposure.
- [ ] **D4 — Sign vs. hash-only:** approve signing every GitHub release APK **plus** publishing the pinned
  SHA-256 (recommended), or explicitly waive signing to `SHA-256 only` (not recommended).
- [ ] **D5 — Signing identity frozen:** confirm `com.audioape.player` as the constant `signingIdentity`
  (in-place upgrade lineage depends on it — changing later breaks upgrades).
- [ ] **D6 — Rotation policy:** approve key rotation **per major version family** and on compromise/holder
  change (not per minor/patch); document rotation date + reason in release notes.
- [ ] **D7 — Play lineage separation (AA-071):** approve that Play App Signing receives a **fresh, separate**
  Play upload key and does **not** reuse or inherit the GitHub signing key.
- [ ] **D8 — No in-Play self-update (spec §6):** confirm the Play variant ships **without** an automatic
  in-Play self-update path; GitHub lane may offer an upgrade check/in-place-update only under the approved
  package-id lineage rules.
- [ ] **D9 — CI posture:** approve **local-only signing + manual release** (recommended), or explicitly
  select CI-with-protected-secrets per §4 with a documented risk acceptance.
- [ ] **D10 — Version/tag scheme:** approve `vX.Y.Z` annotated tags from an immutable release commit and the
  `versionCode`/`versionName` mapping used by the build.
- [ ] **D11 — Release-notes scope (G9/AA-069):** approve that every release ships CHANGELOG + SHA-256 +
  license notices + privacy disclosure + install/update instructions + tested-upgrade note.
- [ ] **D12 — Reproducible snapshot (AA-069):** approve generating a pinned dependency/SBOM snapshot with each
  release and publishing it as an asset.

---

## 6. Summary of the agreed posture

- One protected **release keystore** signs all GitHub-first APKs (constantly identified as `com.audioape.player`).
- **Play App Signing is a separate lineage** (fresh key, Google-managed re-signing) handled at AA-071 — never
  fold the two together.
- Keys are generated offline by the owner, stored out-of-repo, password in the OS keychain — never committed,
  logged, or placed in CI (AGENTS.md rule 11).
- **Local-only signing + manual release**; CI verifies (SHA recheck, signature verify) but cannot sign.
- No automatic in-Play self-update; GitHub lane carries hashes + license + privacy disclosures per G9.