package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/** Raw SDK event. Missing availability is unknown, never implicitly AVAILABLE. */
internal data class ZeekrSensorValue(val timestampNs: Long, val value: Number?, val availability: Int?)

/** Separates last-change timestamps from successful reads and invalidates every connection epoch. */
internal class ZeekrVehicleState {
    private var epoch = 0L
    private var epochStartedAt = 0L
    private var gearEventNs = 0L
    private var connected = false
    private var readAtNs = -1L
    private var gear: VehicleGear? = null
    private var kmh: Double? = null
    private var percent: Double? = null
    private var availability = "unread"
    private val samples = ArrayList<VehicleSpeedSample>()

    @Synchronized fun begin(nowNs: Long = 0): Long { epoch++; clear(); epochStartedAt = nowNs; return epoch }
    @Synchronized fun connected(ticket: Long) { if (ticket == epoch) connected = true }
    @Synchronized fun invalidate(ticket: Long) { if (ticket == epoch) { epoch++; clear() } }
    @Synchronized fun isConnected(ticket: Long): Boolean = epoch == ticket && connected
    @Synchronized fun clearSamples() = samples.clear()

    @Synchronized fun publish(ticket: Long, speed: ZeekrSensorValue?, shift: ZeekrSensorValue?,
        battery: ZeekrSensorValue?, nowNs: Long, collectSpeed: Boolean) {
        if (epoch != ticket || !connected) return
        fun valid(value: ZeekrSensorValue?): Boolean = value != null && value.availability == 0 &&
            value.timestampNs > 0 && value.timestampNs <= nowNs && value.value?.toDouble()?.isFinite() == true
        val nextGear = shift?.takeIf(::valid)?.value?.toDouble()?.let { raw ->
            if (raw != raw.toInt().toDouble()) null else when (raw.toInt()) {
                0 -> VehicleGear.PARK
                1 -> VehicleGear.REVERSE
                2 -> VehicleGear.NEUTRAL
                3 -> VehicleGear.DRIVE
                else -> null
            }
        }
        if (nextGear != gear) samples.clear() // Never label samples from a previous gear with a new one.
        gear = nextGear
        gearEventNs = if (nextGear != null) shift!!.timestampNs else 0L
        kmh = speed?.takeIf(::valid)?.value?.toDouble()?.takeIf { it in 0.0..250.0 }
        percent = battery?.takeIf(::valid)?.value?.toDouble()?.takeIf { it in 0.0..100.0 }
        availability = "speed=${speed?.availability},gear=${shift?.availability},battery=${battery?.availability}"
        readAtNs = nowNs
        // Old moving-speed cache entries must not be re-stamped as new measurements. Zero can be
        // on-change and stay cached at a red light, but only on a healthy, freshly read connection.
        val usableSpeed = kmh?.takeIf { it == 0.0 || nowNs - speed!!.timestampNs <= MOVING_SPEED_AGE_NS }
        if (collectSpeed && gear != null && usableSpeed != null) {
            if (samples.size == 40) samples.removeAt(0)
            samples += VehicleSpeedSample(nowNs / 1_000_000, usableSpeed / 3.6)
        } else samples.clear()
    }

    @Synchronized fun drain(nowNs: Long): VehicleSpeedReading? {
        if (!healthy(nowNs) || gear == null || samples.isEmpty()) { samples.clear(); return null }
        return VehicleSpeedReading(gear!!, samples.toList()).also { samples.clear() }
    }

    @Synchronized fun parked(nowNs: Long): Boolean? {
        if (!healthy(nowNs) || gear == null) return null
        if (gear != VehicleGear.PARK) return false
        if (gearEventNs < epochStartedAt) return null // Cached P from before bind/wake is not new park evidence.
        return kmh?.let { it == 0.0 }
    }

    @Synchronized fun battery(nowNs: Long): VehicleStatusSnapshot? = percent?.takeIf { healthy(nowNs) }?.let {
        VehicleStatusSnapshot(rangeKm = 0, rangeWarning = false, batteryPercent = it,
            currentChargeWh = null, maxChargeWh = null, maxRangeKm = 0,
            rangeAvailable = false, chargingAvailable = false)
    }

    @Synchronized fun status(): String = "Zeekr CarSensor connected=$connected gear=${gear?.letter ?: "unknown"}" +
        " speedKmh=$kmh batteryPercent=$percent availability=[$availability] parkEventAfterConnect=${gearEventNs>=epochStartedAt}"

    private fun healthy(nowNs: Long) = connected && readAtNs >= 0 && nowNs >= readAtNs &&
        nowNs - readAtNs <= READ_HEALTH_NS
    private fun clear() { connected = false; readAtNs = -1; gear = null; kmh = null; percent = null; samples.clear() }

    companion object {
        const val READ_HEALTH_NS = 1_500_000_000L
        const val MOVING_SPEED_AGE_NS = 3_000_000_000L
        // Units and enums are vehicle observations for this profile, not a general android.car contract.
        fun supported(model: String, display: String) = model == "Zeekr DHU for arm64" &&
            display == "zeekr_dhu-userdebug 12 SQ3A.220705.003.A1 eng.buildf.20260902.185825 test-keys"
    }
}
