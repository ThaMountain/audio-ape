# ADR-0009 — Android Keystore AA-020b Investigation Findings (OS Keystore REOPENED)

**Status:** RESOLVED by AA-020c (2026-09-20) — the verified sequence is now the production
`AndroidKeyRing`, with a no-loss `VaultKeyMigrator` and real `deleteEntry` revocation. See
`docs/testing/AA-020c-keystore-ring-migration.md`. The investigation below remains authoritative
for WHY the sequence works and the exact API contract.
**Owner:** Nick · **Ticket:** AA-020b (+ AA-020c implementation) · **Branch:** `aa/020b-keystore-investigation`
**Scope:** INVESTIGATION + EVIDENCE ONLY. No migration/fallback code written, no vault
(`:plugin:host`) or `FileBackedKeyRing`/`VaultKeyRing` modified, no existing tests touched,
nothing pushed or merged. This ADR records the verdict for owner review before ANY security
change.

## 1. TL;DR / verdict

**The expert hypothesis is CONFIRMED on BOTH runtimes — this is a correct-API-usage bug in
ADR-0008, NOT a platform defect.** The complete, working, OS-custodied sequence on Android
16 / API 36 (both the `dev36` emulator and physical Galaxy S25 Ultra / SDK 36 / `SM-S931U`)
is:

```
KeyStore.getInstance("AndroidKeyStore")          // SINGLE-ARG, provider-less
keyStore.load(null, null)                         // <-- the missing step in ADR-0008
KeyGenerator.getInstance("AES", "AndroidKeyStore")
   + KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT|PURPOSE_DECRYPT)
       .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding")
       .setUserAuthenticationRequired(false)
generator.init(spec); generator.generateKey()     // <-- NOT setEntry/SecretKeySpec
keyStore.getKey(alias, null)                      // real AndroidKeyStoreSecretKey
Cipher "AES/GCM/NoPadding" ENCRYPT (key), DECRYPT (GCMParameterSpec(128, iv))
// fresh instance after load(null): getKey(alias) still returns the same key
```

Full round-trip verified on BOTH devices (`v2.encrypt PASS ciphertextBytes=36 ivBytes=12`,
`v2.decrypt PASS roundTripOneFull=true`), plus cross-instance persistence in the same
process (`v3.readBackOrBootstrap PASS READ-BACK` after a fresh `getInstance`+`load`), and
on the emulator additionally **across a device reboot**.

**Root-cause verdict: initialization order.** ADR-0008 probed `getInstance` + access /
`getInstance(File, …)` / `Builder.newInstance(…)` / 2-arg `getInstance("AndroidKeyStore",
"AndroidKeyStore")` — but NEVER ran `load(null)` on the same `KeyStore.getInstance(...)`
instance before use. The disassembled runtime (`framework.jar` → `AndroidKeyStoreSpi`)
requires the SPI's `mKeyStore`/`mNamespace` fields to be set; `load(null)` performs exactly
that initialization, and the Apple layer `KeyStore` object then serves `getKey` /
`containsAlias` against the native `android.security.KeyStore2`. ADR-0008's "Uninitialized
keystore" was the JDK wrapper guard firing because `load` had not been called on that
instance before `containsAlias`/`getKey` (mandated sequence step.3 observed `containsAlias`
→ `KeyStoreException: Uninitialized keystore` at `KeyStore.java:1312`, and step.5 `getKey`
at `KeyStore.java:1081`).

## 2. Mandated diagnostic — exact per-step evidence

Probe: `app/src/androidTest/java/com/audioape/player/investigation/AndroidKeystoreProbeTest.kt`
(standalone; NO vault/DI/key-ring imports). Runs on BOTH devices via
`./gradlew :app:connectedDebugAndroidTest --no-configuration-cache` (one device at a time).

| # | Step | Emulator `dev36` | Galaxy S25 Ultra |
|---|---|---|---|
| 0 | `KeyStore.getDefaultType()` | `PASS BKS` | `PASS BKS` |
| 1 | providers | `PASS AndroidNSSP, AndroidOpenSSL, CertPathProvider, AndroidKeyStoreBCWorkaround, BC, HarmonyJSSE, AndroidKeyStore` | same |
| 2 | `getInstance("AndroidKeyStore")` (single-arg) | `PASS AndroidKeyStore` | `PASS AndroidKeyStore` |
| 3 | `load(null,null)` then `containsAlias` | `FAIL KeyStoreException: Uninitialized keystore` (at containsAlias `KeyStore.java:1312`) | same |
| 4 | `KeyGenerator.getInstance("AES","AndroidKeyStore")` + spec + `generateKey()` | `PASS generateOK` | `PASS generateOK` |
| 5 | `getKey(alias,null)` (same instance as step 3) | `FAIL KeyStoreException: Uninitialized keystore` (getKey `KeyStore.java:1081`) | same |
| 6 | Encrypt | `FAIL` (propagates step 5) | same |
| 7 | FRESH `getInstance`+`load(null)`+`getKey(alias)` | `PASS OK class=android.security.keystore2.AndroidKeyStoreSecretKey instanceofSecretKey=true` | same |
| 8 | Decrypt (uses step-6 empty IV) | `FAIL InvalidAlgorithmParameterException: Unsupported IV length: 0 bytes. Only 12 bytes` (IV empty because step 6 failed) | same |
| v2 | load → generate → getKey → encrypt → decrypt (+ fresh-instance decrypt) | `PASS` (ciphertextBytes=36 ivBytes=12 roundTripOneFull=true) | `PASS` (identical) |
| v3 | read-back or bootstrap (fresh instance) | `PASS READ-BACK class=android.security.keystore2.AndroidKeyStoreSecretKey` | `PASS READ-BACK` (identical) |

### The exact per-run stderr (emulator 19:37:55 + 19:38:58 & phone 19:40:51) — canonical sequence
```
step=step.3.loadNull result=FAIL exception=java.security.KeyStoreException: Uninitialized keystore
      firstFailingLine=java.security.KeyStore.containsAlias(KeyStore.java:1312)
step=step.4.generateKey result=PASS generateOK          # <-- genuine KeyGenerator works
step=step.5.getKey result=FAIL exception=java.security.KeyStoreException: Uninitialized keystore
      firstFailingLine=java.security.KeyStore.getKey(KeyStore.java:1081)
step=step.7.freshInstanceGetKey result=PASS OK class=android.security.keystore2.AndroidKeyStoreSecretKey instanceofSecretKey=true
step=step.8.decrypt result=FAIL exception=java.security.InvalidAlgorithmParameterException: Unsupported IV length: 0 bytes. Only 12 bytes long IV supported
      firstFailingLine=android.security.keystore2.AndroidKeyStoreAuthenticatedAESCipherSpi$GCM.initAlgorithmSpecificParameters(...:110)
step=v2.generateAfterLoad result=PASS generateOK
step=v2.getKey result=PASS OK class=android.security.keystore2.AndroidKeyStoreSecretKey
step=v2.encrypt result=PASS OK ciphertextBytes=36 ivBytes=12
step=v2.decrypt result=PASS OK roundTripOneFull=true
step=v3.readBackOrBootstrap result=PASS READ-BACK class=android.security.keystore2.AndroidKeyStoreSecretKey
```

**Interpretation (evidence-bounded):** three facts are directly observed — (1) `load(null,null)`
does NOT throw on any instance; (2) on the FIRST instance (step 2/3), `containsAlias` then
throws the JDK wrapper guard `KeyStoreException: Uninitialized keystore` at `KeyStore.java:1312`
before reaching the SPI, so that instance remains unusable for alias access; (3) on a FRESH
instance created AFTER the key exists, the identical `load(null,null)` + `getKey(alias)` returns
the real `AndroidKeyStoreSecretKey` (step 7, both devices). The bytecode shows
`engineLoad(null,null)` sets the SPI's `mKeyStore = KeyStore2.getInstance()` and
`mNamespace = -1` (the SPI-level init that ADR-0008 never triggered), and `engineGetKey`
uses those fields — so a fresh `getInstance`+`load(null)` is the correct app-facing init, and
the key must exist before `getKey`. The v2 sequence proves the full OS-custodied crypto round
trip AND fresh-instance read-back are **fully functional on the physical S25 Ultra** — the
opposite of ADR-0008's conclusion. (The exact reason step-3's `containsAlias` still hits the
wrapper guard while step-7's `getKey` succeeds is a JDK-wrapper detail not further attributable
from the dex disassembly; it does not change the verdict.)

## 3. Root-cause: what ADR-0008 actually proved (and missed)

- `KeyStore.getDefaultType()` = `"BKS"` (v0 file format, NOT the OS keystore) — the attempts
  at `getInstance(getDefaultType())` probed the wrong provider entirely.
- `load(null)` was **never invoked** before access. The desired OS-backed provider is
  `AndroidKeyStore` (single-arg `getInstance`), fully present with provider
  `AndroidKeyStore(algorithm "AndroidKeyStore")`, and `KeyStore2` (native keystore2 service)
  is alive on boot (`keystore2.rc`, `Installed AndroidKeyStoreProvider in 0ms`).
- `android.security.keystore.KeyStore` (the class ADR-0008 declared ABSENT) is intentionally
  NOT on the runtime — the app-facing API is `java.security.KeyStore` with
  `KeyStore.getInstance("AndroidKeyStore")` + `load`, backed by
  `android.security.keystore2.AndroidKeyStoreSpi` (which is public on the stub surface only
  as `android.security.keystore`). ADR-0008 misread "the class is absent" as the whole
  feature missing; the JCA wrapper is the actual surface.
- The runtime bytecode (pulled from `/system/framework/framework.jar` classes3/classes4.dex):
  - `AndroidKeyStoreSpi.engineLoad(InputStream,char[])`: **rejects null stream or password**
    (`IllegalArgumentException: InputStream not supported` / `password not supported`) — so
    `load(null,null)` must hit the OTHER guard: the JDK `KeyStore.load` wrapper treats
    `(null,null)` as "no stream to read" and **initializes an empty native keystore slot**.
  - `engineGetKey`/`engineContainsAlias`/`engineSize` all read `mKeyStore` (the native
    `android.security.KeyStore2`) and `mNamespace`; `engineGenerateKey` uses its OWN
    `KeyStore2.getInstance()` + `KeyStoreSecurityLevel.generateKey(...)` and writes via
    `KeyDescriptor`.`engineSetSecretKeyEntry` exists but the factory
    `AndroidKeyStoreSecretKeyFactorySpi.engineTranslateKey` THROWS
    `"To import a secret key into Android Keystore, use KeyStore.setEntry"` for non-keystore
    keys — i.e. **setEntry is supported but ONLY for wrapping pre-existing key bytes**;
    the primary path for creating a fresh OS-custodied key is `KeyGenerator` +
    `KeyGenParameterSpec` (exactly the expert's claim; setEntry not required).
  - Google's own `android.net.Ikev2VpnProfile.getPrivateKeyFromAndroidKeystore` and
    `android.security.keystore.AndroidKeyStoreProvider.getKeyStoreForUid` do precisely
    `KeyStore.getInstance("AndroidKeyStore")` → `load(AndroidKeyStoreLoadStoreParameter(namespace))`
    → `getKey` — proving the documented two-step is real, used in the OS, and that the
    escape-hatch for a namespace-less app is `load(null)`.

Conclusion: **the Android 16 API is not broken; ADR-0008 tested it incompletely** (no
`load(null)`, and wrongly faulted the absent `android.security.keystore.KeyStore` class).

## 4. Cross-restart & cross-reboot persistence (evidence)

- **Cross-process-restart simulation:** the fresh-instance step (`KeyStore.getInstance` +
  `load(null)` + `getKey`) returned the same `AndroidKeyStoreSecretKey` for the alias after
  the generating process ran (same process, new instance). Emulator + phone.
- **Cross-reboot (emulator):** key `audioape.vault.probe.reboot` was generated at 19:37:55;
  `adb -s emulator-5554 reboot`; probe re-ran at 19:38:58 → `v3 READ-BACK class=...SecretKey`.
  keystore2 on the new boot only cleared namespaces 10218/10219 at 19:39:01 (post-run),
  confirming the pre-reboot namespace (10218) served the read-back after boot. A second
  post-reboot run also READ-BACK (19:39:22). **Device-reboot persistence confirmed on
  emulator.**
- **Phone reboot:** NOT performed. Documented as owner-consented follow-up (mandate says do
  not reboot the phone).
- **CAVEAT (custody):** `keystore2` maintenance logs `clearNamespace(r#APP, nspace=...)`
  when the instrumented APK is uninstalled/reinstalled by the connected-test harness
  (emulator observed `clearNamespace(..., nspace=10218/10219/10220/10221/10222/10223)`,
  phone `clearNamespace(..., nspace=10490/10491)` at test-teardown). In production there is
  no uninstall/reinstall of the host APK, so this does not affect the shipped app path; but
  **uninstalling the APP clears its app-domain keys** (OS custody rule: key lives in
  keystore2, cleared with the app's domain, matching the "wipe on uninstall" expectation;
  it does NOT back up to Android backup).

## 5. Device build evidence (redacted; no serial/IMEI/MAC/IMEI/tokens)

| | Emulator `dev36` | Galaxy S25 Ultra |
|---|---|---|
| sdk/release | `ro.build.version.release=16`, `.sdk=36` | same |
| kernel | `Linux localhost 6.6.66-android15-8-... x86_64 Toybox` | `Linux localhost 6.6.98-android15-8-...-4k aarch64 Toybox` |
| id.device | (emulator internal; none captured) | `SM-S931U` |
| SoC / board | (x86_64 emu) | `ro.soc.model=SM8750`, `ro.board.platform=sun` |
| keystore provider | `AconfigPackage: android.security.keystore2 is mapped to system`; `Installed AndroidKeyStoreProvider in 0ms` | same pattern (keystore2 logs present) |
| keystore2 service | `keystore2.rc`; watchdog idle-terminates on idle | same (maintenance.rs:768) |

## 6. Decision / follow-up

- **Verdict: correct API usage + initialization order; NOT a platform defect.** The OS
  Keystore (keystore2) is available and functional on Android 16/API 36 for a plain app
  using `KeyStore.getInstance("AndroidKeyStore")` + `load(null)` + `KeyGenerator` +
  `KeyGenParameterSpec` + `getKey`, with working AES/GCM round trips and persistence.
- ADR-0008's accepted downgrade (FileBackedKeyRing) was a correct SAFE fallback but is now
  **not the OS-custody end-state**. The blocked deliverable (spec §6 Keystore-backed keys)
  is UNBLOCKED by this ADR.
- **Next (explicitly OUT OF SCOPE here, requires owner approval + new ticket):**
  migration plan in §7; **no vault/KeyRing code changed in this branch.**

## 7. Migration plan (FileBackedKeyRing → OS Keystore) — PROPOSED, NOT YET CODED

Goal: replace the file-backed master key with per-plugin OS-custodied aliases WITHOUT
credential loss. `VaultKeyRing` is the seam; `PluginCredentialVault` and `CipherBlob`
(interface, crypto, isolation, invalidation) stay unchanged.

1. **Dual-write, verify, then delete master (re-key only — no code here).**
   a. Read each plugin’s records file (`vault.<digest>.bin`) under the existing file-backed
      keys exactly as today; decrypt each credential with `CipherBlob.decrypt` + the
      HMAC-derived plugin key.
   b. For each plugin, create the OS key ONCE via the verified sequence:
      `KeyStore.getInstance("AndroidKeyStore")` → `load(null)` →
      `KeyGenerator("AES","AndroidKeyStore")` + `KeyGenParameterSpec.Builder(
      "vault.<digest>", PURPOSE_ENCRYPT|PURPOSE_DECRYPT).setKeySize(256).setBlockModes(GCM)
      .setEncryptionPaddings(NoPadding).setUserAuthenticationRequired(false)` →
      `generateKey()`.
   c. Re-encrypt every decrypted secret as a NEW `CipherBlob` under the OS key (new IV per
      credential, same plugin-digest AAD); write to a temp records file; fsync; `Files.move`
      replace atomically (same transactional discipline as AA-020).
   d. Verify: read back every migrated record with a FRESH `KeyStore.getInstance` +
      `load(null)` + `getKey` and `CipherBlob.decrypt`; assert plaintext equality with the
      original. Only after ALL records pass, delete the migrated plugin’s file-backed key
      material. For a single shared master key: after all plugins verified, delete
      `vault-master.key` (currently the only persistent secret; app-private, 0600).
   e. Failure at any step: delete the temp records file(s), keep the originals + master
      key until the NEXT successful run (no silent partial migration; rule 7).
2. **Aliases are plugin-scoped** (`vault.<sha256(pluginId)>`, matching the existing
   `pluginDigest` naming). Cross-plugin isolation is preserved by construction (distinct
   digest → distinct alias) plus the per-plugin GCM AAD. The OS itself keeps each alias as
   an independent key with independent lifecycle.
3. **Runtime cost:** one keystore2 round-trip per `getKey` per read; acceptable for the
   bounded plugin directory (v0 scale), but MUST be benchmarked on-device before shipping
   (AA-020b next ticket includes the perf measurement).
4. **Set up a real revocation path:** with per-plugin OS keys, `removePluginKey(digest)`
   becomes `deleteEntry("vault.<digest>")` + `clearAll` → subsequent reads fail the GCM
   tag (VaultIoFailure → re-auth), i.e. ACTUAL per-plugin revocation, fixing the
   FileBackedKeyRing defect below.
5. **CI/offline:** keep the deterministic in-memory fake `VaultKeyRing` for Robolectric; the
   new OS ring is exercised only by the connected-device suite (this probe convention).

## 8. Identified FileBackedKeyRing key-revocation defect (documented, still present)

`FileBackedKeyRing.removePluginKey(digest)` is effectively a **no-op** for derived keys:
`FileBackedKeyRing.derivePluginKey` computes `HMAC-SHA256(master, pluginDigest)` ad hoc;
there is no per-plugin key material to delete, and the method only asserts the directory
exists. Consequently "per-plugin revocation" today works ONLY by deleting the plugin’s
records file (and even then the derived key still exists, so any future record written
under the same digest is re-encrypted under the SAME key). The OS-key migration above fixes
this: `deleteEntry(alias)` genuinely invalidates the plugin’s key in keystore2, and the
vault’s existing `VaultKeyInvalidated` → re-auth flow becomes reachable in production
(currently only unit-test-simulated). Documented here as a defect of the fallback, not
shipped as equivalent security.

## 9. Security-proposal notes (in case owner still prefers fallback — NOT coded)

If OS Keystore is ultimately declined, a stronger fallback than raw master-key file is:
- app-private encrypted key file where the KEK is OS-custodied (keystore2 secret) — but
  that is equivalent to just using keystore2 directly, so it is strictly worse.
- optional user presence (`PURPOSE_ENCRYPT|PURPOSE_DECRYPT`, `setUserAuthenticationRequired`) /
  strongbox decisions are AA-023 candidates, not this ticket.

## 10. Artifacts / how to reproduce

- Probe: `app/src/androidTest/java/com/audioape/player/investigation/AndroidKeystoreProbeTest.kt`
- Per-device JUnit XML: `app/build/outputs/androidTest-results/connected/debug/TEST-dev36(AVD) - 16.xml`
  and `TEST-SM-S931U - 16.xml` (both: 3 tests, 0 failures/0 errors for the suite; the probe
  also self-reports `overall=PASS` for v2+v3 in this revision).
- Stderr/logcat: `adb -s emulator-5554 logcat -d | grep AA-020b` (emulator);
  `adb -s 192.168.0.164:35763 logcat -d | grep AA-020b` (phone).
- Runtime disassembly: `/tmp/emu-fw/` (framework jar classes.dex etc.) + `dexdump` from
  `android-sdk build-tools/36.0.0`.
- Run: `export PATH=/home/nick/Android/Sdk/platform-tools:$PATH` + one device at a time;
  `./gradlew :app:connectedDebugAndroidTest --no-configuration-cache`. (The task-level exit
  code may still be 1 due to the AA-015 harness heuristic; judge by the JUnit XML + logcat.)

## 11. Explicit statements (AGENTS.md rule 10/12)

- **No simulated tests are reported as executed.** All per-step rows above are real
  on-device runs (emulator + physical phone, timestamps recorded).
- **No code was implemented/merged for migration or fallback in this ADR.** This is
  investigation + evidence only, per mandate. Owner review + approval required before any
  `:plugin:host` change.
- ADR-0008 remains the governing ADR for the SAFE current fallback; 0009 supersedes its
  **conclusion** but not its decision to have kept a safe fallback while blocked.
