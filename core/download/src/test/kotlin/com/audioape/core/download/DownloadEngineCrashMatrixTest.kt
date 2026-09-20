package com.audioape.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AA-021 crash matrix: inject process-death at EVERY named download transition, then run a
 * FRESH engine. Acceptance: recovery converges to exactly one safe state — fully committed
 * (managed files hash-verified, ONE Room record, marker present, zero staging residue) — and
 * NEVER loses a staged part or duplicates a library entry.
 */
class DownloadEngineCrashMatrixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyCrashCheckpointConvergesToFullyCommittedState() {
        for (checkpoint in DownloadCheckpoint.entries) {
            val root = tmp.newFolder("cp-${checkpoint.name}")
            val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src-${checkpoint.name}"))
            val request =
                ResolvedDownloadRequest(
                    fixture.manifest.bookId,
                    fixture.manifest.displayTitle,
                    DownloadSource { fixture.zipFile.inputStream() },
                    fixture.archiveSizeBytes,
                    fixture.archiveSha256Hex,
                )
            val committer = MemoryCommitter()

            val crashing = DownloadEngine(root, committer)
            var fired = false
            crashing.checkpointHook = { point ->
                if (point == checkpoint && !fired) {
                    fired = true
                    throw SimulatedCrash()
                }
            }
            try {
                crashing.run(request)
                fail("expected SimulatedCrash at $checkpoint")
            } catch (expected: SimulatedCrash) {
                // process died; nothing cleaned up by the dead process
            }
            assertTrue("crash must actually have fired at $checkpoint", fired)

            val recovery = DownloadEngine(root, committer)
            val report = recovery.run(request)

            assertTrue("[$checkpoint] recovery must succeed: $report", report.succeeded)
            assertTrue(
                "[$checkpoint] exactly ONE Room record across both runs",
                committer.committed.size == 1,
            )
            val managedDir = File(root, "${fixture.manifest.bookId}/managed")
            fixture.partContents.forEach { (name, bytes) ->
                val f = File(managedDir, name)
                assertTrue("[$checkpoint] $name must exist in the managed tree", f.isFile)
                assertTrue("[$checkpoint] $name must hash-match", DownloadHashes.fileMatchesSha256(f, DownloadHashes.sha256(bytes)))
                assertEquals("[$checkpoint] $name byte count", bytes.size.toLong(), f.length())
            }
            assertTrue(
                "[$checkpoint] completion marker must exist",
                File(root, "${fixture.manifest.bookId}/download-v1.complete").isFile,
            )
            val staging = File(root, "${fixture.manifest.bookId}/staging")
            assertTrue("[$checkpoint] staging must be empty", !staging.exists() || staging.listFiles().orEmpty().isEmpty())
            assertEquals(
                "[$checkpoint] committed parts in the record",
                fixture.partContents.size,
                committer.committed[0].second.size,
            )
        }
    }

    @Test
    fun markerWithStrayStagingIsInconsistentStateNotSilentContinue() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val engine = DownloadEngine(root, committer)
        assertTrue(engine.run(request).succeeded)

        // Reintroduce staging residue after completion.
        val staging = File(root, "${fixture.manifest.bookId}/staging")
        staging.mkdirs()
        File(staging, "leftover.bin").writeBytes(ByteArray(4) { 1 })
        val rerun = DownloadEngine(root, committer).run(request)
        assertFalse("marker + stray staging must fail", rerun.succeeded)
        assertTrue(rerun.engineFailure is IllegalStateException)
    }

    @Test
    fun markerWithoutRecordIsInconsistentState() {
        val root = tmp.newFolder("root")
        val fixture = DownloadFixtures.syntheticBook(tmp.newFolder("src"))
        val committer = MemoryCommitter()
        val request =
            ResolvedDownloadRequest(
                fixture.manifest.bookId,
                fixture.manifest.displayTitle,
                DownloadSource { fixture.zipFile.inputStream() },
                fixture.archiveSizeBytes,
                fixture.archiveSha256Hex,
            )
        val engine = DownloadEngine(root, committer)
        assertTrue(engine.run(request).succeeded)

        // Wipe the record (simulated) while the marker remains: must NOT silently continue.
        committer.committed.clear()
        val rerun = DownloadEngine(root, committer).run(request)
        assertFalse("marker without record must fail", rerun.succeeded)
        assertTrue(rerun.engineFailure is IllegalStateException)
    }
}
