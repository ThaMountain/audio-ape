package com.audioape.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellVersionTest {
    @Test
    fun formatsVersionLabel() {
        assertEquals("Version 0.1.0", ShellVersion.label("0.1.0"))
    }
}
