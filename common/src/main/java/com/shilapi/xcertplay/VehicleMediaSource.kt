package com.shilapi.xcertplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** One connected controller's optional OEM media source, selected at build time.
 * Deactivate synchronously before posting close to the main thread so stale controls are rejected.
 */
internal interface VehicleMediaSource {
    fun update(info: CarPlayNowPlaying, artwork: Bitmap?)
    fun select(source: Int, display: Int): Boolean
    fun deactivate()
    fun close()
}
