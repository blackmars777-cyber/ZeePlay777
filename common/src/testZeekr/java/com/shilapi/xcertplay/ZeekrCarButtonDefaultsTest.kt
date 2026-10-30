package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ZeekrCarButtonDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }

    @After fun cleanup() = resetPreferences()

    @Test fun freshAndBlankLabelsUseZeekrWhileCustomSettingsArePreserved() {
        assertEquals("Zeekr", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, "")
        assertEquals("Zeekr", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
        val custom = byteArrayOf(1, 2, 3)
        AirPlayPersistence.saveCustomAirPlayIcon(context, custom)
        assertArrayEquals(custom, AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes())
    }

    @Test fun carButtonAndPreviewUseThePinnedZeekrLogo() {
        val carIcon = context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        val preview = context.resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }
        assertArrayEquals(carIcon, preview)
        assertEquals("a8bb59ce487dd30f6784f31dbbaf64b22974dbb3a2b071e6917ca00f0caf23cf", MessageDigest.getInstance("SHA-256").digest(carIcon)
            .joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
