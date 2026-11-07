package com.novcloud.echo.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.EchoApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoverStylePrefsTest {
    private val prefs get() = (ApplicationProvider.getApplicationContext<android.content.Context>() as EchoApp).container.prefs

    @Test
    fun savedStyleReadsBackAndTravelsWithTheBackup() = runBlocking {
        prefs.setCoverStyle(CoverStyle.Vinyl)
        assertEquals(CoverStyle.Vinyl, prefs.coverStyle.first())
        val saved = prefs.backupSettings()
        assertEquals("Vinyl", saved.coverStyle)

        prefs.setCoverStyle(CoverStyle.Film)
        prefs.restoreSettings(saved) { true }
        assertEquals(CoverStyle.Vinyl, prefs.coverStyle.first())

        prefs.restoreSettings(saved.copy(coverStyle = "Cassette")) { true }
        assertEquals(CoverStyle.Classic, prefs.coverStyle.first())

        prefs.setCoverStyle(CoverStyle.Jewel)
        prefs.restoreSettings(saved.copy(coverStyle = null)) { true }
        assertEquals(CoverStyle.Classic, prefs.coverStyle.first())
        assertEquals(null, prefs.backupSettings().coverStyle)
    }
}
