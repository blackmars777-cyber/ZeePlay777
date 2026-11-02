package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.*
import org.junit.Test

class ZeekrVehicleStateTest {
    private val now = 50_000_000_000L
    private fun event(value: Number?, timestamp: Long = now, available: Int? = 0) = ZeekrSensorValue(timestamp, value, available)
    private fun started(): Pair<ZeekrVehicleState, Long> {
        val state = ZeekrVehicleState()
        val ticket = state.begin()
        state.connected(ticket)
        return state to ticket
    }

    @Test fun observedGearSequenceAndKmhAreMappedWithoutUsingAospGearBits() {
        val (state, ticket) = started()
        for ((raw, expected) in listOf(0 to VehicleGear.PARK, 1 to VehicleGear.REVERSE,
            3 to VehicleGear.DRIVE, 0 to VehicleGear.PARK, 2 to VehicleGear.NEUTRAL, 0 to VehicleGear.PARK)) {
            state.publish(ticket, event(72f), event(raw), event(55f), now, true)
            val reading = state.drain(now)!!
            assertEquals(expected, reading.gear)
            assertEquals(20.0, reading.samples.single().metersPerSecond, 0.00001)
        }
        state.publish(ticket, event(0f), event(4), event(55f), now, true)
        assertNull(state.drain(now)) // Do not treat standard Android's PARK=4 as Zeekr PARK.
        assertNull(state.parked(now))
    }

    @Test fun onChangeGearSocAndStoppedSpeedCanRemainOldOnAHealthyConnection() {
        val (state, ticket) = started()
        val old = 1_000_000_000L
        state.publish(ticket, event(0f, old), event(0, old), event(55f, old), now, true)
        assertEquals(true, state.parked(now))
        assertEquals(55.0, state.battery(now)!!.batteryPercent, 0.0)
        assertNotNull(state.drain(now))
        assertNull(state.parked(now + ZeekrVehicleState.READ_HEALTH_NS + 1))
        assertNull(state.battery(now + ZeekrVehicleState.READ_HEALTH_NS + 1))
    }

    @Test fun staleMovingSpeedIsNotStampedAsNewAndContradictoryParkNeverAllowsVideo() {
        val (state, ticket) = started()
        state.publish(ticket, event(80f, now - 4_000_000_000L), event(0), event(55f), now, true)
        assertNull(state.drain(now))
        assertEquals(false, state.parked(now))
    }

    @Test fun unavailableMissingAndMalformedSamplesNeverTurnIntoParkOrEmptyBattery() {
        for (bad in listOf(event(null), event(Float.NaN), event(Float.POSITIVE_INFINITY),
            event(0, available = 1), event(0, available = null), event(0, timestamp = 0), event(0, timestamp = now + 1))) {
            val (state, ticket) = started()
            state.publish(ticket, event(0f), bad, bad, now, true)
            assertNull(state.parked(now))
            assertNull(state.battery(now))
            assertNull(state.drain(now))
        }
        val (state, ticket) = started()
        state.publish(ticket, event(-1f), event(0), event(101f), now, true)
        assertNull(state.parked(now))
        assertNull(state.battery(now))
        assertNull(state.drain(now))
    }

    @Test fun disconnectDiscardsStateAndLateOldReadsCannotRestoreItAfterReconnect() {
        val (state, first) = started()
        state.publish(first, event(0f), event(0), event(55f), now, true)
        state.invalidate(first)
        val next = state.begin()
        state.connected(next)
        state.publish(first, event(0f), event(0), event(55f), now, true)
        assertNull(state.parked(now))
        assertNull(state.battery(now))
        state.publish(next, event(0f), event(3), event(54f), now, true)
        state.invalidate(first) // Late death of the old Binder must not clear the new connection.
        assertEquals(false, state.parked(now))
        assertEquals(54.0, state.battery(now)!!.batteryPercent, 0.0)
    }

    @Test fun gearChangeDropsPreviouslyBufferedVelocitySamples() {
        val (state, ticket) = started()
        state.publish(ticket, event(36f), event(3), null, now, true)
        state.publish(ticket, event(0f), event(0), null, now + 1, true)
        val reading = state.drain(now + 1)!!
        assertEquals(VehicleGear.PARK, reading.gear)
        assertEquals(1, reading.samples.size)
        assertEquals(0.0, reading.samples.single().metersPerSecond, 0.0)
    }

    @Test fun batteryOnlyDoesNotClaimUnknownRangeChargingOrEnergy() {
        val (state, ticket) = started()
        state.publish(ticket, event(0f), event(0), event(55f), now, false)
        val battery = state.battery(now)!!
        assertFalse(battery.rangeAvailable)
        assertFalse(battery.chargingAvailable)
        assertNull(battery.currentChargeWh)
        assertNull(battery.maxChargeWh)
    }

    @Test fun preConnectionParkCacheDoesNotAuthorizeVideoAfterBindOrWake() {
        val state = ZeekrVehicleState()
        val ticket = state.begin(now)
        state.connected(ticket)
        state.publish(ticket, event(0f, 1), event(0, 1), event(55f, 1), now, false)
        assertNull(state.parked(now))
        assertNotNull(state.battery(now))
        state.publish(ticket, event(0f), event(0), event(55f), now, false)
        assertEquals(true, state.parked(now))
        state.invalidate(ticket)
        val afterWake = state.begin(now + 10)
        state.connected(afterWake)
        state.publish(afterWake, event(0f), event(0), event(55f), now + 10, false)
        assertNull(state.parked(now + 10))
    }

    @Test fun observedUnknownStatusFeedsReadOnlyDataButNeverParkVideo() {
        val (state, ticket) = started()
        state.publish(ticket, event(72f, available = -1), event(3, available = -1),
            event(53f, 1, -1), now, true, event(277, 1, -1))
        assertEquals(20.0, state.drain(now)!!.samples.single().metersPerSecond, 0.00001)
        val status = state.battery(now)!!
        assertEquals(53.0, status.batteryPercent, 0.0)
        assertEquals(277, status.rangeKm)
        assertTrue(status.rangeAvailable)
        assertFalse(status.rangeWarningAvailable)
        assertFalse(status.maxRangeAvailable)
        assertFalse(status.chargingAvailable)
        assertNull(state.parked(now))
        state.publish(ticket, event(0f, available = -1), event(0, available = -1),
            event(53f, available = -1), now, true, event(276, available = -1))
        assertNull(state.parked(now))
        assertEquals(276, state.battery(now)!!.rangeKm) // Never hard-code the calibration value.
        state.invalidate(ticket)
        assertNull(state.battery(now))
        assertNull(state.drain(now))
    }

    @Test fun unknownOldStoppedCacheIsNotRestampedAndRangeFailuresDoNotEraseSoc() {
        val (state, ticket) = started()
        state.publish(ticket, event(0f, 1, -1), event(0, 1, -1), event(53f, 1, -1),
            now, true, event(277, 1, 1))
        assertNull(state.drain(now))
        assertNull(state.parked(now))
        assertEquals(53.0, state.battery(now)!!.batteryPercent, 0.0)
        assertFalse(state.battery(now)!!.rangeAvailable)
        for (bad in listOf(event(277, available = null), event(3001), event(-1), event(277.5), event(277, timestamp = now+1))) {
            state.publish(ticket, event(0f), event(0), event(53f), now, false, bad)
            assertFalse(state.battery(now)!!.rangeAvailable)
        }
    }

    @Test fun unitAndEnumProfileIsNotAppliedToOtherModelsOrNewFirmware() {
        val version = "zeekr_dhu-userdebug 12 SQ3A.220705.003.A1 eng.buildf.20260902.185825 test-keys"
        assertTrue(ZeekrVehicleState.supported("Zeekr DHU for arm64", version))
        assertFalse(ZeekrVehicleState.supported("other", version))
        assertFalse(ZeekrVehicleState.supported("Zeekr DHU for arm64", version + "-new"))
    }
}
