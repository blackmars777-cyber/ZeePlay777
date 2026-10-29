package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Media-center discovery/cold entry point. An absent iPhone is never reported as playing. */
class ZeekrMediaIntentService : Service() {
    private val endpoint = createZeekrSelectionEndpoint(CarPlayMediaKeys::onZeekrSourceSelected)

    override fun onBind(intent: Intent?): IBinder = endpoint

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Matches the SDK's state-recovery Intent contract. This handles the command and exits;
        // the existing DiPlay session service owns the actual connection and audio lifetime.
        zeekrSelection(intent?.getStringExtra("operation"), intent?.getStringExtra("operationData"),
            CarPlayMediaKeys::onZeekrSourceSelected)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
