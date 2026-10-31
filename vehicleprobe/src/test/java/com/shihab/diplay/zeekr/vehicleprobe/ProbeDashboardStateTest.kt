package com.shihab.diplay.zeekr.vehicleprobe

import org.junit.Assert.*
import org.junit.Test

class ProbeDashboardStateTest {
    @Test fun unavailableValuesAndUnknownProfilesNeverBecomeZeroOrASelectedGear() {
        val state=ProbeDashboardState(true)
        state.reading(ProbeDashboardSnapshot.SPEED,0.0,1,2,1)
        state.reading(ProbeDashboardSnapshot.GEAR,0.0,1,2,2)
        assertNull(state.snapshot().speed)
        assertNull(state.snapshot().gear)
        state.reading(ProbeDashboardSnapshot.SPEED,Double.NaN,1,2,0)
        assertNull(state.snapshot().speed)
        state.reading(ProbeDashboardSnapshot.SPEED,0.0,0,2,0)
        assertNull(state.snapshot().speed)
        val unknown=ProbeDashboardState(false)
        unknown.reading(ProbeDashboardSnapshot.GEAR,0.0,1,2,0)
        assertNull(unknown.snapshot().gear)
    }
    @Test fun unknownStatusShowsDiagnosticEvidenceWithoutTreatingItAsAvailable() {
        val state=ProbeDashboardState(true)
        state.reading(ProbeDashboardSnapshot.SPEED,12.0,1_000_000_000,2_000_000_000,-1)
        assertEquals(12.0,state.snapshot().speed!!,0.0)
        assertEquals(12.0,state.snapshot().speedHistory.last().kmh!!,0.0)
        assertEquals(-1,state.snapshot().value(ProbeDashboardSnapshot.SPEED).availability)
        assertEquals("缓存读数 · 状态待核对",state.snapshot().value(ProbeDashboardSnapshot.SPEED).stateLabel)
        state.reading(ProbeDashboardSnapshot.BATTERY,53.0,1,2,-1)
        assertEquals(53.0,state.snapshot().battery!!,0.0)
        state.failed(ProbeDashboardSnapshot.BATTERY)
        assertNull(state.snapshot().battery)
        state.phase(ProbePhase.DISCONNECTED)
        assertNull(state.snapshot().speed)
    }
    @Test fun disconnectRetractsMeasurementsAndOnChangeTimestampsAreNotAutomaticFailure() {
        val state=ProbeDashboardState(true)
        state.phase(ProbePhase.CONNECTED)
        state.reading(ProbeDashboardSnapshot.GEAR,3.0,1,100_000_000_000,0)
        state.reading(ProbeDashboardSnapshot.BATTERY,55.0,1,100_000_000_000,0)
        assertEquals(3,state.snapshot().gear)
        assertEquals(55.0,state.snapshot().battery!!,0.0)
        state.phase(ProbePhase.DISCONNECTED)
        assertNull(state.snapshot().gear)
        assertNull(state.snapshot().battery)
    }
    @Test fun historyIsBoundedAndOldMovingSpeedCreatesAGapInsteadOfAFakeLiveLine() {
        val state=ProbeDashboardState(true)
        repeat(100) { state.reading(ProbeDashboardSnapshot.SPEED,it.toDouble(),10_000_000_000L,10_000_000_000L+it,0) }
        assertEquals(60,state.snapshot().speedHistory.size)
        state.reading(ProbeDashboardSnapshot.SPEED,88.0,1,100_000_000_000L,0)
        assertNull(state.snapshot().speedHistory.last().kmh)
        state.reading(ProbeDashboardSnapshot.SPEED,0.0,1,100_000_000_000L,0)
        assertNull(state.snapshot().speedHistory.last().kmh)
    }
}
