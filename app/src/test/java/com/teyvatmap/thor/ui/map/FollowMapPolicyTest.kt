package com.teyvatmap.thor.ui.map

import com.teyvatmap.thor.tracking.PlayerPose
import com.teyvatmap.thor.tracking.cv.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowMapPolicyTest {
    private fun pose(map: Int, state: TrackState = TrackState.TRACKING, has: Boolean = true) = PlayerPose(mapId = map, state = state, hasPosition = has)

    @Test fun followSwitchesToThePlayersMapAfterARelocation() {
        assertEquals(9, FollowMapPolicy.target(true, true, pose(9), displayed = 2))       // teleport into The Chasm
        assertEquals(2, FollowMapPolicy.target(true, true, pose(2, TrackState.FIXED), displayed = 9))
    }

    @Test fun nothingHappensWhenNotFollowingOrNotTrackingOrAlreadyThere() {
        assertNull(FollowMapPolicy.target(false, true, pose(9), 2))                        // panned / switched by hand: follow is off
        assertNull(FollowMapPolicy.target(true, false, pose(9), 2))                        // tracking stopped
        assertNull(FollowMapPolicy.target(true, true, pose(2), 2))
        assertNull(FollowMapPolicy.target(true, true, pose(9), null))                      // maps not loaded yet
    }

    @Test fun onlyASettledPositionSwitchesTheMap() {
        for (st in listOf(TrackState.RELOCATING, TrackState.LOADING, TrackState.LOST, TrackState.HIDDEN, TrackState.DOMAIN, TrackState.OFF)) {
            assertNull(st.name, FollowMapPolicy.target(true, true, pose(9, st), 2))
        }
        assertNull(FollowMapPolicy.target(true, true, pose(9, has = false), 2))
    }
}
