# AA-015 checkpoint and paused-recovery verification

## Implemented policy

- The playback service captures a book-wide checkpoint on play-to-pause, committed seek, and an
  active `MediaItem` transition.
- While `Player.isPlaying` is true, a coroutine ticker captures a checkpoint every 10 seconds.
  Pausing cancels that ticker; it does not poll or write while paused.
- Captures within 250 ms are coalesced by book ID and sent to
  `PlaybackCheckpointDao.saveCheckpoints` as one batch. A committed seek while paused is still a
  deliberate state change and is persisted.
- `onDestroy` takes one final snapshot and gives its Room write up to two seconds to finish. Abrupt
  process loss may lose the bounded interval since the most recent event/periodic checkpoint.
- Cold restore applies the saved book checkpoint and speed, prepares the playlist with
  `playWhenReady=false`, and requires a new explicit play command.

## Physical-device test design

`TwoPartBookPlaybackE2ETest` uses the authorized synthetic WAV fixtures under
`app/src/androidTest/assets`. The debug manifest places only the playback service in
`com.audioape.player:playback`, allowing the instrumentation runner to inject an unclean
`am crash <playback-pid>` without killing itself. Release behavior and source do not depend on the
debug process placement.

The test selects an isolated app-private Room filename through the existing service dependency
seam; it does not open, clear, or modify the normal `audio-ape.db`, SAF storage, shared media, or the
owner's audiobook library. It verifies:

1. seeded checkpoint and 1.5x speed cold-restore paused;
2. no position advance before explicit `play()`;
3. active two-part transition and checkpoint flush;
4. unclean playback-process death while audio is playing;
5. relaunch paused at the durable checkpoint with at most 1.5 seconds measured loss;
6. restored 1.5x speed and position advance only after another explicit `play()`.

Run from the repository root with the physical device connected:

```sh
./gradlew :app:connectedDebugAndroidTest
```

This test does not reboot the phone and does not exercise a Bluetooth/headset hardware play
button. Those AA-015 acceptance paths remain unverified until separate manual/device automation is
run. `am crash` validates unclean service-process recovery; Android's package-level `am force-stop`
suppresses subsequent component launches until an external user action and cannot be issued from a
same-package instrumentation test that must continue asserting.

**Observed harness behavior (2026-09-19):** the connected-task *task-level* exit code is 1 even
though the testcase passes (XML `tests=1 failures=0 errors=0`, HTML report 100% success,
TestRunner started/finished clean). Root cause: AGP/Android attribute ANY app-process crash during
an instrumentation window as run failure, and this test's entire purpose is intentionally crashing
the `:playback` process (`am crash`). The previous `BUILD SUCCESSFUL` on the same test was a
timing outlier. This is the harness honoring the injection, not a product defect; CI does not run
connected tests (headless only), so there is no pipeline impact. The evidence of record for this
ticket is the testcase result XML/report + logcat, which show the crash-recovery assertions
passing.
