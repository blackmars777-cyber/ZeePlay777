package com.shilapi.xcertplay.network

/** Request status only. ACCEPTED still needs an observed running hotspot. */
internal enum class VehicleHotspotRequestResult { UNAVAILABLE, REJECTED, ACCEPTED, TIMED_OUT, CANCELLED }
