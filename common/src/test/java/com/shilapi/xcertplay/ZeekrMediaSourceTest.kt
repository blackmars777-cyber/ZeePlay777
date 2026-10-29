package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [FileProviderPathTestShadow::class])
class ZeekrMediaSourceTest {
    @Test fun unavailableCoreUsesDirectSupportServiceAndPublishesThePhoneSource() {
        val car = FakeCar(initFailures = Int.MAX_VALUE, initFailureReason = 305, directPresent = true)
        val sent = mutableListOf<Int>()
        val source = ZeekrMediaSource(car.context, { sent += it; true }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(title = "Phone song", playing = true), null)
        idle()
        assertEquals(1, car.registrations)
        assertEquals(car.context.packageName, car.currentPackage)
        assertEquals("Phone song", car.title)
        assertTrue(source.select(6, 0))
        assertEquals(listOf(1), sent)
        source.close()
        idle()
        assertEquals(1, car.unregistered)
        assertEquals(1, car.removedListeners)
    }

    @Test fun lateDirectConnectionDoesNotReplaceARecoveredCoreRegistration() {
        val car = FakeCar(initFailures = Int.MAX_VALUE, initFailureReason = 305,
            directPresent = true, delayDirectConnection = true)
        val source = ZeekrMediaSource(car.context, { true }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(title = "Recovered", playing = true), null)
        idle()
        car.connected()
        idle()
        assertEquals(1, car.registrations)
        car.directConnection!!.onServiceConnected(ZeekrMediaSource.DIRECT_COMPONENT, car.direct)
        idle()
        assertEquals(1, car.registrations)
        assertEquals(0, car.removedListeners)
        assertEquals("Recovered", car.title)
        source.close()
        idle()
        assertEquals(1, car.unregistered)
    }

    @Test fun directSourceSurvivesUnrelatedCoreDisconnectAndKeepsPublishing() {
        val car = FakeCar(initFailures = Int.MAX_VALUE, initFailureReason = 305, directPresent = true)
        val source = ZeekrMediaSource(car.context, { true }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        car.coreConnection!!.onServiceDisconnected(ZeekrMediaSource.COMPONENT)
        idle()
        source.update(CarPlayNowPlaying(title = "Still playing", playing = true), null)
        idle()
        assertEquals("Still playing", car.title)
        assertEquals(car.context.packageName, car.currentPackage)
        assertEquals(1, car.registrations)
        source.close()
        idle()
    }

    @Test fun missingCoreCanUseDirectServiceButPermissionRejectionDoesNotClaimSource() {
        val car = FakeCar(present = false, directPresent = true, status = 403)
        val source = ZeekrMediaSource(car.context, { false }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(playing = true), null)
        idle()
        assertEquals(1, car.binds)
        assertEquals(0, car.initCalls)
        assertEquals(0, car.registrations)
        assertEquals(0, car.claims)
        source.close()
        idle()
    }

    @Test fun deniedPlayRequestDoesNotPublishAFalseSourceSwitch() {
        val car = FakeCar(playAccepted = false)
        val source = ZeekrMediaSource(car.context, { false }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(playing = true), null)
        idle()
        assertEquals(1, car.claims)
        assertEquals("previous.player", car.currentPackage)
        source.close()
        idle()
    }

    @Test fun incompatibleDirectInterfaceDoesNotReceiveMediaTransactions() {
        val car = FakeCar(initFailures = Int.MAX_VALUE, initFailureReason = 305,
            directPresent = true, directDescriptor = "unsupported.firmware")
        val source = ZeekrMediaSource(car.context, { false }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(playing = true), null)
        idle()
        assertEquals(0, car.registrations)
        assertEquals("previous.player", car.currentPackage)
        source.close()
        idle()
    }

    @Test fun persistentStartupFailureStopsRetryingAfterThreeAttempts() {
        val car = FakeCar(initFailures = Int.MAX_VALUE)
        val source = ZeekrMediaSource(car.context, { fail("no command before registration"); false }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))
        assertEquals(3, car.initCalls)
        assertEquals("previous.player", car.currentPackage)
        assertEquals(0, car.registrations)
        source.close()
        idle()
    }

    @Test fun explicitUnavailableServiceIsReportedWithoutAStartupRetryLoop() {
        val car = FakeCar(initFailures = Int.MAX_VALUE, initFailureReason = 305)
        val source = ZeekrMediaSource(car.context, { false }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))
        assertEquals(1, car.initCalls)
        assertTrue(WheelKeyLog.report(false).any { it.contains("media service disconnected reason=305") })
        source.close()
        idle()
    }

    @Test fun temporaryMediaServiceInitFailureRecoversCurrentSourceAndControls() {
        val car = FakeCar(initFailures = 1)
        var controls = 0
        val source = ZeekrMediaSource(car.context, { controls++; true }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(title = "Phone song", playing = true), null)
        idle()
        assertEquals("previous.player", car.currentPackage)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(car.context.packageName, car.currentPackage)
        assertEquals(1, ZeekrMediaProtocol.call(car.client!!, ZeekrMediaProtocol.CLIENT, 3) { it.readInt() })
        assertEquals(1, controls)
        source.close()
        idle()
    }

    @Test fun positionAdvancesBetweenPhoneReportsAndStopsWhilePaused() {
        val car = FakeCar()
        val source = ZeekrMediaSource(car.context, { true }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(title = "Song", elapsedMillis = 1000, durationMillis = 5000, playing = true), null)
        idle()
        val metadata = car.metadataUpdates
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(3000L, car.position)
        assertEquals(metadata, car.metadataUpdates)
        source.update(CarPlayNowPlaying(title = "Song", elapsedMillis = 3000, durationMillis = 5000, playing = false), null)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals(3000L, car.position)
        source.close()
        idle()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun artworkUsesRestrictedProviderFilesAndIsRemovedOnDisconnect() {
        val car = FakeCar()
        val authority = "${car.context.packageName}.zeekr-artwork"
        val info = car.context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, ZeekrArtworkProvider().apply { attachInfo(car.context, info) })
        val source = ZeekrMediaSource(car.context, { true }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(title = "Cover"), Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        idle()
        assertNotNull(WheelKeyLog.report(false).joinToString("\n"), car.artwork)
        val uri = car.artwork!!
        assertEquals("content", uri.scheme)
        assertEquals("${car.context.packageName}.zeekr-artwork", uri.authority)
        val stream = car.context.contentResolver.openInputStream(uri)!!
        stream.use { assertEquals(255, it.read()); assertEquals(216, it.read()) } // JPEG signature
        source.close()
        idle()
        assertThrows(java.io.FileNotFoundException::class.java) { car.context.contentResolver.openInputStream(uri) }
    }

    @Test fun publishesPhoneStateWithoutReclaimingFocusOnProgressOrSwitchAway() {
        val car = FakeCar()
        val sent = mutableListOf<Int>()
        val source = ZeekrMediaSource(car.context, { sent += it; true }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        assertEquals(listOf(6), car.sources.toList())
        assertEquals(0, car.claims)
        val song = CarPlayNowPlaying(title = "Phone song", artist = "Artist", elapsedMillis = 1200, playing = true)
        source.update(song, null)
        idle()
        assertEquals("Phone song", car.title)
        assertEquals(1, car.state)
        assertEquals(1200L, car.position)
        assertEquals(1, car.claims)
        val metadataUpdates = car.metadataUpdates
        source.update(song.copy(elapsedMillis = 2400), null)
        idle()
        assertEquals(metadataUpdates, car.metadataUpdates)
        assertEquals(2400L, car.position)
        car.focus("another.music")
        idle()
        assertEquals(listOf(2), sent)
        source.update(song.copy(elapsedMillis = 2600), null)
        idle()
        assertEquals(1, car.claims)
        assertTrue(source.select(6, 0))
        idle()
        assertEquals(listOf(2, 1), sent)
        assertEquals(2, car.claims)
        source.close()
        idle()
        assertEquals(1, car.unregistered)
        assertEquals(1, car.removedListeners)
        assertFalse(source.select(6, 0))
        assertEquals(0, ZeekrMediaProtocol.call(car.client!!, ZeekrMediaProtocol.CLIENT, 1) { it.readInt() })
    }

    @Test fun mediaCenterRestartRegistersAgainAndOldCallbacksLoseAuthority() {
        val car = FakeCar()
        var sends = 0
        val source = ZeekrMediaSource(car.context, { sends++; true }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        val oldClient = car.client!!
        car.disconnected()
        idle()
        assertEquals(0, ZeekrMediaProtocol.call(oldClient, ZeekrMediaProtocol.CLIENT, 1) { it.readInt() })
        car.connected()
        idle()
        assertEquals(2, car.registrations)
        assertEquals(0, ZeekrMediaProtocol.call(oldClient, ZeekrMediaProtocol.CLIENT, 1) { it.readInt() })
        assertEquals(1, ZeekrMediaProtocol.call(car.client!!, ZeekrMediaProtocol.CLIENT, 1) { it.readInt() })
        assertEquals(1, sends)
        source.close()
        idle()
    }

    @Test fun disconnectBeforeRegistrationCompletesDiscardsTheLateToken() {
        val car = FakeCar(delayed = true)
        val source = ZeekrMediaSource(car.context, { fail("stale command"); false }, Handler(Looper.getMainLooper()))
        source.start()
        idle()
        source.deactivate()
        source.close()
        idle()
        car.deliver()
        idle()
        assertEquals(1, car.unregistered)
        assertEquals(0, car.claims)
    }

    @Test fun missingServiceAndRejectedRegistrationLeaveOrdinaryPlaybackAlone() {
        val absent = FakeCar(present = false)
        val source = ZeekrMediaSource(absent.context, { fail("no control"); false }, Handler(Looper.getMainLooper()))
        source.start()
        source.update(CarPlayNowPlaying(playing = true), null)
        idle()
        assertEquals(0, absent.binds)
        source.close()
        val rejected = FakeCar(status = 403)
        val denied = ZeekrMediaSource(rejected.context, { fail("no control"); false }, Handler(Looper.getMainLooper()))
        denied.start()
        denied.update(CarPlayNowPlaying(playing = true), null)
        idle()
        assertEquals(0, rejected.claims)
        assertEquals(0, rejected.metadataUpdates)
        denied.close()
        idle()
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    /** Simulates the observed wire contract, including asynchronous registration callbacks. */
    private class FakeCar(val delayed: Boolean = false, present: Boolean = true, val status: Int = 200,
        val initFailures: Int = 0, val initFailureReason: Int = 312,
        val directPresent: Boolean = false, val directDescriptor: String = ZeekrMediaProtocol.SUPPORT,
        val playAccepted: Boolean = true, val delayDirectConnection: Boolean = false) {
        var coreConnection: ServiceConnection? = null
        var directConnection: ServiceConnection? = null
        var initCalls = 0
        var currentPackage = "previous.player"
        val identity = Binder()
        var client: IBinder? = null
        var listener: IBinder? = null
        var tokenReceiver: IBinder? = null
        var mediaReceiver: IBinder? = null
        var registrations = 0
        var binds = 0
        var removedListeners = 0
        var claims = 0
        var unregistered = 0
        var metadataUpdates = 0
        var title: String? = null
        var state = -1
        var position = -1L
        var sources = intArrayOf()
        var artwork: Uri? = null
        val media = ZeekrCallbackBinder(ZeekrMediaProtocol.MEDIA) { code, data, reply ->
            assertSame(identity, data.readStrongBinder())
            when (code) {
                5 -> unregistered++
                6 -> claims++
                7 -> {
                    val info = data.readStrongBinder()!!
                    title = ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 2) { it.readString() }
                    state = ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 11) { it.readInt() }
                    artwork = ZeekrMediaProtocol.call(info, ZeekrMediaProtocol.INFO, 16) {
                        if (it.readInt() == 0) null else Uri.CREATOR.createFromParcel(it)
                    }
                    metadataUpdates++
                }
                8 -> sources = data.createIntArray()!!
                9 -> { assertEquals(6, data.readInt()); currentPackage = context.packageName }
                11 -> position = data.readLong()
                44 -> {
                    reply!!.writeNoException(); reply.writeString(currentPackage)
                    return@ZeekrCallbackBinder true
                }
                else -> fail("unexpected media code $code")
            }
            reply!!.writeNoException()
            if (code in listOf(5, 6, 7)) reply.writeInt(if (code == 6 && !playAccepted) 0 else 1)
            true
        }
        val core = ZeekrCallbackBinder(ZeekrMediaProtocol.CORE) { code, data, reply ->
            when (code) {
                1 -> {
                    assertArrayEquals(arrayOf("mediacenter"), data.createStringArray())
                    initCalls++
                    if (initCalls <= initFailures) disconnected(initFailureReason) else connected()
                }
                2 -> listener = data.readStrongBinder()
                3 -> { assertSame(listener, data.readStrongBinder()); removedListeners++ }
                6 -> {
                    route(data, reply!!)
                    return@ZeekrCallbackBinder true
                }
                else -> fail("unexpected core code $code")
            }
            reply!!.writeNoException()
            true
        }
        val direct = ZeekrCallbackBinder(directDescriptor) { code, data, reply ->
            assertEquals(3, code)
            route(data, reply!!)
            true
        }
        private fun route(data: Parcel, reply: Parcel) {
            assertEquals(1, data.readInt())
            assertEquals("mediacenter", data.readString())
            assertEquals("ZeekrMediaCenterAPI", data.readString())
            val method = data.readString()
            val parameter = ZeekrMediaProtocol.readStringMessage(data.createByteArray())
            assertNull(data.createByteArray())
            val callback = data.readStrongBinder()!!
            if (status == 200) when (method) {
                "mediaCenterRegisterMusicNew" -> {
                    assertEquals(context.packageName, parameter)
                    registrations++
                    client = ZeekrMediaProtocol.call(callback, ZeekrMediaProtocol.REGISTER_CALLBACK, 2) { it.readStrongBinder() }
                    tokenReceiver = ZeekrMediaProtocol.call(callback, ZeekrMediaProtocol.REGISTER_CALLBACK, 1) { it.readStrongBinder() }
                }
                "mediaCenterUpdateMediaContentNew" -> {
                    mediaReceiver = callback
                    if (!delayed) deliver()
                }
                "mediaCenterRegisterExtMediaClient" -> Unit
                else -> fail("unexpected route $method")
            }
            reply.writeNoException(); reply.writeInt(1); reply.writeInt(status)
        }
        val context: Context
        init {
            val base = RuntimeEnvironment.getApplication() as Context
            val manager = mock(PackageManager::class.java)
            `when`(manager.resolveService(any(Intent::class.java), eq(0))).thenAnswer {
                val intent = it.getArgument<Intent>(0)
                val available = if (intent.component?.packageName == "com.zeekr.mediacenter") directPresent else present
                if (available) ResolveInfo() else null
            }
            `when`(manager.resolveContentProvider(anyString(), anyInt())).thenAnswer {
                base.packageManager.resolveContentProvider(it.getArgument<String>(0), it.getArgument<Int>(1))
            }
            `when`(manager.getPackagesForUid(anyInt())).thenReturn(arrayOf(base.packageName))
            `when`(manager.getXml(anyString(), anyInt(), nullable(ApplicationInfo::class.java))).thenAnswer {
                base.packageManager.getXml(it.getArgument<String>(0), it.getArgument<Int>(1), it.getArgument<ApplicationInfo?>(2))
            }
            context = object : ContextWrapper(base) {
                override fun getPackageManager(): PackageManager = manager
                override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                    binds++
                    if (intent.component!!.packageName == "com.zeekr.coreservice") coreConnection = connection
                    if (intent.component!!.packageName == "com.zeekr.mediacenter") {
                        directConnection = connection
                        if (!delayDirectConnection) connection.onServiceConnected(intent.component!!, direct)
                    } else connection.onServiceConnected(intent.component!!, core)
                    return true
                }
                override fun unbindService(connection: ServiceConnection) = Unit
            }
        }
        fun connected() = ZeekrMediaProtocol.command(listener!!, ZeekrMediaProtocol.SERVICE_CALLBACK, 1) { it.writeString("mediacenter") }
        fun disconnected(reason: Int = 303) = ZeekrMediaProtocol.command(listener!!, ZeekrMediaProtocol.SERVICE_CALLBACK, 2) {
            it.writeString("mediacenter"); it.writeInt(reason)
        }
        fun deliver() {
            ZeekrMediaProtocol.command(tokenReceiver!!, ZeekrMediaProtocol.TOKEN_CALLBACK, 1) { it.writeStrongBinder(identity) }
            ZeekrMediaProtocol.command(mediaReceiver!!, ZeekrMediaProtocol.MEDIA_CALLBACK, 2) { it.writeStrongBinder(media) }
        }
        fun focus(name: String) = ZeekrMediaProtocol.command(client!!, ZeekrMediaProtocol.CLIENT, 25) { it.writeString(name) }
    }
}
