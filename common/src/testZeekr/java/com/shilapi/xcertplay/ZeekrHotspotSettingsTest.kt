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
@Config(sdk = [32], shadows = [ZeekrHotspotSettingsTest.NoWrite::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZeekrHotspotSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun installService() {
        val info = ApplicationInfo().apply {
            packageName = "com.aptiv.wifi"
            flags = ApplicationInfo.FLAG_SYSTEM
            enabled = true
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName
            applicationInfo = info
            services = arrayOf(ServiceInfo().apply {
                packageName = info.packageName
                name = "com.aptiv.wifi.wifimanager.WifiServiceManager"
                applicationInfo = info
                enabled = true
                exported = true
            })
        })
    }
    @After fun reset() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h700dp-xhdpi")
    fun compactCardUsesVehicleServiceWithoutRequestingWriteSettings() = render("compact-zh")

    @Test @Config(qualifiers = "en-w1000dp-h700dp-xhdpi")
    fun fullCardUsesVehicleServiceWithoutRequestingWriteSettings() = render("full-en")

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h700dp-xhdpi")
    fun arabicCardUsesVehicleServiceWithoutRequestingWriteSettings() = render("compact-ar-rtl")

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
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "systemHotspotSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val labels = children(content).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(labels.contains(activity.getString(R.string.settings_hotspot_vehicle_service_detected)))
            assertTrue(labels.contains(activity.getString(R.string.settings_auto_car_hotspot_vehicle_description)))
            assertFalse(labels.contains(activity.getString(R.string.settings_write_settings_allow)))
            assertFalse(labels.contains(activity.getString(R.string.settings_write_settings_missing)))
            val width = activity.resources.displayMetrics.widthPixels
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            val control = children(content).filterIsInstance<Switch>().single()
            assertTrue(control.width > 0 && control.height > 0)
            assertTrue(control.left >= 0 && control.right <= (control.parent as View).width)
            val image = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(image))
                val directory = File("build/reports/aptiv-hotspot-ui").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
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
