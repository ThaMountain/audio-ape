package com.audioape.player.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audioape.core.database.AudioApeDatabase
import com.audioape.core.database.RoomDownloadCommitter
import com.audioape.core.download.DownloadEngine
import com.audioape.core.download.DownloadHashes
import com.audioape.core.download.DownloadManifest
import com.audioape.core.download.DownloadManifestCodec
import com.audioape.core.download.DownloadSource
import com.audioape.core.download.PartDescriptor
import com.audioape.core.download.ResolvedDownloadRequest
import com.audioape.core.model.BookId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * AA-021 END-TO-END (emulator-connected): a real loopback HTTP fetch of a synthetic authorized
 * two-part archive → private staging → size/hash verification → safe extraction → managed tree →
 * REAL Room record, plus idempotency and corrupted-journal recovery preserving every row.
 *
 * The transport exercised here is genuine HTTP over localhost; the media is synthetic playable
 * WAV (PCM), authorized fixture data — no real credentials, no torrent sources.
 */
class DownloadVerticalSliceE2ETest {
    private lateinit var context: Context
    private lateinit var database: AudioApeDatabase
    private lateinit var server: MiniHttpServer
    private lateinit var downloadsRoot: File
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AudioApeDatabase.create(context, "download-slice-e2e-${UUID.randomUUID()}")
        downloadsRoot = File(context.filesDir, "downloads-e2e").apply { deleteRecursively() }
        fixture = syntheticTwoPartZip()
        server = MiniHttpServer(fixture.zip)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        database.close()
        downloadsRoot.deleteRecursively()
    }

    @Test
    fun twoPartDownloadCommitsRoomRecordAndRecoversWithoutDuplicates() {
        val committer = RoomDownloadCommitter(database)
        val engine = DownloadEngine(downloadsRoot, committer)
        val bookId = fixture.manifest.bookId
        val request = request(bookId)

        // ---- Run 1: full download over loopback HTTP -----------------------------------------
        val report = engine.run(request)
        assertTrue("run 1 must succeed: $report", report.succeeded)
        assertTrue("archive must be verified", report.archiveVerified)
        assertEquals(2, report.partsCommitted)

        val managedDir = File(downloadsRoot, "$bookId/managed")
        fixture.parts.forEach { (name, bytes) ->
            val f = File(managedDir, name)
            assertTrue("$name must exist in the managed tree", f.isFile)
            assertTrue("$name must hash-match the served bytes", DownloadHashes.fileMatchesSha256(f, DownloadHashes.sha256(bytes)))
        }
        val bookRow = runBlockingQuery { database.libraryBookDao().book(BookId(bookId)) }
        assertTrue("library_book row must exist", bookRow != null)
        val partRows = runBlockingQuery { database.mediaPartDao().parts(BookId(bookId)) }
        assertEquals("exactly two media_part rows", 2, partRows.size)
        assertEquals("imported book is not user-owned", false, bookRow!!.userOwned)

        // ---- Run 2: idempotent no-op -----------------------------------------------------------
        val second = engine.run(request)
        assertTrue("run 2 must be a verified no-op: $second", second.succeeded)
        assertTrue(second.alreadyComplete)
        assertEquals("no new book rows", 1, countBooks())
        assertEquals("no new part rows", 2, countParts(bookId))
        // Content URIs point at the managed files.
        assertTrue(partRows.all { it.contentUri.startsWith("file:") })

        // ---- Run 3: corrupt the journal + lose the marker, then recover -------------------------
        val bookDir = File(downloadsRoot, bookId)
        File(bookDir, "download.journal").writeText("book|\npart\npart|part01.bin|EXTRACTED\nbook|RECORDS_COMMITTED")
        assertTrue(File(bookDir, "download-v1.complete").delete())

        val third = engine.run(request)
        assertTrue("run 3 (torn journal) must converge: $third", third.succeeded)
        assertEquals("file count unchanged", 2, managedDir.listFiles().orEmpty().size)
        fixture.parts.forEach { (name, bytes) ->
            assertTrue("$name bytes unchanged", DownloadHashes.fileMatchesSha256(File(managedDir, name), DownloadHashes.sha256(bytes)))
        }
        assertEquals("still exactly one book row", 1, countBooks())
        assertEquals("still exactly two part rows", 2, countParts(bookId))
        assertTrue("marker recreated", File(bookDir, "download-v1.complete").isFile)

        // ---- Run 4: completed download survives a garbage journal -------------------------------
        File(bookDir, "download-v1.complete").delete()
        File(bookDir, "download.journal").writeText("@@@ not a journal at all @@@")
        val fourth = engine.run(request)
        assertTrue("garbage journal must be refused", !fourth.succeeded)
        assertEquals("book rows untouched by the refusal", 1, countBooks())
        assertEquals("part rows untouched by the refusal", 2, countParts(bookId))

        // ---- Run 5: marker present but MEDIA tampered -> explicit failure, bytes preserved -------
        val tamperedBytes = "on-device-tamper".toByteArray()
        val managed00 = File(managedDir, "part00.wav")
        Files.write(managed00.toPath(), tamperedBytes)
        val fifth = engine.run(request)
        assertTrue("marker + mismatched media must fail, not no-op", !fifth.succeeded)
        assertTrue(
            "tampered bytes must remain untouched",
            tamperedBytes.contentEquals(managed00.readBytes()),
        )
        assertEquals("still exactly one book row", 1, countBooks())
        assertEquals("still exactly two part rows", 2, countParts(bookId))
    }

    private fun request(bookId: String): ResolvedDownloadRequest =
        ResolvedDownloadRequest(
            bookId = bookId,
            displayTitle = fixture.manifest.displayTitle,
            source = DownloadSource { fetch(server.url()) },
            expectedArchiveSizeBytes = fixture.zip.length(),
            expectedArchiveSha256Hex = DownloadHashes.sha256(fixture.zip.inputStream()),
            expectedParts = fixture.manifest.parts,
        )

    private fun fetch(url: String): java.io.InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        if (code != 200) throw java.io.IOException("loopback server returned HTTP $code")
        return connection.inputStream
    }

    private fun <T> runBlockingQuery(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    private fun countBooks(): Int =
        runBlockingQuery { database.libraryBookDao().book(BookId(fixture.manifest.bookId)) != null }.let { if (it) 1 else 0 }

    private fun countParts(bookId: String): Int = runBlockingQuery { database.mediaPartDao().parts(BookId(bookId)).size }

    private fun syntheticTwoPartZip(): Fixture {
        val partA = wavPcm(44100, 1.0, 5)
        val partB = wavPcm(44100, 1.0, 6)
        val manifest =
            DownloadManifest(
                bookId = UUID.randomUUID().toString(),
                displayTitle = "AA-021 Synthetic Two-Part Download",
                parts =
                    listOf(
                        PartDescriptor(0, "part00.wav", partA.size.toLong(), DownloadHashes.sha256(partA)),
                        PartDescriptor(1, "part01.wav", partB.size.toLong(), DownloadHashes.sha256(partB)),
                    ),
            )
        val zip = File(context.cacheDir, "e2e-${manifest.bookId}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.mf"))
            zos.write(DownloadManifestCodec.encode(manifest).toByteArray())
            zos.closeEntry()
            listOf("part00.wav" to partA, "part01.wav" to partB).forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return Fixture(zip, manifest, listOf("part00.wav" to partA, "part01.wav" to partB))
    }

    /** Minimal playable 16-bit PCM WAV (44-byte header + samples). */
    private fun wavPcm(
        sampleRate: Int,
        seconds: Double,
        seed: Int,
    ): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        val samples = ByteArray(frames * 2)
        var phase = 0
        var n = 0
        while (n < frames) {
            val v = ((seed * 13 + n * 7) % 20000 - 10000).toShort()
            samples[phase++] = (v.toInt() and 0xFF).toByte()
            samples[phase++] = ((v.toInt() shr 8) and 0xFF).toByte()
            n++
        }
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        putInt32(header, 4, 36 + samples.size)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        putInt32(header, 16, 16)
        putInt16(header, 20, 1)
        putInt16(header, 22, 1)
        putInt32(header, 24, sampleRate)
        putInt32(header, 28, sampleRate * 2)
        putInt16(header, 32, 2)
        putInt16(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        putInt32(header, 40, samples.size)
        return header + samples
    }

    private fun putInt16(
        out: ByteArray,
        offset: Int,
        value: Int,
    ) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putInt32(
        out: ByteArray,
        offset: Int,
        value: Int,
    ) {
        putInt16(out, offset, value and 0xFFFF)
        putInt16(out, offset + 2, value shr 16)
    }

    private data class Fixture(
        val zip: File,
        val manifest: DownloadManifest,
        val parts: List<Pair<String, ByteArray>>,
    )
}

/**
 * Minimal single-file loopback HTTP server (GET /book.zip, Content-Length, Connection close).
 * Deliberately dependency-free: works on the emulator with only [ServerSocket].
 */
private class MiniHttpServer(
    private val payload: File,
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        serverSocket = socket
        executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "e2e-http").apply { isDaemon = true }
            }
        executor!!.execute {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.use { connection ->
                        val requestLine = connection.getInputStream().bufferedReader().readLine()
                        val bytes = payload.readBytes()
                        val response =
                            buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/octet-stream\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Connection: close\r\n")
                                append("\r\n")
                            }
                        connection.getOutputStream().apply {
                            write(response.toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // server shutdown
            }
        }
    }

    fun url(): String = "http://127.0.0.1:${serverSocket!!.localPort}/book.zip"

    fun stop() {
        serverSocket?.close()
        executor?.shutdownNow()
    }
}
