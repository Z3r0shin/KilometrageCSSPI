package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {

    @Test
    fun aHigherVersionIsNewer() {
        assertTrue(Updater.isNewer("1.4", "1.3.1"))
        assertTrue(Updater.isNewer("v1.3.1", "1.3"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
    }

    @Test
    fun numbersCompareAsNumbersNotText() {
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertFalse(Updater.isNewer("1.9", "1.10"))
    }

    @Test
    fun releaseNotesArePlainTextWithoutTheManualInstallSteps() {
        val body = "## Nouveau\r\n\r\n- **Signature** sur le formulaire\r\n- La `date` aussi\r\n\r\n" +
            "**Installer :** télécharger `Kilometrage-CSSPI-v1.4.apk` ci-dessous."
        assertEquals("Nouveau\n\n- Signature sur le formulaire\n- La date aussi", Updater.notes(body))
    }

    @Test
    fun theSameOrAnOlderVersionIsNot() {
        assertFalse(Updater.isNewer("1.4", "1.4"))
        assertFalse(Updater.isNewer("1.4.0", "1.4"))
        assertFalse(Updater.isNewer("v1.3.1", "1.4"))
    }
}
