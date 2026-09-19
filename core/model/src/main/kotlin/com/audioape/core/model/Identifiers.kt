package com.audioape.core.model

import java.util.UUID

/** Internal canonical identifier for a written work. */
data class WorkId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "work id")
    }
}

/** Internal canonical identifier for one audio rendition of a work. */
data class EditionId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "edition id")
    }
}

/** Internal canonical identifier for one locally owned logical book. */
data class BookId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "book id")
    }
}

/** Internal canonical identifier for one playable media part. */
data class MediaPartId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "media part id")
    }
}

/** Internal canonical identifier for one chapter. */
data class ChapterId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "chapter id")
    }
}

/** Internal canonical identifier for one bookmark. */
data class BookmarkId(
    val value: String,
) {
    init {
        requireCanonicalUuid(value, "bookmark id")
    }
}

private fun requireCanonicalUuid(
    value: String,
    label: String,
) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) {
        "$label must be a canonical lowercase UUID string"
    }
}
