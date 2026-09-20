# AA-020c — OS-custodied key ring + vault migration (testing evidence)

Status: **IMPLEMENTED + on-device verified** (2026-09-19/20). Supersedes ADR-0008's file-backed
fallback as the *production* key custody; file-backed custody remains only as the migration
source. See ADR-0009 for the verified API sequence this implementation uses.

## What changed

| File | Change |
|---|---|
| `plugin/host/.../AndroidKeyRing.kt` | NEW — OS-custodied `VaultKeyRing` (ADR-0009 sequence: single-arg `KeyStore.getInstance("AndroidKeyStore")` + `load(null,null)` + `KeyGenerator`/`KeyGenParameterSpec`; reads always via a FRESH loaded instance) |
| `plugin/host/.../VaultKeyMigrator.kt` | NEW — one-time FileBackedKeyRing → OS migration in digest space (temp-file + atomic replace + verify-each-record + master deleted only on full success) |
| `plugin/host/.../CipherBlob.kt` | ENCRYPT now lets the provider generate the IV (`cipher.init(ENCRYPT_MODE, key)`, store `cipher.iv`). **Discovered on-device:** Android Keystore keys reject caller-provided IVs on encrypt (`InvalidAlgorithmParameterException: Caller-provided IV not permitted`, both devices). File format unchanged (per-blob IV still persisted). |
| `plugin/host/.../PluginCredentialVault.kt` | default ring → `AndroidKeyRing()` |
| `app/.../keystore/AndroidKeyRingDeviceTest.kt` | NEW — 4 device tests incl. migration E2E + revocation + `getKey` latency bench |
| `app/.../keystore/VaultKeystoreSmokeTest.kt` | master-key-file assertion replaced with fresh-instance read-back (OS custody proof) |

## On-device results (real runs, Android 16 / API 36)

AGP `connectedDebugAndroidTest` ran with BOTH devices attached; XML per device (0 failures/0
errors on both; Gradle task exit-code=1 is the documented AGP artifact family — the report XMLs
are the evidence):

| Test | Emulator dev36 (16 GB Pixel-class) | Galaxy S25 Ultra (SM-S931U) |
|---|---|---|
| `osRingCreatesReadsEncryptsAndPersistsAcrossFreshInstances` | PASS 0.001s | PASS |
| `osRingRevocationInvalidatesCiphertext` (deleteEntry → old ciphertext undecryptable) | PASS 0.014s | PASS |
| `migratesFileBackedVaultToOsRingWithoutLoss` (seed 2 plugins/3 creds via public API → migrate → OS reads equal) | PASS 0.005s | PASS |
| `VaultKeystoreSmokeTest` (store/read/delete/clearAll + fresh-instance read + at-rest ciphertext-only) | PASS 1/1 | PASS 1/1 |
| `measuresOsKeyGetLatency` — avg/median/p95 of `pluginKeyOrNull` (fresh KeyStore + load + getKey) | see bench line below | — (emulator only) |

Bench (emulator, logcat `AA020cBench`): avg/median/p95 of a fresh-KeyStore + load + getKey =
**0.16 ms / 0.15 ms / 0.22 ms** (25 iterations) — keystore2 makes vault opens effectively free.

## Headless regression (after CipherBlob change)

`./gradlew :plugin:host:testDebugUnitTest :app:testDebugUnitTest` → **40 tests, 0 failures**,
incl. the new `VaultKeyMigratorTest` 3/3 (success path, tamper-failure path keeping master,
revocation-after-migration). Spotless: clean. Lint: clean.

## Migration protocol (no-loss guarantee)

1. Per `vault.<digest>.bin`: read records → decrypt each under the LEGACY ring key (AAD=digest)
   → re-encrypt under the OS key (new provider IV, AAD=digest) → temp file → fsync → atomic
   replace.
2. VERIFY each migrated record (fresh read + decrypt under the OS key == original plaintext).
3. Only after ALL files verify: delete `vault-master.key`.
4. Any failure: temp removed, original + master untouched (retry-safe).

`VaultKeyMigrator` is invoked explicitly (one-shot, owner-triggered in a future upgrade path);
the vault does NOT auto-migrate, so a failed or partial migration never leaves the app
half-migrated.

## Notes / follow-ups

- Android TV / emulator reboot path: OS key persistence across device reboot was already proven
  for the probe in ADR-0009 (emulator rebooted, key read back post-boot); not re-run here.
- Phone reboot path remains owner-consented follow-up (device-reboot policy).
- `getKey` cost matters at vault open: each plugin credential read does one fresh KeyStore +
  load + getKey (0.16 ms avg / 0.22 ms p95 on the emulator) — negligible.
- Key revocation is now REAL: `deleteEntry` deletes the OS key; old ciphertext fails GCM auth,
  surfacing as `VaultIoFailure` → plugin re-auth. (FileBackedKeyRing's `removePluginKey` remains
  a record-level no-op — that ring is migration-only now.)