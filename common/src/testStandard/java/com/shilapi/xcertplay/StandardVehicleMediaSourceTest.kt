package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StandardVehicleMediaSourceTest {
    @Test fun standardCarButtonKeepsTheUpstreamDefaultName() {
        assertEquals("BYD", AirPlayPersistence.DEFAULT_OEM_LABEL)
    }

    @Test fun standardBuildHasNoOemMediaSourceOrZeekrClasses() {
        assertNull(createVehicleMediaSource(RuntimeEnvironment.getApplication(), Handler(Looper.getMainLooper()),
            current = { error("standard build must not query an OEM source") },
            send = { error("standard build must not send OEM controls") },
        ))
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.shilapi.xcertplay.ZeekrMediaSource")
        }
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.shilapi.xcertplay.ZeekrMediaIntentService")
        }
    }
}
