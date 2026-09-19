# AA-020 — Plugin credential vault — headless correctness evidence

Scope: `:plugin:host` (new Android library). All tests are Robolectric unit tests: headless,
deterministic, no physical device. Real-device Keystore instrumentation is a documented
follow-up (the Android Keystore backend cannot run headless).

## Test class: `PluginCredentialVaultTest` (12 tests)

| Test | Verifies |
|---|---|
| store then read round-trips the secret | positive path, AES/GCM round trip |
| read returns not found for missing plugin key and missing credential | `VaultNotFound` |
| plugins cannot read each other's credentials | namespace isolation (distinct digest/alias/file) |
| cross-plugin alias collision is impossible by construction | digest/alias/file-name uniqueness |
| overwrite replaces the old value | overwrite semantics |
| delete removes one credential and leaves siblings intact | delete + idempotent delete |
| clear all removes every credential and the keystore key | clearAll wipes file + ring |
| invalid plugin or credential ids are rejected as forbidden | `VaultForbidden` on `/`, empty, controls |
| corrupt ciphertext file is a loud io failure, not not-found and not silently healed | `VaultIoFailure` + `clearAll` recovery |
| key invalidation returns typed key-invalidated and re-auth can re-store cleanly | invalidation seam + re-auth |
| ciphertext at rest never contains the plaintext bytes | on-disk scan (real ciphertext path) |
| secret bytes never appear in vault log output | no secret-bearing string API, no logging |

## Simulated invalidation

`FakeVaultKeyRing` (test seam) stores real JCA `SecretKeySpec(32-byte AES)` handles per
plugin digest. A per-digest `invalidated` flag makes every key access throw
`android.security.keystore.KeyPermanentlyInvalidatedException`, the exact type the physical
keystore backend raises on biometric/strongbox rule changes. The re-auth flow is: assert
`VaultKeyInvalidated` on read/store/clearAll → host clears the dead state (`recover`) →
re-consent → re-store succeeds. No silent re-create/decrypt, per AA-020.

## Ciphertext-at-rest

The on-disk bytes are produced by the production `CipherBlob` path with the production GCM
construction; the test reads the file back and asserts the plaintext is absent with a
byte-level `contains` scan (not a string `contains`).

## Seams

- `VaultKeyRing` (interface) is the only seam: production `AndroidKeyRing` wraps
  `KeyStore.getInstance(String)`; tests inject `FakeVaultKeyRing`.
- Alias/file derivation (`VaultKeyRing.pluginDigest`, `VaultKeyScoped`) is shared production
  code, so the namespace-isolation contract tested here is exactly what ships.
