package com.teyvatmap.thor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespawnTest {
    private val h = 3_600_000L

    @Test fun expiresAfterConfiguredHours() {
        assertEquals(1_000L + 72 * h, RespawnMath.expiresAt(1_000, 72))
        assertNull(RespawnMath.expiresAt(1_000, 0))
        assertNull(RespawnMath.expiresAt(1_000, -5))
    }

    @Test fun isExpiredBoundary() {
        val found = 10_000L
        assertFalse(RespawnMath.isExpired(found, 24, found + 24 * h - 1))
        assertTrue(RespawnMath.isExpired(found, 24, found + 24 * h))
        assertFalse(RespawnMath.isExpired(found, 0, found + 1000 * h)) // never respawns
    }

    @Test fun remainingClampsAtZero() {
        assertEquals(24 * h - 5 * h, RespawnMath.remainingMs(0, 24, 5 * h))
        assertEquals(0L, RespawnMath.remainingMs(0, 24, 99 * h))
        assertNull(RespawnMath.remainingMs(0, 0, 5 * h))
    }

    @Test fun formatRemaining() {
        assertEquals("2d 3h", RespawnMath.formatRemaining(51 * h))
        assertEquals("3d", RespawnMath.formatRemaining(72 * h))
        assertEquals("5h 12m", RespawnMath.formatRemaining(5 * h + 12 * 60_000))
        assertEquals("42m", RespawnMath.formatRemaining(42 * 60_000))
        assertEquals("<1m", RespawnMath.formatRemaining(10_000))
        assertEquals("<1m", RespawnMath.formatRemaining(-5))
    }

    @Test fun stepperWalksTheSteps() {
        assertEquals(6, RespawnMath.stepUp(0))
        assertEquals(24, RespawnMath.stepUp(13))
        assertEquals(168, RespawnMath.stepUp(168))
        assertEquals(0, RespawnMath.stepDown(6))
        assertEquals(12, RespawnMath.stepDown(24))
        assertEquals(0, RespawnMath.stepDown(0))
        assertEquals("never", RespawnMath.formatHours(0))
        assertEquals("3 d", RespawnMath.formatHours(72))
        assertEquals("12 h", RespawnMath.formatHours(12))
    }

    @Test fun shippedDefaults() {
        assertEquals(72, RespawnDefaults.hoursFor(15, RespawnDefaults.GROUP_ORES)) // White Iron Chunk
        assertEquals(48, RespawnDefaults.hoursFor(34, RespawnDefaults.GROUP_SPECIALTIES)) // Violetgrass
        assertEquals(12, RespawnDefaults.hoursFor(281, RespawnDefaults.GROUP_ANIMALS)) // Anemo Crystalfly
        assertEquals(24, RespawnDefaults.hoursFor(274, RespawnDefaults.GROUP_ANIMALS)) // White Pigeon
        assertEquals(0, RespawnDefaults.hoursFor(5, 4)) // Anemoculus: never
        assertEquals(0, RespawnDefaults.hoursFor(17, 13)) // Common Chest: never
    }
}
