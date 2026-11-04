package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZeekrSettingsUiTest {
    private var activity: DiPlayActivity? = null
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() {
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        context.getSharedPreferences("zeekr_interaction", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun allCategoriesAndSearchExcludeBydEvenWithOldPreferences() {
        WheelZoomSettings.setJoystick(context, true)
        WheelZoomSettings.setSiriLongPressOnly(context, true)
        val screen = open()
        val forbidden = Regex("BYD|DiLink|比亚迪|比亞迪", RegexOption.IGNORE_CASE)
        for (category in SettingsCategory.entries) {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, category))
            val texts = children(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse("$category: ${texts.filter { forbidden.containsMatchIn(it) }}", texts.any { forbidden.containsMatchIn(it) })
        }
        val results = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")
        assertFalse(results.any { forbidden.containsMatchIn(it.title) })
        assertTrue(results.any { it.title == screen.getString(R.string.settings_zeekr_media) && it.category == SettingsCategory.VEHICLE })
        assertFalse(results.any { it.title == screen.getString(R.string.wheel_joystick) })
    }

    @Test fun setupUsesZeekrInsteadOfDilinkProfiles() {
        val screen = open()
        for (method in listOf("setupCarStep", "setupFeaturesStep")) {
            val content = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
            ReflectionHelpers.callInstanceMethod<Unit>(screen, method,
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val labels = children(content).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse(labels.any { Regex("BYD|DiLink", RegexOption.IGNORE_CASE).containsMatchIn(it) })
            assertTrue(labels.any { it == screen.getString(R.string.settings_zeekr_interaction) || it.contains("Zeekr") })
        }
    }

    @Test fun controlsPersistTheirOwnSettings() {
        val screen = open()
        val card = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "zeekrInteractionControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, card))
        val controls = children(card).filterIsInstance<Switch>().toList()
        assertEquals(3, controls.size)
        assertTrue(controls.all { it.isChecked })
        controls.forEach { it.isChecked = false }
        assertFalse(ZeekrInteractionSettings.mediaEnabled(screen))
        assertFalse(ZeekrInteractionSettings.autoSelect(screen))
        assertFalse(ZeekrInteractionSettings.controlsEnabled(screen))
    }

    @Test @Config(qualifiers = "zh-rCN-w1600dp-h1000dp-mdpi")
    fun referenceLight() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        capture("reference-light", 1f)
    }

    @Test @Config(qualifiers = "zh-rCN-w1600dp-h1000dp-mdpi")
    fun referenceDark() = capture("reference-dark", 1f)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h760dp-xhdpi")
    fun compactLargeFont() = capture("compact-zh")

    @Test @Config(qualifiers = "en-w1400dp-h900dp-xhdpi")
    fun fullLargeFont() = capture("full-en")

    @Test @Config(qualifiers = "ar-ldrtl-w400dp-h760dp-xhdpi")
    fun arabicLargeFont() = capture("compact-ar-rtl")

    @Test @Config(qualifiers = "zh-rCN-w1400dp-h900dp-xhdpi")
    fun fullLightLargeFont() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        capture("full-zh-light")
    }

    private fun capture(name: String, fontScale: Float = 1.3f) {
        RuntimeEnvironment.setFontScale(fontScale)
        val screen = open()
        for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.VEHICLE, SettingsCategory.ADVANCED)) {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, category))
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = screen.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            for (button in children(root).filterIsInstance<android.widget.Button>()) {
                val textLayout = button.layout ?: continue
                if (button.parent is LinearLayout && (button.parent as LinearLayout).orientation == LinearLayout.VERTICAL) {
                    assertTrue("Clipped button: ${button.text}", textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
            }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val folder = File("build/reports/zeekr-settings-ui").apply { mkdirs() }
                File(folder, "$name-${category.name.lowercase()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun open(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().get().also { activity = it }

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(children(view.getChildAt(index)))
    }
}
