package com.shilapi.xcertplay

import android.app.PendingIntent
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.json.JSONObject
import java.util.UUID

/** Binder/worker callbacks must not operate the car video player's views off the main thread. */
internal fun dispatchZeekrMediaCommand(handler: Handler, current: () -> Boolean, send: () -> Boolean): Boolean {
    if (!current()) return false
    if (Looper.myLooper() == handler.looper) return send()
    return handler.post { if (current()) send() }
}

internal fun zeekrSelection(method: String?, json: String?, selected: (Int, Int) -> Boolean): Boolean {
    if (method != "onSourceSelected" && method != "onSourceSelectedWithDisplayId") return false
    return runCatching {
        val data = JSONObject(json ?: return false)
        selected(data.getInt("sourceType"), if (method == "onSourceSelectedWithDisplayId") data.optInt("displayId", 0) else 0)
    }.getOrDefault(false)
}

internal fun createZeekrSelectionEndpoint(selected: (Int, Int) -> Boolean): IBinder =
    ZeekrCallbackBinder(ZeekrMediaProtocol.SUPPORT) { code, data, reply ->
        if (code !in 1..3 || reply == null) return@ZeekrCallbackBinder false
        val success = runCatching {
            if (data.readInt() == 0) return@runCatching false
            data.readString() // service
            data.readString() // module
            val method = data.readString()
            val parameter = data.createByteArray()
            data.createByteArray() // attachment
            if (code != 1) return@runCatching false
            zeekrSelection(method, ZeekrMediaProtocol.readStringMessage(parameter), selected)
        }.getOrDefault(false)
        reply.writeNoException()
        ZeekrMediaProtocol.writeResult(reply, success)
        true
    }

internal fun createZeekrMusicClient(
    packageName: String,
    active: () -> Boolean,
    info: () -> IBinder,
    progress: () -> Long,
    command: (Int) -> Boolean,
    focusLost: () -> Unit,
): IBinder = ZeekrCallbackBinder(ZeekrMediaProtocol.CLIENT) { code, data, reply ->
    fun result(value: Boolean) { reply?.writeNoException(); reply?.writeInt(if (value) 1 else 0) }
    fun send(index: Int) = active() && command(index)
    when (code) {
        1 -> result(send(CarPlayMediaButton.PLAY))
        2 -> result(send(CarPlayMediaButton.PAUSE))
        3 -> result(send(CarPlayMediaButton.NEXT))
        4 -> result(send(CarPlayMediaButton.PREVIOUS))
        8 -> result(data.readInt() == ZeekrMediaProtocol.SOURCE && send(CarPlayMediaButton.PLAY))
        10 -> { reply?.writeNoException(); reply?.writeStrongBinder(if (active()) info() else null) }
        11 -> { reply?.writeNoException(); reply?.writeIntArray(if (active()) intArrayOf(ZeekrMediaProtocol.SOURCE) else intArrayOf()) }
        12 -> { reply?.writeNoException(); reply?.writeInt(if (active()) ZeekrMediaProtocol.SOURCE else 0) }
        13 -> { reply?.writeNoException(); reply?.writeLong(if (active()) progress() else 0L) }
        14 -> { data.readInt(); reply?.writeNoException(); reply?.writeInt(-1) } // null playlist
        25 -> {
            val selected = data.readString()
            // An empty focus owner can be a service reset, not an explicit source switch.
            if (active() && !selected.isNullOrEmpty() && selected != packageName &&
                !selected.startsWith("$packageName:")) focusLost()
            reply?.writeNoException()
        }
        41, 42, 43, 44 -> {
            data.readInt() // originating control surface
            val display = data.readInt()
            data.readStrongBinder() // optional completion callback; Boolean reports acceptance
            val key = when (code) {
                41 -> CarPlayMediaButton.PLAY
                42 -> CarPlayMediaButton.PAUSE
                43 -> CarPlayMediaButton.NEXT
                else -> CarPlayMediaButton.PREVIOUS
            }
            result(display == 0 && send(key))
        }
        // Unsupported controls must not claim success (seek, collect, loop, downloads, catalog).
        5, 6, 7, 9, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 26, 27,
        30, 31, 32, 36, 37, 38, 39, 40, 45, 46, 47, 48, 50 -> result(false)
        28 -> { reply?.writeNoException(); reply?.writeInt(-1) } // null content list
        29 -> { reply?.writeNoException(); reply?.writeInt(0) } // null Parcelable
        33, 34, 35, 49 -> reply?.writeNoException()
        else -> return@ZeekrCallbackBinder false
    }
    true
}

internal fun createZeekrPlaybackInfo(
    packageName: String,
    snapshot: () -> CarPlayNowPlaying,
    artwork: () -> Uri?,
    launch: (Int) -> PendingIntent?,
    available: () -> Boolean = { true },
): IBinder = ZeekrCallbackBinder(ZeekrMediaProtocol.INFO) { code, data, reply ->
    if (code !in 1..54 || reply == null) return@ZeekrCallbackBinder false
    val info = snapshot()
    reply.writeNoException()
    when (code) {
        1, 33, 39, 40 -> {
            val display = if (code == 39 || code == 40) data.readInt() else 0
            val intent = launch(display)
            reply.writeInt(if (intent == null) 0 else 1)
            intent?.writeToParcel(reply, 0)
        }
        2 -> reply.writeString(info.title.orEmpty())
        3 -> reply.writeString(info.artist.orEmpty())
        4 -> reply.writeString(info.album.orEmpty())
        5, 6, 12, 14, 26, 30, 35, 37, 38, 45, 46, 52, 54 -> reply.writeString("")
        24 -> reply.writeString(UUID.nameUUIDFromBytes(listOf(info.sourceApp, info.title, info.artist, info.album)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)).toString())
        7 -> reply.writeLong(info.durationMillis ?: 0L)
        9 -> reply.writeInt(ZeekrMediaProtocol.SOURCE)
        10, 13, 15, 17 -> reply.writeInt(0) // optional URI
        11 -> reply.writeInt(if (!available()) 3 else if (info.playing) 1 else 0) // Zeekr states differ from Android!
        16 -> {
            val uri = artwork()
            reply.writeInt(if (uri == null) 0 else 1)
            uri?.writeToParcel(reply, 0)
        }
        25 -> reply.writeString(com.shilapi.xcertplay.host.BuildConfig.EDITION_APPLICATION_LABEL)
        27 -> reply.writeString(packageName)
        29 -> reply.writeInt(1) // play/pause VR controls
        31, 32 -> reply.writeInt(-1)
        42 -> reply.writeLong(info.elapsedMillis ?: 0L)
        43 -> reply.writeInt(1) // music category
        47 -> reply.writeInt(7) // SDK default transport controls
        else -> reply.writeInt(0) // capabilities/visibility off; no seek, loop, collect or downloads
    }
    true
}
