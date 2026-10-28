package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioOutputHealthTest {
    @Test fun continuousPacketsWithZeroWritesReportOnceAndRecoverOnlyAfterOutput() {
        val health = AudioOutputHealth()
        health.input(0)
        health.input(1_000_000_000)
        health.input(2_000_000_000)
        assertEquals("stalled", health.transition(2_000_000_000))
        assertNull(health.transition(2_500_000_000))
        // Going idle does not falsely report recovery.
        assertNull(health.transition(10_000_000_000))
        health.input(10_000_000_000)
        assertNull(health.transition(10_000_000_000))
        health.output(10_010_000_000)
        assertEquals("recovered", health.transition(10_010_000_000))
        assertNull(health.transition(10_020_000_000))
    }

    @Test fun shortOrIdlePromptsAndRegularPcmWritesNeverTriggerAStall() {
        val health = AudioOutputHealth()
        health.input(0)
        assertNull(health.transition(3_000_000_000))
        health.input(10_000_000_000)
        assertNull(health.transition(10_000_000_000))
        repeat(10) { i ->
            val now = 10_000_000_000 + i * 500_000_000L
            health.input(now)
            health.output(now)
            assertNull(health.transition(now))
        }
    }
}
