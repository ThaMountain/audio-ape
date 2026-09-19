package com.audioape.plugin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResultTest {
    @Test
    fun `success envelope carries the value and no rejection`() {
        val value = listOf("a", "b")
        val result = pluginSuccess(value)
        assertTrue(result.isSuccess)
        assertFalse(result.isRejected)
        assertSame(value, result.value)
        assertNull(result.rejection)
    }

    @Test
    fun `rejection envelope carries a typed error and no value`() {
        val result = pluginRejected<String>(PluginErrorCode.NOT_FOUND, "nothing here")
        assertTrue(result.isRejected)
        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(PluginErrorCode.NOT_FOUND, result.rejection?.code)
        assertEquals("nothing here", result.rejection?.detail)
    }

    @Test
    fun `envelope invariants are enforced at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginResult(value = "x", rejection = PluginRejection(PluginErrorCode.NOT_FOUND))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginResult(value = null, rejection = null)
        }
    }

    @Test
    fun `rejections are immutable and detail may be omitted`() {
        val rejection = PluginRejection(PluginErrorCode.CAPABILITY_MISMATCH)
        assertEquals(PluginErrorCode.CAPABILITY_MISMATCH, rejection.code)
        assertNull(rejection.detail)
        assertEquals(rejection, rejection.copy())

        assertThrows(IllegalArgumentException::class.java) {
            PluginRejection(PluginErrorCode.FORBIDDEN, " ")
        }
    }

    @Test
    fun `capability mismatch and not found helpers produce typed rejections`() {
        val mismatch = pluginRejected<String>(PluginErrorCode.CAPABILITY_MISMATCH)
        assertEquals(PluginErrorCode.CAPABILITY_MISMATCH, mismatch.rejection?.code)

        val missing = pluginRejected<Int>(PluginErrorCode.NOT_FOUND)
        assertEquals(PluginErrorCode.NOT_FOUND, missing.rejection?.code)
    }
}
