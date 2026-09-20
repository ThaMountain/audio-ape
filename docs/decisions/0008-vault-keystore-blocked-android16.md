# ADR-0008 — Android Keystore OS-backed plugin vault: BLOCKED on Android 16/API 36 (file-backed fallback)

**Status:** OVERTAKEN by ADR-0009/AA-020c at the owner's direction (2026-09-19/20) — the block
was incorrect API usage, not a platform defect; `AndroidKeyRing` (OS custody) is now the
production key ring and `FileBackedKeyRing` migration-only. **Retained for history.**

**Status:** Accepted (2026-09-19 — with a consciously accepted, documented downgrade) · **Owner:** Nick · **Ticket:** AA-020

## Context
AA-020 required "Android Keystore-backed encryption for plugin-specific alias". The `:plugin:host`
vault (`PluginCredentialVault` + `CipherBlob` AES-256-GCM with per-encryption IV and plugin-digest AAD,
per-plugin key aliases) and its invalidation semantics are implemented and unit-tested via a fake key
ring. The production key ring (`AndroidKeyRing`) targeted `KeyStore.getInstance(getDefaultType())`.

## Runtime evidence (2026-09-19, primary-source probing on target runtimes)
On **both** the `dev36(AVD)` emulator and the physical **Galaxy S25 Ultra (Android 16 / SDK 36)**:

| Attempted path | Result (identical on both devices) |
|---|---|
| `KeyStore.getInstance(KeyStore.getDefaultType())` (`BKS` default) + any access | `KeyStoreException: Uninitialized keystore` |
| `KeyStore.getInstance(File, password)` (documented auto-load) | `IllegalArgumentException: File does not exist…` — no create-new-file path |
| `KeyStore.Builder.newInstance(type, provider, protection).getKeyStore()` for default type and `AndroidKeyStore`, all providers | `KeyStoreException: KeyStore instantiation failed` |
| `KeyStore.getInstance("AndroidKeyStore", "AndroidKeyStore")` (provider/algorithm discovered via `Security.getProviders()`) | `KeyStoreException: Uninitialized keystore` |
| `Class.forName("android.security.keystore.KeyStore")` | `ClassNotFoundException` — **class absent from runtime** |
| Reflected runtime `java.security.KeyStore` methods (JDK surface on device) | `load(…)`, `setKeyEntry/getKey(alias, char[])` JCA forms; **no `initialize`/`OPTION_*`/`setEntry(…,ProtectionParameter)` present at runtime despite the SDK stub** |

Conclusion: the Android 16 / API 36 runtime does not expose a usable app-facing key-value keystore
through the JCA surface the SDK stubs describe, for any documented creation pattern. The API the
stubs suggest (and the headless test double implies) does not exist on the target. This is an
unresolved platform-API discrepancy; per AGENTS.md rule 12 the blocked deliverable is recorded and
**not** silently claimed.

## Decision
1. The vault **interface, crypto, isolation, and invalidation semantics stay as built** (they are
   correct and target-independent — AES-256-GCM, per-encryption IV, GCM-AAD bound to plugin digest,
   digest-derived namespacing, typed `VaultKeyInvalidated` re-auth flow).
2. The **production key ring becomes `FileBackedKeyRing`** (this PR): one app-private 256-bit master
   key generated once with `SecureRandom` at `{vaultDirectory}/vault-master.key` (0600 best-effort),
   per-plugin AES keys derived via HMAC-SHA256(masterKey, sha256(pluginId)) with no per-plugin bytes
   at rest; backup/transfer exclusions already cover app-private storage (AA-016 rules).
3. **Explicit downgrade, accepted:** file-backed keys are extractable by a same-user attacker with
   app-private file read access (no OS key custody). Not shipped as "equivalent" — documented as the
   v0 fallback.
4. `VaultKeyRing` remains the seam; `AndroidKeyRing` is removed from the production path but its code
   and this ADR document exactly what was attempted, so a maintained wrapper / clarified API can slot
   back in without touching the vault.
5. **Follow-up ticket (blocked deliverable, not complete):** re-evaluate OS-custodied key custody when
   the Android Keystore app surface is clarified (KeyStoreManager grant flow, SDK correction, or an
   actively maintained wrapper like androidx.security) and re-run on the physical device. Until then,
   the master key file MUST remain excluded from backup (already enforced) and cleared on uninstall
   (it lives in app-private data → wiped with the app).

## Consequences
- Plugin secrets are encrypted at rest in app-private storage (ciphertext-only, AAD-bound, namespaced)
  with a documented, weaker key-custody story than Keystore-pinned — the honest, safe placeholder.
- The Keystore-backed requirement of spec §6 remains **open** (rule 12), tracked by ADR-0008.