package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/** Optional edition-specific, read-only data. All accessors return cached data without vendor RPC. */
internal interface VehicleDataSource : AutoCloseable {
    fun speed(): VehicleSpeedSource
    val battery: VehicleStatusProvider
    fun warmUp()
    fun diagnostic(): String
    /** null means unknown/unavailable; callers must never turn it into parked=true. */
    fun parked(): Boolean?
}
