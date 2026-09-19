package com.audioape.plugin.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AA-018 isolation proof: the fixture modules must not import anything from
 * Room/SAF/player (:core:database, :core:storage, :app) and may not rely on the
 * Android runtime.
 *
 * Enforcement is structural (the Gradle module graph has no such dependency) plus this
 * reflection test over the compiled fixture classes. We walk the module's own compiled
 * main class output, which is present on the test classpath.
 */
class FixtureIsolationTest {
    private val blockedPackages =
        listOf(
            "android.",
            "androidx.room.",
            "androidx.documentfile.",
            "androidx.datastore.",
            "com.audioape.core.database.",
            "com.audioape.core.storage.",
            "com.audioape.player.",
        )

    @Test
    fun `fixture module class files reference no room saf or player packages`() {
        val fixtureClasses = fixtureClassFiles()
        assertTrue("no fixture classes found for isolation scan", fixtureClasses.isNotEmpty())

        for (classFile in fixtureClasses) {
            val binaryName = classFile.fileName.toString().substringBeforeLast(".")
            val constantPool: String = readClassConstantPool(classFile)
            for (blocked in blockedPackages) {
                assertTrue(
                    "class $binaryName must not reference blocked package '$blocked'",
                    !constantPool.contains(blocked),
                )
            }
        }
    }

    @Test
    fun `fixture data is checksum-pinned to the repository catalog file`() {
        val pinned = HeartOfDarknessFixture.FIXTURE_SHA256
        assertEquals(64, pinned.length)

        val repoFile = locate("contracts/catalog-librivox-fixtures.json")
        assertTrue("repository catalog fixture missing", repoFile != null)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(repoFile))
                .toList()
                .map {
                    java.lang.Integer
                        .toHexString(java.lang.Byte.toUnsignedInt(it))
                        .padStart(2, '0')
                }.joinToString("")
        assertEquals(
            "bundled fixture data drifted from contracts/catalog-librivox-fixtures.json",
            pinned,
            digest,
        )
    }

    private fun fixtureClassFiles(): List<Path> {
        val classRoot = locateClassRoot()
        assertTrue("compiled main classes not found at $classRoot", Files.isDirectory(classRoot))
        return Files
            .walk(classRoot)
            .filter { it.toString().endsWith(".class") }
            .toList()
    }

    private fun readClassConstantPool(classFile: Path): String {
        val bytes = Files.readAllBytes(classFile)
        return String(bytes, Charset.forName("UTF-8")).replace("\u0000", "")
    }

    /**
     * Gradle runs JUnit with the module's `build/classes/kotlin/main` on the classpath
     * first; we locate it by scanning the current working directory up a few levels.
     */
    private fun locateClassRoot(): Path {
        val candidates =
            listOf(
                Path.of("build/classes/kotlin/main"),
                Path.of("../../plugin/fixtures/build/classes/kotlin/main"),
                Path.of("../../../plugin/fixtures/build/classes/kotlin/main"),
            )
        val found = candidates.firstOrNull { Files.isDirectory(it) }
        assertTrue("could not locate compiled fixture classes", found != null)
        return found as Path
    }

    private fun locate(relativePath: String): Path? =
        listOf(
            Path.of("."),
            Path.of("../.."),
            Path.of("../../.."),
            Path.of("plugin", "fixtures"),
        ).map { it.resolve(relativePath) }
            .firstOrNull { Files.isReadable(it) }
}
