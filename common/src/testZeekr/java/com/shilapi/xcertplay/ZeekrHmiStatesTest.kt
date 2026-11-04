package com.shilapi.xcertplay

import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.time.Duration
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** Visual state fixtures; no sample vehicle telemetry or simulated controls enter production UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN-w1600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZeekrHmiStatesTest {
    private var activity: DiPlayActivity? = null
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() {
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
    }
    @Test fun lightReferenceCanvasAndStates() = capture(AppAppearance.LIGHT)
    @Test fun darkReferenceCanvasAndStates() = capture(AppAppearance.DARK)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h760dp-xhdpi")
    fun compactHomeLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        capture(AppAppearance.DARK)
    }

    @Test @Config(qualifiers = "zh-rCN-w1600dp-h1000dp-mdpi")
    fun fullHomeLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        capture(AppAppearance.LIGHT)
    }

    @Test @Config(qualifiers = "ar-ldrtl-w400dp-h760dp-xhdpi")
    fun arabicHomeLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        capture(AppAppearance.LIGHT)
    }

    @Test @Config(shadows = [HotspotOff::class])
    fun hotspotWarningKeepsItsActionsInsideTheWirelessCard() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        AirPlayPersistence.saveWirelessHotspotMode(context, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
        com.shilapi.xcertplay.network.CarHotspotSettings.setEnabled(context, false)
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().get().also { activity = it }
        // The screenshot retains the real authentication error; separately verify the known hotspot state.
        snapshot(screen, AppAppearance.LIGHT, "hotspot-off-authentication-unavailable")
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val title = ReflectionHelpers.getField<TextView>(screen, "status")
        assertEquals(screen.getString(R.string.car_hotspot_is_off), title.text.toString())
        val buttons = children(screen.window.decorView).filterIsInstance<Button>().toList()
        val hotspot = buttons.single { it.text == screen.getString(R.string.open_car_hotspot_settings) }
        val choose = buttons.single { it.text == screen.getString(R.string.choose_iphone) }
        assertSame(hotspot.parent, choose.parent)
        snapshot(screen, AppAppearance.LIGHT, "hotspot-off")
        val a = IntArray(2).also(hotspot::getLocationOnScreen)
        val b = IntArray(2).also(choose::getLocationOnScreen)
        assertEquals(a[0], b[0])
        assertTrue(b[1] >= a[1] + hotspot.height)
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h760dp-xhdpi", shadows = [HotspotOff::class])
    fun compactHotspotKeepsItsSettingsAction() = hotspotWarningKeepsItsActionsInsideTheWirelessCard()

    @Implements(className = "com.shilapi.xcertplay.network.CarHotspotStatus", isInAndroidSdk = false)
    class HotspotOff {
        @Implementation fun isEnabled(context: Context): Boolean? = false
    }

    private fun capture(appearance: AppAppearance) {
        AirPlayPersistence.saveAppAppearance(context, appearance)
        AirPlayPersistence.saveWirelessHotspotMode(context, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P)
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().get().also { activity = it }
        // Capture the real source-only authentication error before state fixtures hide it.
        snapshot(screen, appearance, "authentication-unavailable")
        // Gallery fixtures isolate visual states from the source-only build's missing authentication assets.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        val feedback = ReflectionHelpers.getField<ZeekrConnectionFeedback>(screen, "zeekrFeedback")
        val primary = ReflectionHelpers.getField<Button>(screen, "connectButton")
        val disconnect = ReflectionHelpers.getField<Button>(screen, "disconnectButton")
        for (phase in ZeekrConnectionFeedback.Phase.entries) {
            feedback.show(phase)
            feedback.title.text = screen.getString(when (phase) {
                ZeekrConnectionFeedback.Phase.EMPTY -> R.string.setup_iphone_none
                ZeekrConnectionFeedback.Phase.READY -> R.string.ready_when_you_are
                ZeekrConnectionFeedback.Phase.CONNECTING -> R.string.connecting_to_your_iphone
                ZeekrConnectionFeedback.Phase.CONNECTED -> R.string.carplay_connected
                ZeekrConnectionFeedback.Phase.ERROR -> R.string.setup_needs_attention
            })
            primary.text = screen.getString(if (phase in listOf(ZeekrConnectionFeedback.Phase.CONNECTED,
                    ZeekrConnectionFeedback.Phase.CONNECTING)) R.string.open_carplay else R.string.connect_phone)
            disconnect.visibility = if (phase == ZeekrConnectionFeedback.Phase.CONNECTED) View.VISIBLE else View.GONE
            val problem = ReflectionHelpers.getField<TextView?>(screen, "homeLastProblem")
            problem?.visibility = if (phase == ZeekrConnectionFeedback.Phase.ERROR) View.VISIBLE else View.GONE
            problem?.setText(R.string.wireless_permissions)
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue(primary.width > 0)
            snapshot(screen, appearance, phase.name.lowercase())
        }
    }

    private fun snapshot(screen: DiPlayActivity, appearance: AppAppearance, state: String) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        val primary = ReflectionHelpers.getField<Button>(screen, "connectButton")
        val buttons = children(root).filterIsInstance<Button>().toList()
        val choose = buttons.single { it.text == screen.getString(R.string.choose_iphone) }
        val usb = buttons.single { it.text == screen.getString(R.string.connect_with_usb) }
        val settings = buttons.single { it.text == screen.getString(R.string.settings) }
        assertSame("iPhone selection belongs to the wireless card", primary.parent, choose.parent)
        assertSame("USB and Settings stay together", usb.parent, settings.parent)
        val brand = children(root).filterIsInstance<TextView>().single { it.text == screen.getString(R.string.diplay) }
        assertEquals("The brand must not wrap or clip in a narrow header", 1, brand.layout.lineCount)
        if (screen.resources.configuration.screenWidthDp >= 850) {
            val hero = children(root).filterIsInstance<TextView>().single { it.text == screen.getString(R.string.a_familiar_drive) }
            fun position(view: View) = IntArray(2).also(view::getLocationOnScreen)
            assertTrue(position(hero)[1] + hero.height <= position(primary.parent as View)[1])
            assertTrue(position(usb)[0] >= position(primary)[0] + primary.width)
            assertTrue(position(choose)[1] > position(primary)[1])
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val folder = File("build/reports/zeekr-hmi-states").apply { mkdirs() }
            val locale = screen.resources.configuration.locales[0].language
            val font = (screen.resources.configuration.fontScale * 100).toInt()
            val name = "${metrics.widthPixels}x${metrics.heightPixels}-${locale}-font${font}-${appearance.key}-$state"
            File(folder, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            if (root is android.widget.ScrollView && state.endsWith("authentication-unavailable") &&
                root.getChildAt(0).height > root.height) {
                // Robolectric's ScrollView shadow records scroll positions without moving native
                // child drawing. Render the same native content at its bottom viewport offset.
                val content = root.getChildAt(0)
                val bottomCanvas = Canvas(bitmap)
                root.background?.draw(bottomCanvas)
                val save = bottomCanvas.save()
                bottomCanvas.translate(0f, -(content.height - root.height).toFloat())
                content.draw(bottomCanvas)
                bottomCanvas.restoreToCount(save)
                File(folder, "$name-bottom.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
