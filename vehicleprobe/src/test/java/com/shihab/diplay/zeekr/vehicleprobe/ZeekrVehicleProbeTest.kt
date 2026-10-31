package com.shihab.diplay.zeekr.vehicleprobe

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class ZeekrVehicleProbeTest {
    @Test fun invalidAndMissingDataNeverBecomeZeroOrPark() {
        assertTrue(ProbeReport.sample(null, 1, 2).contains("NO_DATA"))
        assertTrue(ProbeReport.sample(Float.NaN, 1, 2).contains("INVALID"))
        assertTrue(ProbeReport.sample(floatArrayOf(Float.POSITIVE_INFINITY), 1, 2).contains("INVALID"))
        assertFalse(ProbeReport.sample(null, 1, 2).contains("raw=0"))
        assertTrue(ProbeReport.sample(0f, 0, 2).contains("UNKNOWN_TIMESTAMP"))
        assertTrue(ProbeReport.sample(0f, 3, 2).contains("UNKNOWN_TIMESTAMP"))
    }

    @Test fun repeatedCacheReadsRetainTheirOriginalAge() {
        val timestamp = 1_000_000_000L
        assertTrue(ProbeReport.sample(0f, timestamp, timestamp + 1).contains("RECENT_SAMPLE"))
        val old = ProbeReport.sample(0f, timestamp, timestamp + 3_000_000_000L)
        assertTrue(old.contains("OLD_SAMPLE"))
        assertTrue(old.contains("ageMs=3000"))
    }

    @Test fun wrappedPermissionFailureRemainsDistinctFromUnsupportedApi() {
        assertTrue(ProbeReport.error(InvocationTargetException(SecurityException("denied")))
            .startsWith("PERMISSION_DENIED"))
        assertTrue(ProbeReport.error(NoSuchMethodException("getProperty")).startsWith("ERROR"))
    }

    @Test fun longSessionsProduceABoundedExportWithTruncationNotice() {
        val report = ProbeReport()
        repeat(1000) { report.add("sample $it " + "x".repeat(1000)) }
        val text = report.text()
        assertTrue(text.contains("已丢弃旧记录：700"))
        assertFalse(text.contains("sample 699 "))
        assertTrue(text.contains("sample 999 "))
        assertTrue(text.length < 185_000)
    }
}
