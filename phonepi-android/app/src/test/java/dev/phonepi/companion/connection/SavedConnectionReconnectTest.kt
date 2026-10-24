package dev.phonepi.companion.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedConnectionReconnectTest {
    @Test
    fun packageReplaceReconnectsWhenHostIsSavedEvenIfBootToggleIsOff() {
        assertTrue(
            SavedConnectionReconnect.shouldStart(
                trigger = SavedConnectionReconnect.Trigger.PACKAGE_REPLACED,
                bootToggleOn = false,
                hasSavedHost = true,
            ),
        )
    }

    @Test
    fun packageReplaceDoesNothingWithoutASavedHost() {
        assertFalse(
            SavedConnectionReconnect.shouldStart(
                trigger = SavedConnectionReconnect.Trigger.PACKAGE_REPLACED,
                bootToggleOn = true,
                hasSavedHost = false,
            ),
        )
    }

    @Test
    fun bootHonorsTheToggle() {
        assertTrue(
            SavedConnectionReconnect.shouldStart(
                trigger = SavedConnectionReconnect.Trigger.BOOT,
                bootToggleOn = true,
                hasSavedHost = true,
            ),
        )
        assertFalse(
            SavedConnectionReconnect.shouldStart(
                trigger = SavedConnectionReconnect.Trigger.BOOT,
                bootToggleOn = false,
                hasSavedHost = true,
            ),
        )
        assertFalse(
            SavedConnectionReconnect.shouldStart(
                trigger = SavedConnectionReconnect.Trigger.BOOT,
                bootToggleOn = true,
                hasSavedHost = false,
            ),
        )
    }
}
