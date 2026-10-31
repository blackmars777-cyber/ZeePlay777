package com.shilapi.xcertplay.network

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ModernTetheringStartTest {
    interface Callback {
        fun onTetheringStarted()
        fun onTetheringFailed(error: Int)
    }

    class Manager(private val denied: Boolean = false) {
        var type: Int? = null
        var callback: Callback? = null
        fun startTethering(type: Int, executor: Executor, callback: Callback) {
            if (denied) throw SecurityException("denied")
            this.type = type
            executor.execute { this.callback = callback }
        }
    }

    @Test fun projectionOverloadStartsWifiAndForwardsAsynchronousResults() {
        val manager = Manager()
        val results = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        ModernTetheringStart.invoke(manager, Callback::class.java, results::add, logs::add)
        assertEquals(0, manager.type)
        assertTrue(results.isEmpty())
        manager.callback!!.onTetheringStarted()
        manager.callback!!.onTetheringFailed(14)
        assertEquals(listOf(0, 14), results)
        assertTrue(logs.last().contains("code=14"))
        assertEquals(manager.callback, manager.callback)
        assertFalse(manager.callback!!.equals(Any()))
    }

    @Test fun servicePermissionRejectionReachesTheExistingAdbFallback() {
        try {
            ModernTetheringStart.invoke(Manager(denied = true), Callback::class.java, { fail() }, {})
            fail("Expected the service rejection")
        } catch (error: InvocationTargetException) {
            assertTrue(error.targetException is SecurityException)
        }
    }

    @Test fun absentOverloadIsNotReportedAsStarted() {
        try {
            ModernTetheringStart.invoke(Any(), Callback::class.java, { fail() }, {})
            fail("Expected a missing method")
        } catch (_: NoSuchMethodException) {
        }
    }

    @Test fun malformedFailureCannotBecomeSuccess() {
        val manager = Manager()
        var result = 0
        ModernTetheringStart.invoke(manager, Callback::class.java, { result = it }, {})
        manager.callback!!.onTetheringFailed(0)
        assertTrue(result > 0)
    }
}
