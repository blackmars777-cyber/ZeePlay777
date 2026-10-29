package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationFocusSettingsScreenshotsTest {
    @Test fun independentNavigationFocusRendersWithGlobalFocusOffAtLargeFont() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveAudioFocusEnabled(activity, false)
        AirPlayPersistence.saveNavigationPromptAudioFocus(activity, true)
        try {
            for ((name, widthDp, tag, densityDpi) in listOf(
                Layout("compact-zh", 320, "zh-CN", 160),
                Layout("full-en-hdpi", 960, "en-US", 320),
                Layout("compact-ar-rtl", 320, "ar-SA", 320),
            )) {
                val locale = Locale.forLanguageTag(tag)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    this.densityDpi = densityDpi
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val controls = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = config.layoutDirection
                    setBackgroundColor(0xff101316.toInt())
                }
                DiPlayActivity::class.java.getDeclaredMethod("audioFocusControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, controls)
                val switches = descendants(controls).filterIsInstance<Switch>().toList()
                val global = switches.single {
                    it.contentDescription == activity.getString(R.string.contrib_audio_home_toggle_audio_focus)
                }
                val navigation = switches.single {
                    it.contentDescription == activity.getString(R.string.settings_navigation_prompt_audio_focus)
                }
                assertFalse(global.isChecked)
                assertTrue(navigation.isChecked)
                assertEquals(View.VISIBLE, (navigation.parent.parent as View).visibility)
                assertNotSame(global.parent, navigation.parent)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                controls.layout(0, 0, width, controls.measuredHeight)
                assertTrue(navigation.height > 0)
                val image = Bitmap.createBitmap(width, controls.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    controls.draw(Canvas(image))
                    val directory = File("build/reports/navigation-focus-ui").apply { mkdirs() }
                    File(directory, "$name.png").outputStream().use {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }
        } finally { activity.finish() }
    }

    private data class Layout(val name: String, val widthDp: Int, val tag: String, val densityDpi: Int)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
