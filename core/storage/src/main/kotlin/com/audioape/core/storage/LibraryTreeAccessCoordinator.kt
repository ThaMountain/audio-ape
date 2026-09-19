package com.audioape.core.storage

import com.audioape.core.model.ContentReference
import java.io.IOException

/** Coordinates picker results, persisted permission, capability checks, and reconnect checks. */
class LibraryTreeAccessCoordinator(
    private val provider: SafTreeProvider,
    private val grantStore: LibraryTreeGrantStore,
) {
    suspend fun acceptSelection(
        tree: ContentReference?,
        grantedFlags: SafGrantFlags,
    ): LibraryTreeAccessResult {
        if (tree == null) return LibraryTreeAccessResult.Denied

        val descriptor =
            provider.describeTree(tree)
                ?: return LibraryTreeAccessResult.Unavailable(
                    tree = tree,
                    reason = TreeUnavailableReason.INVALID_TREE_URI,
                )
        if (UnsupportedTreeRootPolicy.isUnsupported(descriptor)) {
            return LibraryTreeAccessResult.RootNotSupported(tree)
        }

        if (grantedFlags != SafGrantFlags.NONE) {
            try {
                provider.takePersistablePermission(tree, grantedFlags)
            } catch (_: SecurityException) {
                return missingPermission(tree, SafGrantFlags.NONE)
            } catch (_: RuntimeException) {
                return LibraryTreeAccessResult.Unavailable(
                    tree = tree,
                    reason = TreeUnavailableReason.PROVIDER_UNAVAILABLE,
                )
            }
        }
        if (!grantedFlags.providesLibraryAccess) return missingPermission(tree, grantedFlags)

        return verifyAndStore(tree, grantedFlags)
    }

    /** Reconnect/status path. The stored reference is retained when its OS grant was revoked. */
    suspend fun verifyStoredGrant(): LibraryTreeAccessResult {
        val stored =
            try {
                grantStore.read()
            } catch (_: IOException) {
                return LibraryTreeAccessResult.Unavailable(
                    tree = null,
                    reason = TreeUnavailableReason.GRANT_STORE_FAILURE,
                )
            } ?: return LibraryTreeAccessResult.PermissionMissing(
                tree = null,
                readMissing = true,
                writeMissing = true,
            )

        val descriptor =
            provider.describeTree(stored.tree)
                ?: return LibraryTreeAccessResult.Unavailable(
                    tree = stored.tree,
                    reason = TreeUnavailableReason.INVALID_TREE_URI,
                )
        if (UnsupportedTreeRootPolicy.isUnsupported(descriptor)) {
            return LibraryTreeAccessResult.RootNotSupported(stored.tree)
        }

        val persisted =
            try {
                provider.persistedPermission(stored.tree)
            } catch (_: RuntimeException) {
                null
            }
        if (persisted == null || !persisted.providesLibraryAccess) {
            return missingPermission(stored.tree, persisted ?: SafGrantFlags.NONE)
        }
        return verifyCapabilities(stored.tree, persisted)
    }

    private suspend fun verifyAndStore(
        tree: ContentReference,
        grantedFlags: SafGrantFlags,
    ): LibraryTreeAccessResult {
        val persisted =
            try {
                provider.persistedPermission(tree)
            } catch (_: RuntimeException) {
                null
            }
        if (persisted == null || !persisted.providesLibraryAccess) {
            return missingPermission(tree, persisted ?: SafGrantFlags.NONE)
        }

        val verified = verifyCapabilities(tree, persisted)
        if (verified !is LibraryTreeAccessResult.Granted) return verified

        val grant = StoredLibraryTreeGrant(tree = tree, flags = grantedFlags)
        return try {
            grantStore.write(grant)
            LibraryTreeAccessResult.Granted(grant)
        } catch (_: IOException) {
            LibraryTreeAccessResult.Unavailable(
                tree = tree,
                reason = TreeUnavailableReason.GRANT_STORE_FAILURE,
            )
        }
    }

    private fun verifyCapabilities(
        tree: ContentReference,
        flags: SafGrantFlags,
    ): LibraryTreeAccessResult {
        val capabilities =
            try {
                provider.inspectTree(tree)
            } catch (_: RuntimeException) {
                return LibraryTreeAccessResult.Unavailable(
                    tree = tree,
                    reason = TreeUnavailableReason.PROVIDER_UNAVAILABLE,
                )
            }

        val unavailableReason =
            when {
                !capabilities.exists -> {
                    TreeUnavailableReason.NOT_FOUND
                }

                !capabilities.isDirectory -> {
                    TreeUnavailableReason.NOT_A_DIRECTORY
                }

                !flags.read || !capabilities.readable -> {
                    TreeUnavailableReason.NOT_READABLE
                }

                !flags.write || !capabilities.writable -> {
                    TreeUnavailableReason.NOT_WRITABLE
                }

                !capabilities.supportsChildCreation -> {
                    TreeUnavailableReason.CHILD_CREATION_UNSUPPORTED
                }

                else -> {
                    null
                }
            }
        return if (unavailableReason == null) {
            LibraryTreeAccessResult.Granted(StoredLibraryTreeGrant(tree, flags))
        } else {
            LibraryTreeAccessResult.Unavailable(tree, unavailableReason)
        }
    }

    private fun missingPermission(
        tree: ContentReference,
        flags: SafGrantFlags,
    ) = LibraryTreeAccessResult.PermissionMissing(
        tree = tree,
        readMissing = !flags.read,
        writeMissing = !flags.write,
    )
}
