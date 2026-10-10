package com.app.shadetube.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun preMp3FilesGetTheMp3Default() {
        val old = parseJsonOrNull("""{"downloadMp3Kbps":0,"volume":0.5}""")
        assertEquals(320, Settings.fromJson(old).downloadMp3Kbps)
    }

    @Test
    fun originalStreamChosenAfterMp3IsKept() {
        val v2 = parseJsonOrNull("""{"settingsVersion":2,"downloadMp3Kbps":0}""")
        assertEquals(0, Settings.fromJson(v2).downloadMp3Kbps)
    }

    @Test
    fun roundTripWritesTheVersion() {
        val s = Settings(downloadMp3Kbps = 0)
        assertEquals(0, Settings.fromJson(s.toJson()).downloadMp3Kbps)
    }
}
