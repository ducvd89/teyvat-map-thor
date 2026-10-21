package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.tracking.CapturePolicy.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a VirtualDisplay may be created (AYN system_server deadlock mitigation). */
class CapturePolicyTest {
    private val base = Inputs(nowMs = 100_000, consentActive = false, typing = false, lastWindowChangeMs = 0, lastReleaseMs = 0)

    @Test fun createsWhenEverythingIsQuiet() = assertEquals(0L, CapturePolicy.waitMs(base))

    @Test fun waitsWhileTheConsentActivityIsAlive() {
        assertTrue(CapturePolicy.waitMs(base.copy(consentActive = true)) > 0)
    }

    @Test fun waitsWhileATextFieldIsActive() {
        assertTrue(CapturePolicy.waitMs(base.copy(typing = true)) > 0)
    }

    @Test fun waitsForTheSettleTimeAfterAWindowChange() {
        assertEquals(1_000L, CapturePolicy.waitMs(base.copy(lastWindowChangeMs = 99_500)))
        assertEquals(0L, CapturePolicy.waitMs(base.copy(lastWindowChangeMs = 98_500)))
    }

    @Test fun noRecreateRightAfterARelease() {
        // the 19:07:39 -> 19:07:41 sequence: released 2 s ago, so wait for the cooldown
        assertEquals(2_000L, CapturePolicy.waitMs(base.copy(lastReleaseMs = 97_000)))
        assertEquals(0L, CapturePolicy.waitMs(base.copy(lastReleaseMs = 94_000)))
    }

    @Test fun neverASecondDisplayPerProjection() {
        assertEquals(CapturePolicy.NEVER, CapturePolicy.waitMs(base.copy(displayExists = true)))
    }
}
