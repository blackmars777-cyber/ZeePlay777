package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [ZeekrVehicleSettingsTest.NoWrite::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZeekrVehicleSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun profile() {
        org.robolectric.shadows.ShadowBuild.setModel("Zeekr DHU for arm64")
        ReflectionHelpers.setStaticField(android.os.Build::class.java, "DISPLAY",
            "zeekr_dhu-userdebug 12 SQ3A.220705.003.A1 eng.buildf.20260902.185825 test-keys")
    }
    @After fun reset() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h700dp-xhdpi")
    fun compactNativeVehicleControls() = render("compact-zh")

    @Test @Config(qualifiers = "en-w1000dp-h700dp-xhdpi")
    fun fullNativeVehicleControls() = render("full-en")

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h700dp-xhdpi")
    fun arabicNativeVehicleControls() = render("compact-ar-rtl")

    private fun render(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = activity.resources.configuration.layoutDirection
                setBackgroundColor(0xff0c1119.toInt())
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "advancedVehicleData",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val labels = children(content).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(labels.contains(activity.getString(R.string.settings_native_vehicle_description)))
            assertFalse(labels.contains(activity.getString(R.string.vehicle_data_mode)))
            assertFalse(labels.contains(activity.getString(R.string.probe_adb_and_vehicle_data)))
            val width = activity.resources.displayMetrics.widthPixels
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            val controls = children(content).filterIsInstance<Switch>().toList()
            assertEquals(2, controls.size)
            for (control in controls) {
                assertTrue(control.width > 0 && control.height > 0)
                assertTrue(control.left >= 0 && control.right <= (control.parent as View).width)
            }
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphone(activity))
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphone(activity))
            val image = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(image))
                val directory = File("build/reports/zeekr-vehicle-ui").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            controls[0].isChecked = true
            controls[1].isChecked = true
            assertTrue(com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphone(activity))
            assertTrue(com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphone(activity))
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParked(activity))
        } finally { activity.finish() }
    }

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    @Implements(Settings.System::class)
    class NoWrite {
        companion object { @JvmStatic @Implementation fun canWrite(context: android.content.Context) = false }
    }
}
