package com.audioape.plugin.protocol

import java.net.URI

/**
 * One audio edition a plugin offers, normalized to the host boundary.
 *
 * Mirrors the catalog adapter normalized return in
 * `docs/ARCHITECTURE_AND_CONTRACTS.md` §E plus the proven-audio requirements of
 * `docs/decisions/0002-catalog-provider-librivox.md`. Unknown optional fields stay
 * `null`; nothing is synthesized. `confirmedAudioEvidence` must be non-empty:
 * a plugin may never present an edition it cannot prove is real audio.
 */
data class CatalogItem(
    val workAliases: List<String>,
    val editionAliases: List<String>,
    val author: String,
    val narrator: String?,
    val language: String?,
    val audioPublicationDate: String?,
    val description: String?,
    val coverUrl: String?,
    val provenance: String,
    val confirmedAudioEvidence: String,
) {
    init {
        require(workAliases.isNotEmpty()) { "a catalog item needs at least one work alias" }
        require(editionAliases.isNotEmpty()) { "a catalog item needs at least one edition alias" }
        requireNormalizedText(author, "catalog author")
        requireOptionalNormalizedText(narrator, "catalog narrator")
        requireOptionalNormalizedText(language, "catalog language")
        requireOptionalNormalizedText(audioPublicationDate, "catalog audio publication date")
        requireOptionalNormalizedText(description, "catalog description")
        requireOptionalUrl(coverUrl, "catalog cover URL")
        requireNormalizedText(provenance, "catalog provenance")
        requireNormalizedText(confirmedAudioEvidence, "confirmed audio evidence")
        require(confirmedAudioEvidence.isNotBlank()) {
            "a catalog item requires non-empty confirmed audio evidence"
        }
        require(workAliases.map { it.trim() }.distinct().size == workAliases.size) {
            "work aliases must be unique"
        }
        require(editionAliases.map { it.trim() }.distinct().size == editionAliases.size) {
            "edition aliases must be unique"
        }
    }
}

/**
 * One typed factual claim the host may fill for an edition's metadata screen.
 *
 * The value is a plain string or URI — never HTML, markup, or a selector. Unknown typed
 * fields on the source stay absent; the host renders only what it receives.
 */
data class EditionMetadataField(
    val field: EditionMetadataFieldType,
    val value: String,
) {
    init {
        requireNormalizedText(value, "edition metadata value")
    }
}

/**
 * The fixed typed set of fields a plugin may enrich for one exact edition
 * (capability table §2.2).
 */
enum class EditionMetadataFieldType {
    TITLE,
    AUTHOR,
    NARRATOR,
    LANGUAGE,
    PUBLICATION_DATE,
    DESCRIPTION,
    SERIES,
    COVER_URL,
}

/**
 * Typed augmentation for one exact edition id, requested by one typed field set.
 * Nothing beyond the requested fields is ever filled.
 */
data class EditionMetadata(
    val backendEditionId: String,
    val typedFields: List<EditionMetadataField>,
    val requestedBy: EditionMetadataFieldType,
    val reviewed: Boolean = false,
) {
    init {
        requireNonBlank(backendEditionId, "backend edition id")
        require(typedFields.distinctBy { it.field }.size == typedFields.size) {
            "typed fields must not repeat a field type"
        }
        require(typedFields.any { it.field == requestedBy }) {
            "edition metadata must include the requested field"
        }
    }
}
