package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ZeekrMediaProtocolTest {
    @Test fun binderCommandsRunOnMainAndAreDiscardedIfThePhoneDisconnectsWhileQueued() {
        var active = true
        var sent = 0
        var accepted = false
        val handler = Handler(Looper.getMainLooper())
        val sender = {
            dispatchZeekrMediaCommand(handler, { active }) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                sent++
                true
            }
        }
        Thread { accepted = sender() }.apply { start(); join() }
        assertTrue(accepted)
        assertEquals(0, sent)
        active = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, sent)
        active = true
        Thread { accepted = sender() }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(accepted)
        assertEquals(1, sent)
    }

    @Test fun coreEnvelopeCarriesAProtobufStringAndRealPackage() {
        val callback = Binder()
        val core = ZeekrCallbackBinder("com.zeekr.sdk.base.internal.IZeekrCoreService") { code, data, reply ->
            assertEquals(6, code)
            assertEquals(1, data.readInt())
            assertEquals("mediacenter", data.readString())
            assertEquals("ZeekrMediaCenterAPI", data.readString())
            assertEquals("mediaCenterRegisterMusicNew", data.readString())
            assertArrayEquals(byteArrayOf(10, 3, 97, 98, 99), data.createByteArray())
            assertNull(data.createByteArray())
            assertSame(callback, data.readStrongBinder())
            reply!!.writeNoException()
            reply.writeInt(1)
            reply.writeInt(403)
            true
        }
        assertEquals(403, ZeekrMediaProtocol.route(core, "mediaCenterRegisterMusicNew", "abc", callback))
        val long = "车".repeat(200)
        assertEquals(long, ZeekrMediaProtocol.readStringMessage(ZeekrMediaProtocol.stringMessage(long)))
        assertThrows(IllegalArgumentException::class.java) { ZeekrMediaProtocol.readStringMessage(byteArrayOf(10, -1)) }
    }

    @Test fun commandsAreExplicitAndStaleClientsCannotControlThePhone() {
        val sent = mutableListOf<Int>()
        var active = true
        val client = createZeekrMusicClient("diplay", { active }, { Binder() }, { 1200L }, { sent += it; true }, {})
        for (code in 1..4) assertEquals(1, intCall(client, ZeekrMediaProtocol.CLIENT, code))
        assertEquals(listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS), sent)
        assertEquals(0, intCall(client, ZeekrMediaProtocol.CLIENT, 8) { it.writeInt(2) })
        assertEquals(1, intCall(client, ZeekrMediaProtocol.CLIENT, 8) { it.writeInt(6) })
        active = false
        assertEquals(0, intCall(client, ZeekrMediaProtocol.CLIENT, 1))
        assertEquals(5, sent.size)
        assertEquals(0, intCall(client, ZeekrMediaProtocol.CLIENT, 36) { it.writeLong(4000L) })
    }

    @Test fun sourceSwitchIgnoresOwnUserAndEmptyOwnerAndRejectsPassengerControls() {
        var lost = 0
        var controls = 0
        val client = createZeekrMusicClient("diplay", { true }, { Binder() }, { 0L }, { controls++; true }, { lost++ })
        listOf(null, "", "diplay", "diplay:10", "other.music").forEach { owner ->
            ZeekrMediaProtocol.command(client, ZeekrMediaProtocol.CLIENT, 25) { it.writeString(owner) }
        }
        assertEquals(1, lost)
        assertEquals(0, intCall(client, ZeekrMediaProtocol.CLIENT, 41) { it.writeInt(0); it.writeInt(1); it.writeStrongBinder(null) })
        assertEquals(0, controls)
    }

    @Test fun infoUsesVendorPlaybackStatesAndAdvertisesOnlyImplementedCapabilities() {
        var now = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 10000, elapsedMillis = 2400, playing = true)
        val info = createZeekrPlaybackInfo("diplay", { now }, { null }, { null })
        assertEquals("Song", ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 2) { it.readString() })
        assertEquals(1, intCall(info, ZeekrMediaProtocol.INFO, 11))
        now = now.copy(playing = false)
        assertEquals(0, intCall(info, ZeekrMediaProtocol.INFO, 11))
        assertEquals(2400L, ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 42) { it.readLong() })
        for (code in listOf(20, 22, 28, 48)) assertEquals(0, intCall(info, ZeekrMediaProtocol.INFO, code))
        assertEquals("ZeePlay", ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 25) { it.readString() })
        assertEquals("diplay", ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 27) { it.readString() })
        val response = Parcel.obtain()
        val request = Parcel.obtain()
        try {
            request.writeInterfaceToken(ZeekrMediaProtocol.INFO)
            assertFalse(info.transact(99, request, response, 0))
        } finally { request.recycle(); response.recycle() }
    }

    @Test fun selectionEndpointChecksPayloadAndDoesNotInventSuccess() {
        var calls = 0
        val selected: (Int, Int) -> Boolean = { source, display ->
            if (source == 6 && display == 0) { calls++; true } else false
        }
        assertTrue(zeekrSelection("onSourceSelected", "{\"sourceType\":6,\"from\":1}", selected))
        assertFalse(zeekrSelection("onSourceSelectedWithDisplayId", "{\"sourceType\":6,\"displayId\":1}", selected))
        assertFalse(zeekrSelection("onSourceSelected", "{}", selected))
        assertFalse(zeekrSelection("onSourceSelected", "bad json", selected))
        assertFalse(zeekrSelection("unknown", "{\"sourceType\":6}", selected))
        assertEquals(1, calls)
        val endpoint = createZeekrSelectionEndpoint(selected)
        val result = ZeekrMediaProtocol.call(endpoint, ZeekrMediaProtocol.SUPPORT, 1, {
            it.writeInt(1); it.writeString("mediacenter"); it.writeString("ZeekrMediaCenterAPI")
            it.writeString("onSourceSelected"); it.writeByteArray(byteArrayOf(10, -1)); it.writeByteArray(null)
        }) { assertEquals(1, it.readInt()); it.readInt() }
        assertEquals(400, result)
        assertEquals(1, calls)
    }

    private fun intCall(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit = {}): Int =
        ZeekrMediaProtocol.call(binder, descriptor, code, write) { it.readInt() }
}
