package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler

/** Starts only after the owning controller has been attached. */
internal fun createVehicleMediaSource(
    context: Context,
    handler: Handler,
    current: () -> Boolean,
    send: (Int) -> Boolean,
): VehicleMediaSource = ZeekrMediaSource(context, send = { index ->
    dispatchZeekrMediaCommand(handler, current) { send(index) }
}).also { source -> handler.post { source.start() } }
