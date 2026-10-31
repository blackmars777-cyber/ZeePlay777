package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed, saved-configuration-only protocol observed in the 007 7.2.3 Aptiv service. */
internal object VehicleHotspotStart {
    internal const val PACKAGE = "com.aptiv.wifi"
    internal val COMPONENT = ComponentName(PACKAGE, "$PACKAGE.wifimanager.WifiServiceManager")
    internal const val DESCRIPTOR = "com.aptiv.wifi.wifimanager.IAptivWIfiConnectionManager"
    private const val START_TRANSACTION = 16
    private const val REQUEST_MILLIS = 4_000L

    fun available(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(COMPONENT, 0)
        val app = service.applicationInfo
        service.enabled && app.enabled && service.exported && service.permission.isNullOrEmpty() &&
            app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    fun request(context: Context, deadline: Long, isCancelled: () -> Boolean, log: (String) -> Unit): VehicleHotspotRequestResult {
        if (!available(context)) return VehicleHotspotRequestResult.UNAVAILABLE
        val end = minOf(deadline, System.nanoTime() + REQUEST_MILLIS * 1_000_000)
        fun stopped(): VehicleHotspotRequestResult? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> VehicleHotspotRequestResult.CANCELLED
            System.nanoTime() >= end -> VehicleHotspotRequestResult.TIMED_OUT
            else -> null
        }
        stopped()?.let { return it }
        val app = context.applicationContext
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (name == COMPONENT) binder.set(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { binder.set(null) }
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
            override fun onBindingDied(name: ComponentName) { connected.countDown() }
        }
        var attemptedBind = false
        return try {
            // Public current-user bind only. No cross-user/system identity or SDK jar is needed.
            attemptedBind = true
            if (!app.bindService(Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE)) {
                log("car hotspot Aptiv: bind rejected")
                return VehicleHotspotRequestResult.UNAVAILABLE
            }
            log("car hotspot Aptiv: waiting for service")
            while (!connected.await(25, TimeUnit.MILLISECONDS)) stopped()?.let { return it }
            stopped()?.let { return it }
            val remote = binder.get() ?: return VehicleHotspotRequestResult.REJECTED.also {
                log("car hotspot Aptiv: service returned no binder")
            }
            val done = CountDownLatch(1)
            val answer = AtomicReference(VehicleHotspotRequestResult.REJECTED)
            // Native Binder transact has no cancellation API. Bound our wait and never fall back to
            // another mutation after a timeout; an in-flight request may still finish in the service.
            Thread({
                try {
                    if (!isCancelled() && System.nanoTime() < end) {
                        answer.set(if (start(remote, { isCancelled() || System.nanoTime() >= end }))
                            VehicleHotspotRequestResult.ACCEPTED else VehicleHotspotRequestResult.REJECTED)
                    }
                } catch (error: Exception) {
                    log("car hotspot Aptiv: request rejected (${error.javaClass.simpleName})")
                } finally { done.countDown() }
            }, "diplay-aptiv-hotspot-request").apply { isDaemon = true; start() }
            while (!done.await(25, TimeUnit.MILLISECONDS)) stopped()?.let { return it }
            stopped()?.let { return it }
            answer.get().also { log("car hotspot Aptiv: request=$it; waiting for actual AP state") }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            VehicleHotspotRequestResult.CANCELLED
        } catch (error: Exception) {
            log("car hotspot Aptiv: bind/request unavailable (${error.javaClass.simpleName})")
            VehicleHotspotRequestResult.REJECTED
        } finally {
            if (attemptedBind) runCatching { app.unbindService(connection) }
        }
    }

    internal fun start(binder: IBinder, isCancelled: () -> Boolean): Boolean {
        if (isCancelled() || binder.interfaceDescriptor != DESCRIPTOR || isCancelled()) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (isCancelled() || !binder.transact(START_TRANSACTION, data, reply, 0)) return false
            reply.readException()
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
