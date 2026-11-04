package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.setup.SetupGuide
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

/** Native view previews only: no CarPlay connection, backup listener, or export is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN-w1600dp-h1000dp-mdpi", shadows = [ZeekrAuxiliaryPagesTest.NoBackupNetwork::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZeekrAuxiliaryPagesTest {
    private var screen: DiPlayActivity? = null
    private var appearanceKey = "light"
    private var originalApplicationFlags: Int? = null
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun reset() {
        screen?.finish()
        RuntimeEnvironment.setFontScale(1f)
        originalApplicationFlags?.let { context.applicationInfo.flags = it }
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun fullLightLargeFont() = capture(AppAppearance.LIGHT)
    @Test fun fullDarkLargeFont() = capture(AppAppearance.DARK)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h760dp-xhdpi")
    fun compactLargeFont() = capture(AppAppearance.LIGHT)

    @Test @Config(qualifiers = "ar-ldrtl-w400dp-h760dp-xhdpi")
    fun arabicLargeFont() = capture(AppAppearance.LIGHT)

    @Test fun backupQrFitsNarrowAndFullWindows() {
        for (width in listOf(240, 320, 400, 900, 1600)) {
            val compact = width < 850
            val side = ZeekrHmiTokens.qrSideDp(width, compact)
            val inset = if (compact) ZeekrHmiTokens.PAGE_INSET_COMPACT_DP else ZeekrHmiTokens.PAGE_INSET_FULL_DP
            assertTrue(side in 1..ZeekrHmiTokens.QR_MAX_SIDE_DP)
            for (density in listOf(1f, 2f, 3f)) {
                val occupied = (side + 2 * ZeekrHmiTokens.QR_FRAME_INSET_DP +
                    2 * ZeekrHmiTokens.CARD_PADDING_DP + 2 * inset) * density
                assertTrue("QR frame overflows $width dp at density $density", occupied <= width * density)
            }
        }
    }

    private fun capture(appearance: AppAppearance) {
        appearanceKey = appearance.key
        // The library test manifest lacks the mobile app's supportsRtl=true declaration.
        originalApplicationFlags = context.applicationInfo.flags
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        RuntimeEnvironment.setFontScale(1.3f)
        AirPlayPersistence.saveAppAppearance(context, appearance)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection")).setup().get()
        screen = activity
        snapshot("connection")
        ReflectionHelpers.setField(activity, "page", "setup")
        for (step in SetupGuide.STEP_CAR..SetupGuide.STEP_DONE) {
            ReflectionHelpers.setField(activity, "setupStep", step)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            snapshot("setup-${step + 1}")
        }
        ReflectionHelpers.setField(activity, "page", "settings")
        for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.CONNECTION,
            SettingsCategory.DISPLAY, SettingsCategory.AUDIO, SettingsCategory.NAVIGATION,
            SettingsCategory.DIAGNOSTICS, SettingsCategory.LANGUAGE, SettingsCategory.ABOUT)) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSettingsCategory",
                ReflectionHelpers.ClassParameter.from(SettingsCategory::class.java, category))
            snapshot("settings-${category.name.lowercase()}")
        }
        ReflectionHelpers.setField(activity, "page", "backup")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertNull("Preview must not open a network listener", ReflectionHelpers.getField<Any?>(activity, "backupServer"))
        snapshot("backup-no-network")
    }

    private fun snapshot(page: String) {
        val activity = screen!!
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.layoutDirection = activity.resources.configuration.layoutDirection
        val metrics = activity.resources.displayMetrics
        // Settle the one adaptive-height pass for translated controls that share a row.
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        }
        assertEquals(activity.resources.configuration.layoutDirection, root.layoutDirection)
        for (button in children(root).filterIsInstance<Button>().filter { it !is Switch }) {
            val textLayout = button.layout ?: continue
            if (button.parent is LinearLayout && (button.parent as LinearLayout).orientation == LinearLayout.VERTICAL) {
                assertTrue("Clipped $page control: ${button.text}",
                    textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            }
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val locale = activity.resources.configuration.locales[0].language
            val directory = File("build/reports/zeekr-auxiliary-ui").apply { mkdirs() }
            File(directory, "${metrics.widthPixels}x${metrics.heightPixels}-$locale-$appearanceKey-$page.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }

    @Implements(className = "com.shilapi.xcertplay.backup.SettingsBackupServer\$Companion", isInAndroidSdk = false)
    class NoBackupNetwork {
        @Implementation fun addressCandidates(preferredPrefix: String?): List<String> = emptyList()
    }
}
