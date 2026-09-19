# AA-014 physical-device E2E

Run from the repository root with a connected physical Android device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

`TwoPartBookPlaybackE2ETest` copies the two synthetic WAV assets into the test app's private files
directory, seeds an in-memory Room database, injects that database into the real
`AudioApePlaybackService`, and connects through a real Media3 `MediaController`. It does not request
a SAF grant and never reads or writes shared/Main storage or the owner's Audiobooks folder. The
production app database is neither opened nor deleted.

The test does not require `POST_NOTIFICATIONS`: Android media playback/session behavior is exercised
whether that optional runtime permission is granted or denied, and the test never waits on a system
permission dialog.

The fixture has zero encoded silence between its tone payloads. The E2E asserts automatic Media3
advance without a second play command, which detects app-inserted transition waits. It does not
capture analog or decoded output, so it is not a numeric acoustic/PCM gap measurement; that remains
a later output-capture device test under PLY-003.

Ordering policy: a complete set of positive, unique reliable track numbers wins. If no such metadata
set is supplied, the unique nonnegative persisted `partOrder` wins. Partial, duplicate, or invalid
purported track metadata is rejected rather than mixed with filename heuristics.
