package com.shihab.diplay.zeekr.vehicleprobe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.os.Build
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeDashboardUiTest {
    @Test fun confirmedVehicleReadingsWithUnknownAvailabilityAreVisibleAsCachedEvidence() {
        val controller=Robolectric.buildActivity(ZeekrVehicleProbeActivity::class.java).setup()
        val screen=controller.get()
        try {
            val state=ProbeDashboardState(true)
            state.phase(ProbePhase.CONNECTED)
            state.reading(ProbeDashboardSnapshot.SPEED,0.0,44444119615492,50554724119578,-1)
            state.reading(ProbeDashboardSnapshot.GEAR,0.0,50541475976135,50554724119578,-1)
            state.reading(ProbeDashboardSnapshot.BATTERY,53.0,49564468407914,50554724119578,-1)
            state.reading(1610612745,277.0,50187781498093,50554724119578,-1)
            screen.render(state.snapshot())
            val views=descendants(screen.window.decorView).toList()
            assertTrue("confirmed battery must be drawn",views.filterIsInstance<ProbeBatteryRing>().single().percent==53.0)
            assertTrue("confirmed range must be drawn",views.filterIsInstance<TextView>().any { it.text.toString()=="277 km" })
            assertTrue("gear must be decoded",views.filterIsInstance<TextView>().any { it.text.toString().startsWith("P · 驻车") })
            assertEquals(0.0,state.snapshot().speed!!,0.0)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="en-w1280dp-h720dp-land-hdpi")
    fun fullDashboardLargeFont()=capture("full-large",1920,1080)

    @Test @Config(qualifiers="en-w320dp-h720dp-port-xhdpi")
    fun compactDashboardLargeFont()=capture("compact-large",640,1440)

    @Test @Config(qualifiers="en-w1280dp-h720dp-land-hdpi")
    fun confirmedCacheDashboardLargeFont()=capture("confirmed-cache-large",1920,1080,confirmedCache=true)

    @Test fun rangeHasNoGuessedKilometersUntilSourceAndUnitsAreChosen() {
        val controller=Robolectric.buildActivity(ZeekrVehicleProbeActivity::class.java).setup()
        val screen=controller.get()
        try {
            val source=ProbeDashboardSnapshot.RANGE_IDS.first()
            val data=ProbeDashboardSnapshot(ProbePhase.CONNECTED,false,2,
                mapOf(source to ProbeValue(283000.0,1,2,0,ProbeRead.VALUE)))
            screen.render(data)
            assertFalse(descendants(screen.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="283 km" })
            screen.getSharedPreferences("probe-dashboard",0).edit().putInt("range-source",source)
                .putString("range-unit","m").putString("range-firmware",Build.DISPLAY).commit()
            screen.render(data)
            assertTrue(descendants(screen.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="283 km" })
            screen.render(data.copy(phase=ProbePhase.DISCONNECTED,values=emptyMap()))
            assertFalse(descendants(screen.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="283 km" })
        } finally { controller.pause().stop().destroy() }
    }

    private fun capture(name:String,width:Int,height:Int,confirmedCache:Boolean=false) {
        RuntimeEnvironment.setFontScale(1.3f)
        val controller=Robolectric.buildActivity(ZeekrVehicleProbeActivity::class.java).setup()
        val screen=controller.get()
        try {
            org.robolectric.util.ReflectionHelpers.setField(screen,"permissionCount",2)
            org.robolectric.util.ReflectionHelpers.setField(screen,"latest","界面测试数据 · 非车辆实测")
            org.robolectric.util.ReflectionHelpers.setField(screen,"collecting",true)
            fun sample(raw:Double,event:Long)=ProbeValue(raw,event,if(confirmedCache)50554724119578 else 20,
                if(confirmedCache)-1 else 0,ProbeRead.VALUE)
            screen.render(ProbeDashboardSnapshot(ProbePhase.CONNECTED,true,2,
                mapOf(ProbeDashboardSnapshot.SPEED to sample(if(confirmedCache)0.0 else 88.0,if(confirmedCache)44444119615492 else 10),
                    ProbeDashboardSnapshot.BATTERY to sample(if(confirmedCache)53.0 else 55.0,if(confirmedCache)49564468407914 else 10),
                    ProbeDashboardSnapshot.GEAR to sample(if(confirmedCache)0.0 else 3.0,if(confirmedCache)50541475976135 else 10),
                    ProbeDashboardSnapshot.RANGE_IDS.first() to sample(if(confirmedCache)277.0 else 283.0,if(confirmedCache)50187781498093 else 10)),
                if(confirmedCache)emptyList() else (0..30).map { ProbeTrendPoint(it*1_000_000_000L,40.0+it*1.6) },
                note=if(confirmedCache)"实车日志回放 · 界面验证，非实时车辆状态" else "界面测试数据 · 非车辆实测"))
            val root=screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
            val buttons=descendants(root).filterIsInstance<Button>().filter { it.text.toString() in listOf("停止探测","导出","详情") }.toList()
            assertEquals(3,buttons.size)
            assertEquals(1,buttons.map { it.height }.toSet().size)
            for(button in buttons) {
                assertTrue(button.height>=ProbeUi.dp(screen,56))
                val rect=android.graphics.Rect()
                assertTrue(button.getGlobalVisibleRect(rect))
                assertTrue(rect.height()>=button.height)
            }
            if(name=="full-large") {
                val chart=descendants(root).filterIsInstance<ProbeSpeedTrend>().single()
                val visible=android.graphics.Rect()
                assertTrue(chart.getGlobalVisibleRect(visible))
                assertEquals(chart.height,visible.height())
            }
            assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text.toString()==if(confirmedCache)"277 km" else "283 km" })
            val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(image))
                val output=File("build/reports/probe-dashboard/$name.png");output.parentFile!!.mkdirs()
                output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } finally { image.recycle() }
        } finally { controller.pause().stop().destroy() }
    }
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
}
