package com.shilapi.xcertplay.network

import android.content.Context

/** OEM hotspot services are excluded from the standard APK. */
internal object VehicleHotspotStart {
    fun available(context: Context): Boolean = false
    fun request(context: Context, deadline: Long, isCancelled: () -> Boolean, log: (String) -> Unit) =
        VehicleHotspotRequestResult.UNAVAILABLE
}
