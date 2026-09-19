package com.audioape.core.storage

import com.audioape.core.model.ContentReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryTreeAccessCoordinatorTest {
    private val tree =
        ContentReference(
            "content://com.android.externalstorage.documents/" +
                "tree/primary%3AAudiobooks%2FAudio%20Ape",
        )

    @Test
    fun `successful selection persists exact read write grant`() =
        runBlocking {
            val provider = FakeSafTreeProvider()
            val store = InMemoryGrantStore()
            val coordinator = LibraryTreeAccessCoordinator(provider, store)

            val result = coordinator.acceptSelection(tree, SafGrantFlags.READ_WRITE)

            assertEquals(SafGrantFlags.READ_WRITE, provider.lastTakenFlags)
            assertEquals(
                LibraryTreeAccessResult.Granted(
                    StoredLibraryTreeGrant(tree, SafGrantFlags.READ_WRITE),
                ),
                result,
            )
            assertEquals(StoredLibraryTreeGrant(tree, SafGrantFlags.READ_WRITE), store.value)
        }

    @Test
    fun `only flags actually granted are passed to takePersistableUriPermission`() =
        runBlocking {
            val provider = FakeSafTreeProvider()
            val store = InMemoryGrantStore()
            val coordinator = LibraryTreeAccessCoordinator(provider, store)
            val readOnly = SafGrantFlags(read = true, write = false)

            val result = coordinator.acceptSelection(tree, readOnly)

            assertEquals(readOnly, provider.lastTakenFlags)
            assertEquals(
                LibraryTreeAccessResult.PermissionMissing(
                    tree = tree,
                    readMissing = false,
                    writeMissing = true,
                ),
                result,
            )
            assertNull(store.value)
        }

    @Test
    fun `cancelled picker has clear denial result and does not touch provider`() =
        runBlocking {
            val provider = FakeSafTreeProvider()
            val coordinator = LibraryTreeAccessCoordinator(provider, InMemoryGrantStore())

            val result = coordinator.acceptSelection(null, SafGrantFlags.NONE)

            assertSame(LibraryTreeAccessResult.Denied, result)
            assertNull(provider.lastTakenFlags)
        }

    @Test
    fun `provider refusal reports missing persisted permission`() =
        runBlocking {
            val provider = FakeSafTreeProvider().apply { refusePermission = true }

            val result =
                LibraryTreeAccessCoordinator(provider, InMemoryGrantStore())
                    .acceptSelection(tree, SafGrantFlags.READ_WRITE)

            assertEquals(
                LibraryTreeAccessResult.PermissionMissing(
                    tree = tree,
                    readMissing = true,
                    writeMissing = true,
                ),
                result,
            )
        }

    @Test
    fun `storage and downloads roots are rejected before permission persistence`() =
        runBlocking {
            val unsupportedDescriptors =
                listOf(
                    TreeDescriptor("com.android.externalstorage.documents", "primary:"),
                    TreeDescriptor("com.android.externalstorage.documents", "primary:Download"),
                    TreeDescriptor("com.android.providers.downloads.documents", "downloads"),
                )

            unsupportedDescriptors.forEach { descriptor ->
                val provider = FakeSafTreeProvider(descriptor = descriptor)
                val result =
                    LibraryTreeAccessCoordinator(provider, InMemoryGrantStore())
                        .acceptSelection(tree, SafGrantFlags.READ_WRITE)

                assertEquals(LibraryTreeAccessResult.RootNotSupported(tree), result)
                assertNull(provider.lastTakenFlags)
            }
        }

    @Test
    fun `child below Downloads is accepted by root policy`() =
        runBlocking {
            val provider =
                FakeSafTreeProvider(
                    descriptor =
                        TreeDescriptor(
                            "com.android.externalstorage.documents",
                            "primary:Download/Audio Ape",
                        ),
                )

            val result =
                LibraryTreeAccessCoordinator(provider, InMemoryGrantStore())
                    .acceptSelection(tree, SafGrantFlags.READ_WRITE)

            assertEquals(
                LibraryTreeAccessResult.Granted(
                    StoredLibraryTreeGrant(tree, SafGrantFlags.READ_WRITE),
                ),
                result,
            )
        }

    @Test
    fun `revoked persisted permission produces reconnectable permission missing state`() =
        runBlocking {
            val provider = FakeSafTreeProvider()
            val store = InMemoryGrantStore()
            val coordinator = LibraryTreeAccessCoordinator(provider, store)
            coordinator.acceptSelection(tree, SafGrantFlags.READ_WRITE)
            provider.persistedFlags = null

            val result = coordinator.verifyStoredGrant()

            assertEquals(
                LibraryTreeAccessResult.PermissionMissing(
                    tree = tree,
                    readMissing = true,
                    writeMissing = true,
                ),
                result,
            )
            assertEquals(tree, store.value?.tree)
        }

    @Test
    fun `read-only provider reports clear unavailable reason`() =
        runBlocking {
            val provider =
                FakeSafTreeProvider(
                    capabilities =
                        validCapabilities.copy(
                            writable = false,
                            supportsChildCreation = false,
                        ),
                )

            val result =
                LibraryTreeAccessCoordinator(provider, InMemoryGrantStore())
                    .acceptSelection(tree, SafGrantFlags.READ_WRITE)

            assertEquals(
                LibraryTreeAccessResult.Unavailable(
                    tree,
                    TreeUnavailableReason.NOT_WRITABLE,
                ),
                result,
            )
        }

    @Test
    fun `unqueryable provider reports unavailable and is not stored`() =
        runBlocking {
            val provider = FakeSafTreeProvider().apply { failInspection = true }
            val store = InMemoryGrantStore()

            val result =
                LibraryTreeAccessCoordinator(provider, store)
                    .acceptSelection(tree, SafGrantFlags.READ_WRITE)

            assertEquals(
                LibraryTreeAccessResult.Unavailable(
                    tree,
                    TreeUnavailableReason.PROVIDER_UNAVAILABLE,
                ),
                result,
            )
            assertNull(store.value)
        }

    private class InMemoryGrantStore : LibraryTreeGrantStore {
        var value: StoredLibraryTreeGrant? = null

        override suspend fun read(): StoredLibraryTreeGrant? = value

        override suspend fun write(grant: StoredLibraryTreeGrant) {
            value = grant
        }
    }

    private class FakeSafTreeProvider(
        private val descriptor: TreeDescriptor =
            TreeDescriptor(
                "com.android.externalstorage.documents",
                "primary:Audiobooks/Audio Ape",
            ),
        private val capabilities: TreeCapabilities = validCapabilities,
    ) : SafTreeProvider {
        var lastTakenFlags: SafGrantFlags? = null
        var persistedFlags: SafGrantFlags? = null
        var failInspection = false
        var refusePermission = false

        override fun describeTree(tree: ContentReference): TreeDescriptor = descriptor

        override fun takePersistablePermission(
            tree: ContentReference,
            flags: SafGrantFlags,
        ) {
            if (refusePermission) throw SecurityException("provider refused permission")
            lastTakenFlags = flags
            persistedFlags = flags
        }

        override fun persistedPermission(tree: ContentReference): SafGrantFlags? = persistedFlags

        override fun inspectTree(tree: ContentReference): TreeCapabilities {
            if (failInspection) error("provider query failed")
            return capabilities
        }
    }

    private companion object {
        val validCapabilities =
            TreeCapabilities(
                exists = true,
                isDirectory = true,
                readable = true,
                writable = true,
                supportsChildCreation = true,
            )
    }
}
