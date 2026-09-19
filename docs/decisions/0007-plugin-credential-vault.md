# AA-020 · Audio Ape — Plugin Credential Vault Prototype (Android Keystore + AES/GCM)

**Owner:** Nick · **Status:** IMPLEMENTED on branch `aa/020-vault` (not merged) · **Gate:** AA-020
**Source-of-truth refs:** `AUDIO_APE_MASTER_SPEC.md` §1.5 PLG-004 and §6,
`docs/ARCHITECTURE_AND_CONTRACTS.md` §F "Vault" and §B, `docs/EXECUTION_PLAN.md` AA-020,
`AGENTS.md` rules 6/10/11/12.

## 1. Decision

The `:plugin:host` module (new Gradle Android library, minSdk 29 / compile SDK 36) ships a
typed, host-private credential vault backed by the Android Keystore. Key material is one
**AES-256 secret-key entry per plugin**, kept entirely inside the Keystore; only ciphertext
(AES-256/GCM) ever touches the app-private filesystem.

**Per-plugin keys were chosen over a single master key** because:

- plugin scale is tiny (bounded, declarative v0 directory), so per-plugin key count is small;
- key-lifecycle isolation: one plugin's revocation/invalidation can never affect another
  plugin's ciphertext (a master key would couple every plugin to one revocation event);
- alias derivation is one prefix + one SHA-256 step per plugin id, so the added state is
  negligible;
- there is no key-wrapping round trip to build or audit.

This matches the architecture's "host-private encrypted bytes keyed by (pluginId, credentialId)
with Android Keystore-managed key material". The `APP` keystore domain (the app-facing
`KeyStore.setEntry`/`getKey` JCA path) is the app-level "host-private" boundary: plugin
declarative code never obtains a keystore reference, so it cannot enumerate another plugin's
alias. Cross-plugin reads are impossible **by construction** — both the keystore alias and the
on-disk file name are derived from the same SHA-256 digest of the plugin id
(`vault.<digest>` / `vault.<digest>.bin`), and each record's GCM authenticator covers the
digest as AAD, so a blob written for one plugin cannot be replayed under another.

## 2. Crypto / storage shape

- AES-256-GCM, 12-byte random IV per store, 128-bit tag, `NoPadding`.
- JCA `Cipher.getInstance("AES/GCM/NoPadding")`; plaintext is passed to `doFinal` only
  through the Kotlin `ByteArray` (interops with JCA `byte[]`). Keys are produced as
  `SecretKeySpec(randomBytes(32), "AES")` — the portable construction that honors an
  explicit key length across providers (including the headless OpenSSL/Conscrypt provider
  used in Robolectric tests, where `KeyGenerator.init(32)` silently yields a 4-byte key, an
  important portability trap this record documents).
- At rest: one per-plugin binary file per plugin, fixed big-endian layout
  (`AAVAULT` magic + version + count, then per credential: id-len u16, id UTF-8, iv 12,
  ciphertext-len u32, ciphertext, tag 16). Strict parsing rejects wrong magic/version,
  over-bounds lengths, duplicate ids, and trailing bytes → `VaultIoFailure`.
- Writes are atomic (same-dir temp + fsync + `Files.move` replace) so a crash never leaves a
  torn record.
- `androidx.security:security-crypto` was **deprecated upstream** ("use Android Keystore
  directly" per its maintainers), so we intentionally depend on **no third-party crypto
  library** — direct Keystore + JCA, which is the current official recommendation.

## 3. Typed result envelope (host-boundary style, AA-018)

Every operation returns exactly one sealed `VaultResult` case — `VaultSuccess(value)`,
`VaultNotFound`, `VaultKeyInvalidated`, `VaultIoFailure`, `VaultForbidden`. No exceptions
cross the boundary; no detail string carries plugin ids, credential ids, or secrets (rule 11).

## 4. Key invalidation

The Keystore can permanently invalidate a key (biometric enrollment, strongbox/device-lock
rule change, backend reset). Production maps `android.security.keystore.
KeyPermanentlyInvalidatedException` to `VaultKeyInvalidated` on every read path, and the host
**never silently recreates or re-decrypts**: re-authentication (re-run the plugin
authorization flow + re-consent) is the caller's explicit job, then `clearAll` may be called
to drop unrecoverable state. The test seam is a deterministic in-memory `VaultKeyRing` whose
per-digest invalidation flag throws exactly that exception — the physical Keystore backend
cannot run headless, so the seam is how "simulate invalidation and re-auth" is proven without
a device.

## 5. Trust boundaries / non-goals (AA-020 scope)

- The permission sheet UI is AA-023; this ticket provides the vault + typed API only and does
  not wire into `:app`.
- The host interpreter is AA-023; no plugin execution exists here.
- `KeyProtection` is currently `PURPOSE_DECRYPT` with no strongbox/biometric gate — a v0
  decision for the token vault, and a documented AA-023 candidate to tighten per plugin if
  the permission sheet requires it. This is **not** silent downgrade: app-domain keys are
  already Keystore-managed and invalidatable; the protection build is centralized in
  `VaultKeyRing.keyProtection()`.
- No physical-device/emulator Keystore pass was run in this ticket (Robolectric is
  deterministic and headless); that instrumentation pass is an explicit follow-up.
