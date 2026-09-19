package com.audioape.core.storage

/** The explicit answer to the one-time initial import question. */
enum class InitialImportChoice {
    IMPORT_EXISTING_MEDIA,
    NOT_NOW,
}

/**
 * UI-facing state for the initial import prompt.
 *
 * Persistence belongs to the future workflow owner. Keeping the answer separate from the imported
 * book count prevents an empty library from showing this prompt on every launch after "Not now".
 */
data class ImportConsentState(
    val importedBookCount: Int,
    val initialPromptAnswered: Boolean = false,
) {
    init {
        require(importedBookCount >= 0) { "imported book count must be nonnegative" }
    }

    fun shouldAskBeforeImport(): Boolean = importedBookCount == 0 && !initialPromptAnswered

    fun confirm(choice: InitialImportChoice): ImportConsentConfirmation =
        ImportConsentConfirmation(
            updatedState = copy(initialPromptAnswered = true),
            nextStep =
                when (choice) {
                    InitialImportChoice.IMPORT_EXISTING_MEDIA -> {
                        ImportConsentNextStep.OPEN_TRANSIENT_SOURCE_PICKER
                    }

                    InitialImportChoice.NOT_NOW -> {
                        ImportConsentNextStep.STAY_IN_LIBRARY
                    }
                },
        )
}

data class ImportConsentConfirmation(
    val updatedState: ImportConsentState,
    val nextStep: ImportConsentNextStep,
)

enum class ImportConsentNextStep {
    OPEN_TRANSIENT_SOURCE_PICKER,
    STAY_IN_LIBRARY,
}
