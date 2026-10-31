package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Parcel
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE,
    shadows = [VehicleHotspotStartTest.ApState::class, VehicleHotspotStartTest.NoWritePermission::class])
class VehicleHotspotStartTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { install(); ApState.on = false }

    private fun install(system: Boolean = true, exported: Boolean = true, enabled: Boolean = true, permission: String? = null) {
        val info = ApplicationInfo().apply {
            packageName = VehicleHotspotStart.PACKAGE
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            this.enabled = enabled
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName
            applicationInfo = info
            services = arrayOf(ServiceInfo().apply {
                packageName = info.packageName
                name = VehicleHotspotStart.COMPONENT.className
                applicationInfo = info
                this.exported = exported
                this.enabled = enabled
                this.permission = permission
            })
        })
    }

    class Service(private val descriptor: String = VehicleHotspotStart.DESCRIPTOR, var accepted: Boolean = true) : Binder() {
        val calls = mutableListOf<Int>()
        var afterStart: () -> Unit = {}
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls += code
            assertEquals(16, code)
            assertEquals(0, flags)
            data.enforceInterface(descriptor)
            assertEquals(0, data.dataAvail())
            afterStart()
            reply!!.writeNoException()
            reply.writeInt(if (accepted) 1 else 0)
            return true
        }
    }

    private inner class BoundContext(val remote: Service, val connect: Boolean = true) : ContextWrapper(app) {
        var bound = 0
        var unbound = 0
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(VehicleHotspotStart.COMPONENT, intent.component)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            bound++
            connection = conn
            if (connect) conn.onServiceConnected(intent.component!!, remote)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbound++; assertSame(connection, conn) }
    }

    @Test fun publicStartupUsesVehicleServiceWithNoWriteSettingsOrAdb() {
        val service = Service().apply { afterStart = { ApState.on = true } }
        val context = BoundContext(service)
        assertFalse(Settings.System.canWrite(context))
        assertEquals(CarHotspotTethering.Result.READY,
            CarHotspotTethering.enable(context, { false }, 500) {})
        assertEquals(listOf(16), service.calls)
        assertEquals(1, context.bound)
        assertEquals(1, context.unbound)
    }

    @Test fun existingHotspotIsReusedWithoutBinding() {
        ApState.on = true
        val context = BoundContext(Service())
        assertEquals(CarHotspotTethering.Result.READY,
            CarHotspotTethering.enable(context, { false }, 500) {})
        assertEquals(0, context.bound)
    }

    @Test fun acceptedRequestStillRequiresObservedApState() {
        val context = BoundContext(Service())
        assertEquals(CarHotspotTethering.Result.TIMED_OUT,
            CarHotspotTethering.enable(context, { false }, 80) {})
        assertEquals(1, context.unbound)
    }

    @Test fun serviceRejectionIsNotSuccessOrAPermissionPrompt() {
        val context = BoundContext(Service(accepted = false))
        assertEquals(CarHotspotTethering.Result.FAILED,
            CarHotspotTethering.enable(context, { false }, 500) {})
        assertEquals(1, context.unbound)
    }

    @Test fun lateServiceConnectionAfterTimeoutCannotStartHotspot() {
        val context = BoundContext(Service(), connect = false)
        assertEquals(VehicleHotspotRequestResult.TIMED_OUT,
            VehicleHotspotStart.request(context, System.nanoTime() + 30_000_000, { false }) {})
        context.connection!!.onServiceConnected(VehicleHotspotStart.COMPONENT, context.remote)
        assertTrue(context.remote.calls.isEmpty())
        assertEquals(1, context.unbound)
    }

    @Test fun wrongDescriptorAndCancellationNeverTransact() {
        val wrong = Service("wrong.interface")
        assertFalse(VehicleHotspotStart.start(wrong) { false })
        assertTrue(wrong.calls.isEmpty())
        val service = Service()
        assertFalse(VehicleHotspotStart.start(service) { true })
        assertTrue(service.calls.isEmpty())
    }

    @Test fun inFlightBinderTimeoutUnbindsWithoutRetrying() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = Service().apply { afterStart = { entered.countDown(); release.await(2, TimeUnit.SECONDS); Unit } }
        val context = BoundContext(service)
        try {
            assertEquals(VehicleHotspotRequestResult.TIMED_OUT,
                VehicleHotspotStart.request(context, System.nanoTime() + 150_000_000, { false }) {})
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(16), service.calls)
            assertEquals(1, context.unbound)
        } finally { release.countDown() }
    }

    @Test fun unavailableOrUntrustedServiceIsNotCalled() {
        install(system = false); assertFalse(VehicleHotspotStart.available(app))
        install(exported = false); assertFalse(VehicleHotspotStart.available(app))
        install(enabled = false); assertFalse(VehicleHotspotStart.available(app))
        install(permission = "signature.permission"); assertFalse(VehicleHotspotStart.available(app))
        shadowOf(app.packageManager).removePackage(VehicleHotspotStart.PACKAGE)
        assertFalse(VehicleHotspotStart.available(app))
    }

    @Implements(className = "com.shilapi.xcertplay.network.CarHotspotStatus", isInAndroidSdk = false)
    class ApState {
        @Implementation fun isEnabled(context: Context): Boolean = on
        companion object { @Volatile var on = false }
    }
    @Implements(Settings.System::class)
    class NoWritePermission {
        companion object { @JvmStatic @Implementation fun canWrite(context: Context): Boolean = false }
    }
}
