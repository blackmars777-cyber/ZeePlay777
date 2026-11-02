package com.shilapi.xcertplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ZeekrVehicleDataTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("condition did not become true")
    }

    @Test fun workerFeedsSpeedSocAndParkThenDropsThemImmediatelyOnConnectionLoss() {
        val clock = AtomicLong(50_000_000_000L)
        lateinit var lose: () -> Unit
        val client = Client(clock)
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _, callback -> lose = callback; client }
        try {
            source.warmUp()
            await { source.parked() == true }
            assertEquals(55.0, source.battery.snapshot()!!.batteryPercent, 0.0)
            val motion = source.speed()
            motion.start()
            await { motion.drain() != null }
            client.lost = true
            lose()
            assertNull(source.parked())
            assertNull(source.battery.snapshot())
            assertNull(motion.drain())
        } finally { source.close() }
        assertTrue(client.closed.await(4, TimeUnit.SECONDS))
    }

    @Test fun closeDoesNotBlockOnVendorCleanupAndLateReadsCannotRestorePark() {
        val clock = AtomicLong(50_000_000_000L)
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val client = object : Client(clock) {
            override fun close() { cleanupEntered.countDown(); allowCleanup.await(4, TimeUnit.SECONDS); super.close() }
        }
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _, _ -> client }
        try {
            source.warmUp()
            await { source.parked() == true }
            source.close()
            assertTrue(cleanupEntered.await(4, TimeUnit.SECONDS))
            assertNull(source.parked()) // Must not acquire a lock held by blocked vendor cleanup.
            assertNull(source.battery.snapshot())
        } finally { allowCleanup.countDown(); source.close() }
        assertTrue(client.closed.await(4, TimeUnit.SECONDS))
    }

    @Test fun invalidAvailabilityRetractsAnAlreadyPublishedParkAndBattery() {
        val clock = AtomicLong(50_000_000_000L)
        val client = Client(clock)
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _, _ -> client }
        try {
            source.warmUp()
            await { source.parked() == true }
            client.availability = 1
            await { source.parked() == null && source.battery.snapshot() == null }
        } finally { source.close() }
        assertTrue(client.closed.await(4, TimeUnit.SECONDS))
    }

    @Test fun anOldControllerStoppingItsSpeedLeaseDoesNotDisableTheReplacement() {
        val clock = AtomicLong(50_000_000_000L)
        val client = Client(clock)
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _, _ -> client }
        try {
            val old = source.speed()
            val next = source.speed()
            old.start()
            await { old.drain() != null }
            next.start()
            old.stop()
            assertNull(old.drain())
            await { next.drain() != null }
            next.stop()
        } finally { source.close() }
        assertTrue(client.closed.await(4, TimeUnit.SECONDS))
    }

    @Test fun startupFailuresAreBoundedAndAnExplicitNewSessionCanTryAgain() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val client = Client(AtomicLong(1L))
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), System::nanoTime) { _, _ ->
            if (attempts.incrementAndGet() <= 3) throw SecurityException("read denied")
            client
        }
        try {
            source.warmUp()
            await { attempts.get() == 3 }
            Thread.sleep(2250)
            assertEquals(3, attempts.get())
            assertNull(source.parked()) // Ordinary getters cannot restart a denied retry loop.
            source.warmUp() // A new controller/session explicitly requests recovery.
            await { source.battery.snapshot()?.batteryPercent == 55.0 }
            assertNull(source.parked()) // The recovered connection has only an old cached P.
            assertEquals(4, attempts.get())
        } finally { source.close() }
        assertTrue(client.closed.await(4, TimeUnit.SECONDS))
    }

    @Test fun observedFirmwareStatusAndIntegerRangeReachTheRealProvider() {
        val clock = AtomicLong(50_000_000_000L)
        val client = object : Client(clock) {
            override fun read(id: Int) = ZeekrSensorValue(clock.get(), when(id) {
                ZeekrVehicleData.GEAR -> 3
                ZeekrVehicleData.SPEED -> 72f
                ZeekrVehicleData.RANGE -> 277
                else -> 53f
            }, -1)
        }
        val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _,_ -> client }
        val speed = source.speed()
        try {
            speed.start()
            await { source.battery.snapshot()?.rangeKm == 277 }
            await { speed.drain()?.samples?.singleOrNull()?.metersPerSecond == 20.0 }
            assertEquals(53.0, source.battery.snapshot()!!.batteryPercent, 0.0)
            assertNull(source.parked())
            client.lost = true
            await { source.battery.snapshot() == null }
        } finally { speed.stop(); source.close() }
    }

    @Test fun deniedChannelsDoNotEraseIndependentVehicleData() {
        for (denied in listOf(ZeekrVehicleData.BATTERY, ZeekrVehicleData.SPEED)) {
            val clock = AtomicLong(50_000_000_000L)
            val client = object : Client(clock) {
                override fun read(id: Int): ZeekrSensorValue {
                    if (id == denied) throw SecurityException("channel denied")
                    return ZeekrSensorValue(clock.get(), when(id) {
                        ZeekrVehicleData.GEAR -> 3
                        ZeekrVehicleData.SPEED -> 72f
                        ZeekrVehicleData.RANGE -> 277
                        else -> 53f
                    }, -1)
                }
            }
            val source = ZeekrVehicleData(RuntimeEnvironment.getApplication(), clock::get) { _,_ -> client }
            val speed = source.speed()
            try {
                speed.start()
                if (denied == ZeekrVehicleData.BATTERY) {
                    await { speed.drain() != null }
                    assertNull(source.battery.snapshot())
                } else {
                    await { source.battery.snapshot()?.rangeKm == 277 }
                    assertNull(speed.drain())
                    assertNull(source.parked())
                }
            } finally { speed.stop(); source.close() }
        }
    }

    internal open class Client(private val clock: AtomicLong) : ZeekrSensorClient {
        @Volatile override var lost = false
        @Volatile var availability = 0
        val closed = CountDownLatch(1)
        override fun ready() = !lost
        override fun read(id: Int) = ZeekrSensorValue(clock.get(), when (id) {
            ZeekrVehicleData.GEAR -> 0
            ZeekrVehicleData.SPEED -> 0f
            else -> 55f
        }, availability)
        override fun close() { closed.countDown() }
    }
}
