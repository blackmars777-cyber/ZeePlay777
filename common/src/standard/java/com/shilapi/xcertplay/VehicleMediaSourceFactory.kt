package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler

/** Standard builds use Android MediaSession without an additional OEM source. */
@Suppress("UNUSED_PARAMETER")
internal fun createVehicleMediaSource(
    context: Context,
    handler: Handler,
    current: () -> Boolean,
    send: (Int) -> Boolean,
): VehicleMediaSource? = null
