package com.shihab.diplay.zeekr.vehicleprobe

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ZeekrVehicleProbeLifecycleTest {
    @Test fun recordsRawSamplesAndDisconnectsWithoutAnyCarPlayOrVehicleWrite() {
        val sampled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val car = FakeCar()
        var finalReport = ""
        val probe = ZeekrVehicleProbe(RuntimeEnvironment.getApplication(), { report ->
            if (report.contains("property 291504647 PERF_VEHICLE_SPEED m/s status=0") &&
                report.contains("sensor 1610612747 ZEEKR_VEHICLE_SPEED 单位未知（UNKNOWN） CACHED（缓存读数） raw=[1.5]")) {
                sampled.countDown()
            }
            if (report.contains("STOPPED")) { finalReport = report; finished.countDown() }
        }, { _, connection -> car.apply { listener = connection } })
        try {
            probe.start()
            assertTrue("probe sampled both managers", sampled.await(8, TimeUnit.SECONDS))
        } finally { probe.close() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, car.disconnections)
        assertEquals(0, car.writes)
        assertTrue(finalReport.contains("raw=1.5"))
        assertTrue(finalReport.contains("NOT_EXPOSED（未暴露：不支持或被权限过滤）"))
        assertTrue(finalReport.contains("历史"))
        // Late service callbacks after stop cannot re-open the probe.
        car.listener.onServiceConnected(ComponentName("test", "CarService"), Binder())
        assertEquals(1, car.disconnections)
    }

    @Test fun missingCarLibraryProducesAReportAndStillStopsCleanly() {
        val failed = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val probe = ZeekrVehicleProbe(RuntimeEnvironment.getApplication(), { report ->
            if (report.contains("ClassNotFoundException")) failed.countDown()
            if (report.contains("STOPPED")) stopped.countDown()
        }, { _, _ -> throw ClassNotFoundException("android.car.Car") })
        try {
            probe.start()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
        } finally { probe.close() }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
    }

    @Test fun closingDuringConnectionReleasesTheCreatedClient() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val car = FakeCar()
        val probe = ZeekrVehicleProbe(RuntimeEnvironment.getApplication(), { report ->
            if (report.contains("STOPPED")) stopped.countDown()
        }, { _: Context, connection: ServiceConnection ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            car.apply { listener = connection }
        })
        probe.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        probe.close()
        release.countDown()
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertEquals(1, car.disconnections)
    }

    class FakeCar {
        lateinit var listener: ServiceConnection
        var disconnections = 0
        var writes = 0
        fun connect() { listener.onServiceConnected(ComponentName("test", "CarService"), Binder()) }
        fun disconnect() { disconnections++ }
        fun getCarManager(name: String): Any = if (name == "property") FakeProperty() else FakeSensor()
        @Suppress("unused") fun setProperty(value: Any) { writes++ }
    }
    class FakeProperty {
        fun getPropertyList(): List<FakeConfig> = listOf(FakeConfig())
        @Suppress("UNUSED_PARAMETER") fun getProperty(id: Int, area: Int) = FakeValue()
    }
    class FakeConfig {
        fun getPropertyId() = 291504647
        fun getAreaIds() = intArrayOf(0)
        fun getChangeMode() = 2
        fun getMinSampleRate() = 1f
        fun getMaxSampleRate() = 10f
    }
    class FakeValue {
        fun getStatus() = 0
        fun getTimestamp() = SystemClock.elapsedRealtimeNanos()
        fun getValue() = 1.5f
    }
    class FakeSensor {
        fun getSupportedSensors() = intArrayOf(1610612747)
        @Suppress("UNUSED_PARAMETER") fun getLatestSensorEvent(id: Int) = FakeEvent()
    }
    class FakeEvent {
        @JvmField val timestamp = SystemClock.elapsedRealtimeNanos()
        @JvmField val floatValues = floatArrayOf(1.5f)
        @JvmField val intValues = intArrayOf()
        @JvmField val longValues = longArrayOf()
    }
}
