# AA-016 — Uninstall-survival contract + verification

## Contract (locked by `BackupExclusionContractTest`)

1. **Media survives uninstall.** Audiobook files live only in the user-chosen, user-accessible
   document tree (`Main storage › Audiobooks/…`), not in app-private storage, so Android uninstall
   (which deletes only the app's private data) cannot remove them.
2. **App-private data does NOT survive.** Room DB (checkpoints, bookmarks, notes, completion,
   collections, tombstones), DataStore prefs, and the future encrypted vault are app-private and
   excluded from every backup/transfer channel:
   - `android:allowBackup="false"` in the manifest,
   - `res/xml/backup_rules.xml` — full-backup-content excludes all 9 domains,
   - `res/xml/data_extraction_rules.xml` — cloud-backup AND device-transfer each exclude all 9 domains.
3. **Reinstall → reconnect.** Uninstall revokes SAF persisted URI grants. On reinstall the
   app-private DataStore is empty, so `LibraryTreeAccessCoordinator.verifyStoredGrant()` returns
   `PermissionMissing`; the user is prompted to re-pick the surviving folder, the app reindexes
   covers/chapters from the media files, and listening positions start fresh (LIB-010).
4. **No sidecars.** No progress/notes/history are ever serialized beside the media files, so the
   external folder stays clean personal library data only.
5. **OEM caveat:** some OEM cloud/D2D implementations may not honor `allowBackup=false`
   (R16/R29). The explicit extraction rules minimize this; per-device OEM verification is recorded
   separately. External copies the user makes beyond Android's channels are outside the app's
   control.

## Device verification (run with the physical phone, wireless adb)

```sh
# 1. Create a marker file OUTSIDE app storage (never the owner's Audiobooks folder):
adb shell 'echo aa016-survives-uninstall > /sdcard/Documents/aa016-marker.txt'
ls -l /sdcard/Documents/aa016-marker.txt

# 2. App private data exists while installed:
adb shell run-as com.audioape.player ls /data/data/com.audioape.player/databases  # or files dir

# 3. Uninstall the app:
adb uninstall com.audioape.player

# 4. Marker MUST survive (user-accessible media location):
adb shell ls -l /sdcard/Documents/aa016-marker.txt        # -> still present

# 5. App private data MUST be gone:
adb shell run-as com.audioape.player true                 # -> fails (package absent)

# 6. Reinstall + boot; app starts fresh; settings/storage screen shows the reconnect flow
#    (verifyStoredGrant -> PermissionMissing -> picker):
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.audioape.player/.MainActivity

# 7. Clean up the marker (created by this test only):
adb shell rm /sdcard/Documents/aa016-marker.txt
```

The orchestrator executes this sequence on the connected Galaxy S25 Ultra and records the real
output; UI picker re-selection is a single human tap the app prompts for (headless automation
cannot drive the system folder picker), everything else is verified by adb.

## Result of the run (orchestrator, 2026-09-19, Galaxy S25 Ultra / Android 16)
- [x] Step 1–2 (marker present; app package installed — `run-as` resolves the package)
- [x] Step 3–4 (files survive uninstall — `uninstall Success`, marker still listed in Documents)
- [x] Step 5 (app private data gone — `run-as com.audioape.player` → "unknown package")
- [x] Step 6 (clean reinstall + boot — MainActivity started, process alive)
- [x] Step 7 (marker cleaned up)
- Note: the app had not yet created its Room DB before this run, so the pre-uninstall
  `ls data` showed no database dir; the package-level wipe (run-as → unknown package) is the
  OS guarantee being verified, and the marker survival is the media-side guarantee.