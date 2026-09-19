package com.audioape.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportConsentTest {
    @Test
    fun `empty first run asks before import`() {
        assertTrue(ImportConsentState(importedBookCount = 0).shouldAskBeforeImport())
    }

    @Test
    fun `accepting prompt opens initial import picker and does not ask again`() {
        val confirmed =
            ImportConsentState(importedBookCount = 0)
                .confirm(InitialImportChoice.IMPORT_EXISTING_MEDIA)

        assertFalse(confirmed.updatedState.shouldAskBeforeImport())
        assertEquals(
            ImportConsentNextStep.OPEN_TRANSIENT_SOURCE_PICKER,
            confirmed.nextStep,
        )
    }

    @Test
    fun `not now does not repeat prompt while manual import remains a separate UI action`() {
        val declined =
            ImportConsentState(importedBookCount = 0)
                .confirm(InitialImportChoice.NOT_NOW)

        assertFalse(declined.updatedState.shouldAskBeforeImport())
        assertEquals(ImportConsentNextStep.STAY_IN_LIBRARY, declined.nextStep)
    }

    @Test
    fun `existing imported book suppresses unanswered initial prompt`() {
        assertFalse(ImportConsentState(importedBookCount = 1).shouldAskBeforeImport())
    }
}
