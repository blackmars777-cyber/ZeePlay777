package com.shilapi.xcertplay

import android.content.Context
import android.os.Build

internal fun createVehicleDataSource(context: Context): VehicleDataSource? =
    if (ZeekrVehicleState.supported(Build.MODEL, Build.DISPLAY)) ZeekrVehicleData(context.applicationContext) else null

internal fun nativeVehicleDataSupported(): Boolean = ZeekrVehicleState.supported(Build.MODEL, Build.DISPLAY)
