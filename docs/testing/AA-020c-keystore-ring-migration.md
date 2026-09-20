# AA-020c — OS-custodied key ring + vault migration (testing evidence)

Status: **IMPLEMENTED + on-device verified** (2026-09-19/20) **+ reviewer-hardened** (2026-09-20:
strict ring error semantics, journal/backup crash-safe migration, error-propagation tests).
Supersedes ADR-0008's file-backed fallback as the *production* key custody; file-backed custody
remains only as the migration source. See ADR-0009 for the verified API sequence this
implementation uses.

## What changed

| File | Change |
|---|---|
| `plugin/host/.../AndroidKeyRing.kt` | NEW — OS-custodied `VaultKeyRing` (ADR-0009 sequence: single-arg `KeyStore.getInstance("AndroidKeyStore")` + `load(null,null)` + `KeyGenerator`/`KeyGenParameterSpec`; reads via FRESH loaded instances; strict absent-vs-failure semantics) |
| `plugin/host/.../VaultKeyMigrator.kt` | NEW — one-time FileBackedKeyRing → OS migration with write-ahead journal + per-file backup; retry-safe AND crash-safe at every file boundary |
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
| `AndroidRingBehaviorProbeTest` — pins the ring's edge semantics (below) | PASS (probe captured) | — (emulator) |

Bench (emulator, logcat `AA020cBench`): avg/median/p95 of a fresh-KeyStore + load + getKey =
**0.16 ms / 0.15 ms / 0.22 ms** (25 iterations) — keystore2 makes vault opens effectively free.

## Headless regression (after CipherBlob change + review hardening)

`./gradlew testDebugUnitTest` → **46 tests, 0 failures** across modules, incl. the new
`VaultKeyMigratorTest` 6/6 and the `ThrowingKeyRing` vault-propagation test (see the review
hardening section below). Spotless: clean. Lint: clean.

## Review hardening (2026-09-20, external review of PR #14)

### Ring error semantics — absent ≠ failure (empirically pinned)
`AndroidRingBehaviorProbeTest` (emulator, Android 16) established the keystore's actual edge
behavior, so the ring no longer GUESSES:
```
fresh.containsAlias(absent)  = false          (works on fresh instances)
fresh.getKey(present)        = KEY:AES
fresh.deleteEntry(present)   = OK
fresh.getKey(afterDelete)    = NULL           (absent = null, never an exception)
fresh.deleteEntry(absent)    = OK             (deleteEntry is idempotent)
```
`AndroidKeyRing.pluginKeyOrNull` now returns null ONLY for a genuinely absent alias and
PROPAGATES every `KeyStoreException`/cast failure (they are real keystore failures → the vault's
typed `VaultIoFailure`; a phantom null could have caused a replacement key to be minted,
invalidating existing ciphertext). `removePluginKey` propagates `deleteEntry` failures and
verifies the key is provably gone before returning (revocation success is only reported after
confirmed deletion; `clearAll` therefore surfaces deletion failure instead of falsely
succeeding). `ThrowingKeyRing` unit test proves vault propagation.

### Migration — retry-safe AND crash-safe at every file boundary
`VaultKeyMigrator` rewritten around a write-ahead journal (`migration.journal`, atomic rewrites)
+ per-file backup (`*.bak-migrating`):
- state machine per plugin file: PENDING → (decrypt legacy → re-encrypt OS → verify TEMP)
  → REPLACED_UNVERIFIED (written BEFORE the swap) → swap (original → backup, temp → final)
  → verify FINAL → DONE → drop backup
- a file already DONE is **skipped, never re-decrypted** — a retry after a partial failure can
  never try to decrypt an OS-encrypted file with the legacy key
- any crash point is repairable: missing final → restore backup → replay; verified final →
  complete DONE; corrupt final → restore backup → re-migrate
- identity discovery includes leftover BACKUPS: a file whose swap crashed mid-way (final
  missing, original in backup) is still migrated/recovered — the initial implementation MISSED
  these and could have deleted the master key while credentials sat in the backup (real
  data-loss bug caught by the crash tests and fixed)
- master key deleted only when every plugin is DONE and no live backup remains

New JVM tests (6): full success; THE reviewer scenario (A migrates, B fails → retries preserve
everything → repaired run completes + master deleted last); crash after swap; crash before
final swap; corrupt swapped file; throwing-ring propagation. All pass.

### Connected-test exit-code discrepancy — RECONCILED
The `connectedDebugAndroidTest` task exited 1 with all-pass per-device XMLs ONLY when BOTH the
emulator and the phone were attached. Single-device emulator run of the identical class filter:
**`exit=0`, 4/4 pass.** Cause: multiple attached devices (AGP result-aggregation quirk in this
AGP version), unrelated to test outcomes and distinct from the AA-015 am-crash heuristic.
Guidance: run connected suites on ONE device at a time locally (disconnect the phone for
emulator runs), and read the per-device XMLs as the evidence.

## Migration protocol (no-loss guarantee, final form)

1. Per plugin identity (final `vault.<digest>.bin` OR leftover `*.bak-migrating`):
   decrypt every record under the LEGACY ring key (AAD=digest) → re-encrypt under the OS key
   (new provider IV, AAD=digest) → write TEMP → fsync.
2. VERIFY the TEMP (fresh read + decrypt under the OS key == original plaintext) BEFORE any
   swap touches the real file.
3. Write-ahead journal (`REPLACED_UNVERIFIED`) → move original → `*.bak-migrating` → move TEMP
   → final → verify FINAL → journal `DONE` → delete backup.
4. Any failure or crash: backup/journal repair on the next run (restore → replay, or complete →
   DONE). A DONE file is never re-decrypted with the legacy key.
5. Only after ALL files are DONE (no failed file, no live backup, no non-DONE journal state)
   is `vault-master.key` deleted; the journal is then discarded.

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