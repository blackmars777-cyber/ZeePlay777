package com.shilapi.xcertplay

import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One controller's data source; background adoption keeps the controller's providers alive. */
internal class ZeekrVehicleData(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val connect: (Context, () -> Unit) -> ZeekrSensorClient = { app, lost -> ReflectiveZeekrSensorClient(app, lost) },
) : VehicleDataSource {
    private val state = ZeekrVehicleState()
    private var worker: ScheduledExecutorService? = null
    private var closed = false
    private var generation = 0L
    @Volatile private var askedAt = 0L
    @Volatile private var speedWanted = false
    private var client: ZeekrSensorClient? = null
    private var ticket = 0L
    private var connectedAt = 0L
    private var attempts = 0
    private var retryAt = 0L
    private var lastSummaryAt = 0L
    private var lastPollAt = 0L

    private val speedClients = mutableSetOf<Any>()
    override fun speed(): VehicleSpeedSource = object : VehicleSpeedSource {
        private val token = Any()
        @Volatile private var active = false
        override fun start() {
            synchronized(this@ZeekrVehicleData) {
                active = true
                speedClients.add(token)
                speedWanted = speedClients.isNotEmpty()
                state.clearSamples()
            }
            warmUp()
        }
        override fun stop() {
            synchronized(this@ZeekrVehicleData) {
                active = false
                speedClients.remove(token)
                speedWanted = speedClients.isNotEmpty()
                if (!speedWanted) state.clearSamples()
            }
        }
        override fun drain() = if (!active) null else touch().let { state.drain(clock()) }
        override fun status(): String = state.status()
    }
    override val battery = VehicleStatusProvider { touch(); state.battery(clock()) }
    override fun parked(): Boolean? { touch(); return state.parked(clock()) }

    override fun warmUp() = ensureWorker(resetFailures = true)

    @Synchronized private fun ensureWorker(resetFailures: Boolean) {
        askedAt = clock()
        if (closed) return
        worker?.let { running ->
            if (resetFailures) {
                val active = generation
                running.execute { if (current(active)) { attempts = 0; retryAt = 0 } }
            }
            return
        }
        val activeGeneration = ++generation
        attempts = 0
        retryAt = 0
        lastPollAt = 0
        worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-zeekr-vehicle").apply { isDaemon = true }
        }.also { executor -> executor.scheduleWithFixedDelay({
            // Never let a vendor exception cancel future reads without invalidating the cache.
            try { poll(activeGeneration) } catch (error: Exception) {
                fail(activeGeneration, error)
            } catch (error: LinkageError) {
                fail(activeGeneration, error)
            }
        }, 0, 250, TimeUnit.MILLISECONDS) }
    }

    private fun touch() = ensureWorker(resetFailures = false)
    private fun current(activeGeneration: Long) = synchronized(this) { generation == activeGeneration && worker != null }

    private fun poll(activeGeneration: Long) {
        if (!current(activeGeneration)) return
        val now = clock()
        if (!speedWanted && now - askedAt > IDLE_NS) { retire(activeGeneration); return }
        if (lastPollAt > 0 && now-lastPollAt > 5_000_000_000L) {
            state.invalidate(ticket)
            runCatching { client?.close() }
            client = null
            attempts = 0
            retryAt = 0
        }
        lastPollAt = now
        if (client == null) {
            if (attempts >= 3 || now < retryAt) return
            ticket = state.begin(now)
            val openingTicket = ticket
            attempts++
            client = connect(context) { state.invalidate(openingTicket) }
            connectedAt = now
        }
        val readingClient = client ?: return
        if (!readingClient.ready()) {
            if (clock() - connectedAt >= CONNECT_TIMEOUT_NS) fail(activeGeneration, IllegalStateException("CarSensor not ready"))
            return
        }
        // A service-loss callback retires the epoch immediately, including during a slow read.
        if (readingClient.lost) { fail(activeGeneration, IllegalStateException("CarSensor disconnected")); return }
        state.connected(ticket)
        val speed = readingClient.read(SPEED)
        val gear = readingClient.read(GEAR)
        val battery = readingClient.read(BATTERY)
        val afterRead = clock() // New events may arrive during RPC, so do not compare with a pre-read clock.
        if (!current(activeGeneration) || readingClient.lost) { state.invalidate(ticket); return }
        state.publish(ticket, speed, gear, battery, afterRead, speedWanted)
        attempts = 0
        if (afterRead - lastSummaryAt >= 30_000_000_000L) {
            Log.i(TAG, state.status())
            lastSummaryAt = afterRead
        }
    }

    private fun fail(activeGeneration: Long, error: Throwable) {
        if (!current(activeGeneration)) return
        state.invalidate(ticket)
        runCatching { client?.close() }
        client = null
        retryAt = clock() + 2_000_000_000L
        var cause = error
        while (cause is java.lang.reflect.InvocationTargetException && cause.targetException != null) cause = cause.targetException
        Log.w(TAG, "CarSensor unavailable (${cause.javaClass.simpleName}); attempt=$attempts/3")
    }

    private fun retire(activeGeneration: Long) {
        val retiring = synchronized(this) {
            if (!current(activeGeneration)) return
            generation++
            state.invalidate(ticket)
            worker
        }
        // Keep the retiring worker visible until cleanup finishes; warmUp never waits on vendor RPC.
        runCatching { client?.close() }
        client = null
        synchronized(this) { if (worker === retiring) worker = null }
        retiring?.shutdown()
    }

    /** Terminal teardown; invalidation is immediate and vendor cleanup stays on the worker. */
    @Synchronized internal fun close() {
        if (closed) return
        closed = true
        generation++
        state.invalidate(ticket)
        val retiring = worker ?: return
        retiring.execute {
            runCatching { client?.close() }
            client = null
        }
        worker = null
        retiring.shutdown()
    }

    companion object {
        private const val TAG = "DiPlay-ZeekrVehicle"
        private const val IDLE_NS = 90_000_000_000L
        private const val CONNECT_TIMEOUT_NS = 5_000_000_000L
        const val SPEED = 291504647
        const val GEAR = 289408000
        const val BATTERY = 1610612780
    }
}

internal interface ZeekrSensorClient : AutoCloseable {
    val lost: Boolean
    fun ready(): Boolean
    fun read(id: Int): ZeekrSensorValue?
}

/** Reuses only the public car API; no proprietary SDK binary or vehicle-property write is bundled. */
internal class ReflectiveZeekrSensorClient(context: Context, private val disconnected: () -> Unit) : ZeekrSensorClient {
    @Volatile override var lost = false
        private set
    @Volatile private var bound = false
    @Volatile private var closed = false
    private var manager: Any? = null
    private var listener: Any? = null
    private var supported = emptySet<Int>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!closed) bound = true }
        override fun onServiceDisconnected(name: ComponentName) { bound = false; lost = true; disconnected() }
        override fun onBindingDied(name: ComponentName) { bound = false; lost = true; disconnected() }
    }
    private val car = Class.forName("android.car.Car")
        .getMethod("createCar", Context::class.java, ServiceConnection::class.java)
        .invoke(null, context, connection)?.let { created ->
            try { invoke(created, "connect"); created } catch (error: Throwable) {
                runCatching { invoke(created, "disconnect") }
                throw error
            }
        } ?: error("android.car service missing")

    override fun ready(): Boolean {
        if (!bound || closed || lost || invoke(car, "isConnected") != true) return false
        if (manager != null) return true
        val next = invoke(car, "getCarManager", "sensor") ?: error("CarSensor manager missing")
        supported = (invoke(next, "getSupportedSensors") as IntArray).toSet()
        check(ZeekrVehicleData.SPEED in supported && ZeekrVehicleData.GEAR in supported) { "Motion sensors unsupported" }
        val type = Class.forName("android.car.hardware.CarSensorManager\$OnSensorChangedListener")
        val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "KrPlayReadOnlyVehicleObserver"
                else -> null // SDK owns the cache; the worker reads it without blocking iAP2/UI.
            }
        }
        listener = observer
        manager = next // Retain it even when registration fails, so cleanup unregisters partial subscriptions.
        val rate = next.javaClass.getField("SENSOR_RATE_NORMAL").getInt(null)
        for (id in listOf(ZeekrVehicleData.SPEED, ZeekrVehicleData.GEAR)) {
            check(invoke(next, "registerListener", observer, id, rate) == true) { "Sensor $id rejected" }
        }
        if (ZeekrVehicleData.BATTERY in supported) {
            val accepted = runCatching { invoke(next, "registerListener", observer, ZeekrVehicleData.BATTERY, rate) == true }.getOrDefault(false)
            if (!accepted) supported = supported - ZeekrVehicleData.BATTERY
        }
        return true
    }

    override fun read(id: Int): ZeekrSensorValue? {
        if (closed || lost || !bound || id !in supported) return null
        val event = invoke(manager ?: return null, "getLatestSensorEvent", id) ?: return null
        val type = event.javaClass
        val timestamp = type.getField("timestamp").getLong(event)
        val availability = runCatching { type.getField("availability").getInt(event) }.getOrNull()
        val number = if (id == ZeekrVehicleData.GEAR) {
            (type.getField("intValues").get(event) as IntArray).firstOrNull()
        } else (type.getField("floatValues").get(event) as FloatArray).firstOrNull()
        return ZeekrSensorValue(timestamp, number, availability)
    }

    override fun close() {
        if (closed) return
        closed = true
        bound = false
        val current = manager
        if (current != null && listener != null) runCatching { invoke(current, "unregisterListener", listener!!) }
        invoke(car, "disconnect")
        manager = null
        listener = null
    }

    private fun invoke(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size &&
            it.parameterTypes.zip(args).all { (type, arg) -> type.isInstance(arg) || type == Int::class.javaPrimitiveType && arg is Int } }
        return method.invoke(target, *args)
    }
}
