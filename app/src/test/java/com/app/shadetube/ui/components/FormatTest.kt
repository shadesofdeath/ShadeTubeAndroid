package com.app.shadetube.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("3:48", formatDuration(228_000))
        assertEquals("5:12", formatDuration(312_999))
        assertEquals("1:02:05", formatDuration(3_725_000))
        assertEquals("--:--", formatDuration(-1))
    }
}
