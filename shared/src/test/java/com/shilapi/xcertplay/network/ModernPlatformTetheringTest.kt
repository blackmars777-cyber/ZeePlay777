package com.shilapi.xcertplay.network

import android.net.TetheringManager
import android.os.ResultReceiver
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, shadows = [ModernPlatformTetheringTest.Tethering::class])
class ModernPlatformTetheringTest {
    @Test fun android12UsesProjectionEntryPointWithoutConnectivityBinderOrAdb() {
        Tethering.type = null
        var result = -1
        PlatformTetheringStart.start(RuntimeEnvironment.getApplication(), object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) { result = resultCode }
        }, {})
        assertEquals(0, Tethering.type)
        assertEquals(0, result)
    }

    @Implements(TetheringManager::class)
    class Tethering {
        @Implementation
        fun startTethering(type: Int, executor: Executor, callback: TetheringManager.StartTetheringCallback) {
            Tethering.type = type
            executor.execute { callback.onTetheringStarted() }
        }
        companion object { var type: Int? = null }
    }
}
