package com.shihab.diplay.zeekr.vehicleprobe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt

/** Native, Chinese-only vehicle dashboard. No DiPlay or CarPlay runtime dependencies. */
class ZeekrVehicleProbeActivity : Activity() {
    private var probe: ZeekrVehicleProbe? = null
    private var latest = ""
    private var permissionCount = 0
    private var generation = 0
    private var collecting = false
    private var snapshot = ProbeDashboardSnapshot()
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var permissionButton: Button
    private lateinit var primary: Button
    private lateinit var export: Button
    private lateinit var detailButton: Button
    private lateinit var gauge: ProbeSpeedGauge
    private lateinit var ring: ProbeBatteryRing
    private lateinit var batteryState: TextView
    private lateinit var rangeNumber: TextView
    private lateinit var rangeState: TextView
    private lateinit var gearState: TextView
    private val gearCells = mutableListOf<TextView>()
    private lateinit var trend: ProbeSpeedTrend
    private lateinit var detailPanel: LinearLayout
    private lateinit var log: TextView
    private lateinit var events: TextView
    private var details = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastFrameAt = 0L
    private val healthCheck = object : Runnable {
        override fun run() {
            if (!collecting) return
            if (android.os.SystemClock.elapsedRealtime() - lastFrameAt > 6000) {
                render(snapshot.copy(phase=ProbePhase.DISCONNECTED,values=emptyMap(),note="采样暂无响应；旧读数已隐藏，可停止并导出诊断"))
            }
            ui.postDelayed(this,1000)
        }
    }
    private val preferences by lazy { getSharedPreferences("probe-dashboard", MODE_PRIVATE) }
    private fun dp(value: Number) = ProbeUi.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.zeekr_probe_title)
        window.statusBarColor = ProbeUi.BG
        window.navigationBarColor = ProbeUi.BG
        latest = savedInstanceState?.getString("report").orEmpty()
        if (latest.isNotEmpty()) snapshot = restore(savedInstanceState!!)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ProbeUi.BG) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        val wide = resources.configuration.screenWidthDp >= 720
        val large = resources.configuration.fontScale > 1.2f
        val header = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(if(wide)24 else 16),dp(16),dp(if(wide)24 else 16),dp(12))
        }
        val titles = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titles.addView(ProbeUi.label(this,"车辆数据探针",if(wide)24f else 20f,bold=true))
        status=ProbeUi.label(this,"●  准备就绪",11f,ProbeUi.LIME)
        titles.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        permissionButton=ProbeUi.button(this,"车辆权限") { showPermissions() }
        header.addView(permissionButton,LinearLayout.LayoutParams(dp(if(wide)150 else 112),dp(64)))
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=false;clipToPadding=false }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(if(wide)24 else 16),dp(4),dp(if(wide)24 else 16),dp(8)) }
        note=ProbeUi.label(this,snapshot.note,12f,ProbeUi.MUTED)
        body.addView(note,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
        gauge=ProbeSpeedGauge(this)
        val gaugeCard=card().apply { addView(gauge,LinearLayout.LayoutParams(-1,-1)) }
        val metrics=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val battery=card().apply {
            setPadding(dp(12),dp(12),dp(12),dp(12))
            addView(ProbeUi.label(this@ZeekrVehicleProbeActivity,"电量 / %",14f,ProbeUi.MUTED))
            ring=ProbeBatteryRing(this@ZeekrVehicleProbeActivity)
            addView(ring,LinearLayout.LayoutParams(-1,0,1f))
            batteryState=ProbeUi.label(this@ZeekrVehicleProbeActivity,"等待采样",11f,ProbeUi.MUTED).apply { maxLines=2 }
            addView(batteryState)
        }
        if(wide&&resources.configuration.screenWidthDp>=1100) {
            val title=battery.getChildAt(0)
            battery.removeView(title);battery.removeView(ring);battery.removeView(batteryState)
            battery.orientation=LinearLayout.HORIZONTAL;battery.gravity=Gravity.CENTER_VERTICAL
            battery.addView(ring,LinearLayout.LayoutParams(dp(100),-1).apply { marginEnd=dp(12) })
            val words=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL }
            words.addView(title);words.addView(batteryState,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
            battery.addView(words,LinearLayout.LayoutParams(0,-1,1f))
        }
        val range=card().apply {
            setPadding(dp(16),dp(14),dp(16),dp(14))
            addView(ProbeUi.label(this@ZeekrVehicleProbeActivity,"动态续航",14f,ProbeUi.MUTED))
            rangeNumber=ProbeUi.label(this@ZeekrVehicleProbeActivity,"—",if(wide&&resources.configuration.screenWidthDp<1100)32f else 36f,bold=true).apply { maxLines=1 }
            addView(rangeNumber,LinearLayout.LayoutParams(-1,0,1f))
            rangeState=ProbeUi.label(this@ZeekrVehicleProbeActivity,"点击核对续航来源  ›",11f,ProbeUi.AMBER).apply { maxLines=3 }
            addView(rangeState)
            isClickable=true;isFocusable=true;contentDescription="动态续航，点击核对来源"
            setOnClickListener { showRangeSources() }
        }
        metrics.addView(battery,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(12) })
        metrics.addView(range,LinearLayout.LayoutParams(0,-1,1f))
        val gear=card().apply { setPadding(dp(16),dp(12),dp(16),dp(12)) }
        gear.addView(ProbeUi.label(this,"当前挡位 · 只读",14f,ProbeUi.MUTED))
        val gearRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        for(letter in listOf("P","R","N","D")) {
            val cell=ProbeUi.label(this,letter,30f,ProbeUi.MUTED,true).apply { gravity=Gravity.CENTER }
            gearCells.add(cell)
            gearRow.addView(cell,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(6) })
        }
        gear.addView(gearRow,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(8);bottomMargin=dp(6) })
        gearState=ProbeUi.label(this,"等待挡位信号",11f,ProbeUi.MUTED)
        gear.addView(gearState)
        if(wide) {
            val instruments=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            val height=if(large)300 else 288
            instruments.addView(gaugeCard,LinearLayout.LayoutParams(0,-1,1.05f).apply { marginEnd=dp(16) })
            val right=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            right.addView(metrics,LinearLayout.LayoutParams(-1,dp(if(large)150 else 152)).apply { bottomMargin=dp(16) })
            right.addView(gear,LinearLayout.LayoutParams(-1,0,1f))
            instruments.addView(right,LinearLayout.LayoutParams(0,-1,1f))
            body.addView(instruments,LinearLayout.LayoutParams(-1,dp(height)))
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if(scroll.height>0) {
                    val other=body.paddingTop+body.paddingBottom+note.measuredHeight+dp(16)+dp(16)+dp(132)
                    val usable=(scroll.height-other).coerceIn(dp(270),dp(300))
                    if(instruments.layoutParams.height!=usable) {
                        instruments.layoutParams=instruments.layoutParams.apply { this.height=usable }
                        metrics.layoutParams=metrics.layoutParams.apply { this.height=(usable*.48f).toInt() }
                    }
                }
            }
        } else {
            body.addView(gaugeCard,LinearLayout.LayoutParams(-1,dp(if(large)320 else 284)))
            body.addView(metrics,LinearLayout.LayoutParams(-1,dp(if(large)208 else 180)).apply { topMargin=dp(16) })
            body.addView(gear,LinearLayout.LayoutParams(-1,dp(if(large)160 else 140)).apply { topMargin=dp(16) })
        }
        trend=ProbeSpeedTrend(this)
        body.addView(card().apply { addView(trend,LinearLayout.LayoutParams(-1,-1)) },
            LinearLayout.LayoutParams(-1,dp(if(large)132 else 132)).apply { topMargin=dp(16) })
        detailPanel=card().apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));visibility=View.GONE }
        detailPanel.addView(ProbeUi.label(this,"挡位事件",16f,bold=true))
        events=ProbeUi.label(this,"尚未收到挡位回调",12f,ProbeUi.MUTED)
        detailPanel.addView(events,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(16) })
        detailPanel.addView(ProbeUi.label(this,"原始诊断报告",16f,bold=true))
        log=ProbeUi.label(this,"",12f,ProbeUi.MUTED).apply { setTextIsSelectable(true) }
        detailPanel.addView(ScrollView(this).apply { addView(log) },LinearLayout.LayoutParams(-1,dp(280)).apply { topMargin=dp(8) })
        body.addView(detailPanel,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setPadding(dp(16),dp(12),dp(16),dp(16));gravity=Gravity.CENTER_VERTICAL }
        primary=ProbeUi.button(this,"开始探测",true) { if(collecting)stopProbe() else startProbe() }
        export=ProbeUi.button(this,"导出") { exportReport() }
        detailButton=ProbeUi.button(this,"详情") { details=!details;detailPanel.visibility=if(details)View.VISIBLE else View.GONE;detailButton.text=if(details)"收起" else "详情";log.text=latest
            if(details)detailPanel.post { scroll.smoothScrollTo(0,detailPanel.top) }
            else scroll.smoothScrollTo(0,0) }
        for((button,weight)in listOf(primary to 1.25f,export to 1f,detailButton to .8f)) {
            actions.addView(button,LinearLayout.LayoutParams(0,dp(64),weight).apply { if(button!==detailButton)marginEnd=dp(10) })
        }
        export.contentDescription="导出探测报告"
        root.addView(actions)
        setContentView(root)
        root.requestApplyInsets()
        render(snapshot)
    }

    private fun card()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=ProbeUi.shape(this@ZeekrVehicleProbeActivity,ProbeUi.CARD,border=true);clipToOutline=true }

    private fun startProbe() {
        if(latest.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("新建一份探测报告？")
                .setMessage("当前快照可先导出。新建后，仪表和趋势会从本次采样重新开始。")
                .setPositiveButton("新建探测") { _,_ -> begin() }.setNeutralButton("先导出") { _,_ -> exportReport() }
                .setNegativeButton("取消",null).showCarDialog()
        } else begin()
    }
    private fun begin() {
        probe?.close();generation++;val active=generation
        latest="";collecting=true
        lastFrameAt=android.os.SystemClock.elapsedRealtime()
        ui.removeCallbacks(healthCheck);ui.postDelayed(healthCheck,1000)
        render(ProbeDashboardSnapshot(phase=ProbePhase.CONNECTING,note="正在连接车辆只读接口"))
        probe=ZeekrVehicleProbe(applicationContext,publish={ report -> runOnUiThread {
            if(generation==active&&!isDestroyed) { latest=report;if(details)log.text=report;export.isEnabled=report.isNotEmpty();export.alpha=if(export.isEnabled)1f else .45f }
        } },onSnapshot={ next -> runOnUiThread {
            if(generation==active&&!isDestroyed) { lastFrameAt=android.os.SystemClock.elapsedRealtime();render(if(collecting)next else next.copy(phase=ProbePhase.STOPPED,note="历史快照；重新开始将新建报告")) }
        } }).also { it.start() }
    }
    private fun stopProbe() {
        collecting=false;ui.removeCallbacks(healthCheck);probe?.close();probe=null
        render(snapshot.copy(phase=if(latest.isEmpty())ProbePhase.IDLE else ProbePhase.STOPPED,
            note=if(latest.isEmpty())"请驻车操作；行驶时由乘员采集" else "历史快照；当前不再采集"))
    }
    internal fun render(next: ProbeDashboardSnapshot) {
        snapshot=next
        status.text="●  ${next.phase.label} · 只读"
        status.setTextColor(if(next.phase==ProbePhase.CONNECTED)ProbeUi.LIME else if(next.phase==ProbePhase.ERROR||next.phase==ProbePhase.DISCONNECTED)ProbeUi.AMBER else ProbeUi.MUTED)
        note.text=next.note
        permissionButton.text="权限 $permissionCount/4"
        primary.text=if(collecting)"停止探测" else "开始探测"
        export.isEnabled=latest.isNotEmpty();export.alpha=if(export.isEnabled)1f else .45f
        gauge.snapshot=next
        ring.percent=next.battery;ring.live=next.phase==ProbePhase.CONNECTED&&next.value(ProbeDashboardSnapshot.BATTERY).availability==0
        val batteryValue=next.value(ProbeDashboardSnapshot.BATTERY)
        ring.unknown=next.phase==ProbePhase.CONNECTED&&next.battery!=null&&batteryValue.statusUnknown
        batteryState.setTextColor(if(ring.unknown)ProbeUi.AMBER else ProbeUi.MUTED)
        batteryState.text=if(!next.calibrated&&next.phase==ProbePhase.CONNECTED)"固件未标定，见详情" else if(next.battery!=null) {
            (if(batteryValue.availability==0)"信号可读" else "缓存 · 状态待核对")+"\n"+ageLabel(batteryValue)
        } else batteryValue.stateLabel
        val selected=rangeSource()
        val range=selected?.let { next.value(it).visible?.let { raw -> raw * (if(rangeUnit()=="m") .001 else 1.0) }?.takeIf { value -> value in 0.0..3000.0 } }
        rangeNumber.text=range?.let { "${it.roundToInt()} km" } ?: "—"
        rangeState.text=when {
            range!=null && next.value(selected!!).statusUnknown -> "缓存 · 状态待核对\n"+ageLabel(next.value(selected))
            range!=null -> "实车核对来源 · 点击修改"
            selected!=null -> "来源已保存 · "+next.value(selected).stateLabel
            else -> "待标定 · 点击核对来源  ›"
        }
        rangeState.setTextColor(if(range!=null && !next.value(selected!!).statusUnknown)ProbeUi.BLUE else ProbeUi.AMBER)
        rangeNumber.setTextColor(if(range!=null)ProbeUi.TEXT else ProbeUi.MUTED)
        for((index,cell)in gearCells.withIndex()) {
            val active=next.gear==index
            cell.setTextColor(if(active)ProbeUi.BG else ProbeUi.MUTED)
            cell.background=ProbeUi.shape(this,if(active) { if(next.phase==ProbePhase.CONNECTED) { if(next.value(ProbeDashboardSnapshot.GEAR).availability==0)ProbeUi.LIME else ProbeUi.AMBER } else ProbeUi.MUTED } else ProbeUi.BG,12f)
            cell.contentDescription=listOf("P 驻车","R 倒车","N 空挡","D 前进")[index]+if(active)"，当前挡位" else ""
        }
        gearState.text=next.gear?.let { listOf("P · 驻车","R · 倒车","N · 空挡","D · 前进")[it]+"  /  "+if(next.value(ProbeDashboardSnapshot.GEAR).availability==0)ageLabel(next.value(ProbeDashboardSnapshot.GEAR)) else "缓存 · 状态待核对" }
            ?: if(!next.calibrated&&next.phase==ProbePhase.CONNECTED)"固件未标定，原始挡位见详情" else next.value(ProbeDashboardSnapshot.GEAR).stateLabel
        trend.live=next.phase==ProbePhase.CONNECTED&&next.value(ProbeDashboardSnapshot.SPEED).availability==0
        trend.points=next.speedHistory
        events.text=next.events.takeLast(8).asReversed().joinToString("\n").ifEmpty { "尚未收到挡位回调；注册时的缓存事件会单独记录" }
    }
    private fun ageLabel(value:ProbeValue):String {
        val seconds=((value.receivedNs-value.eventNs).coerceAtLeast(0)/1_000_000_000)
        return when { seconds<1 -> "刚刚变化";seconds<60 -> "$seconds 秒前变化";seconds<3600 -> "${seconds/60} 分钟前变化";else -> "${seconds/3600} 小时前变化" }
    }
    private fun showPermissions() {
        val names=listOf("车速","动力总成 / 挡位","能源 / 电量","车辆基础信息")
        val message=ZeekrVehicleProbe.PERMISSIONS.mapIndexed { i,p -> names[i]+if(checkSelfPermission(p)==android.content.pm.PackageManager.PERMISSION_GRANTED)"：已授权" else "：未授权" }.joinToString("\n")+
            "\n\n权限与接口可读性分开记录；实际读取结果以仪表及报告为准。"
        AlertDialog.Builder(this).setTitle("车辆数据权限").setMessage(message).setPositiveButton("申请权限") { _,_ -> stopProbe();requestPermissions(ZeekrVehicleProbe.PERMISSIONS,723) }.setNegativeButton("返回",null).showCarDialog()
    }
    private fun savedRangeSource():Int?=preferences.getInt("range-source",0).takeIf {
        it in ProbeDashboardSnapshot.RANGE_IDS&&preferences.getString("range-firmware",null)==android.os.Build.DISPLAY
    }
    // Exact-firmware evidence: user confirmed 53% and DTE 277 km on 2026-10-31.
    // Only source and unit are calibrated here. The displayed number always comes from the SDK.
    private fun rangeSource():Int?=savedRangeSource() ?: if(snapshot.calibrated)ProbeDashboardSnapshot.RANGE_IDS.first() else null
    private fun rangeUnit():String=if(savedRangeSource()!=null)preferences.getString("range-unit","km") ?: "km" else "km"
    private fun showRangeSources() {
        val ids=ProbeDashboardSnapshot.RANGE_IDS.filter { snapshot.value(it).visible?.let { n -> n in 0.0..3_000_000.0 }==true }
        if(ids.isEmpty()) { AlertDialog.Builder(this).setTitle("动态续航来源").setMessage("当前没有可读取的续航候选。请先开始探测；若接口未暴露，可导出报告排查。\n\n不会用电量百分比推算续航。").setPositiveButton("知道了",null).showCarDialog();return }
        val labels=ids.map { "候选 ${if(it==ProbeDashboardSnapshot.RANGE_IDS[0])"A" else "B"} · 原始值 ${snapshot.value(it).visible}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择与仪表一致的动态续航").setItems(labels) { _,index ->
            val id=ids[index]
            val raw=snapshot.value(id).visible ?: return@setItems
            val units=listOf("km","m").filter { raw * (if(it=="m") .001 else 1.0) <= 3000 }
            val choices=units.map { unit ->
                val km=raw * (if(unit=="m") .001 else 1.0)
                "${if(unit=="m")"原始单位为米" else "原始单位为公里"} · ${km.roundToInt()} km"
            }.toTypedArray()
            var choice:Int?=null
            val dialog=AlertDialog.Builder(this).setTitle("与仪表核对原始单位")
                .setSingleChoiceItems(choices,-1) { selected,index ->
                    choice=index
                    (selected as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                }
                .setPositiveButton("已核对，保存") { _,_ -> choice?.let { index ->
                    preferences.edit().putInt("range-source",id).putString("range-unit",units[index])
                        .putString("range-firmware",android.os.Build.DISPLAY).apply();render(snapshot)
                } }.setNegativeButton("暂不标定",null).showCarDialog()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false

        }.setNegativeButton("返回",null).showCarDialog()
    }
    private fun AlertDialog.Builder.showCarDialog(): AlertDialog {
        val dialog=create()
        dialog.show()
        dialog.window?.setLayout(dp(minOf(resources.configuration.screenWidthDp-32,640)),android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        for(which in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply { minHeight=dp(56);minimumHeight=dp(56);setTextColor(ProbeUi.LIME) }
        }
        return dialog
    }
    private fun exportReport() {
        if(latest.isEmpty())return
        stopProbe()
        val dir=File(cacheDir,"vehicle-probe").apply { mkdirs() }
        val file=File(dir,"vehicle-probe.txt").apply { writeText("导出快照：采集已停止，以下读数均为历史记录。\n$latest") }
        val uri=FileProvider.getUriForFile(this,"$packageName.vehicle-probe",file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("探测报告",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },"导出探测报告"))
    }
    override fun onResume() {
        super.onResume()
        permissionCount=ZeekrVehicleProbe.PERMISSIONS.count { checkSelfPermission(it)==android.content.pm.PackageManager.PERMISSION_GRANTED }
        permissionButton.text="权限 $permissionCount/4"
    }
    override fun onStop() { stopProbe();super.onStop() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null);super.onDestroy() }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("report",latest);out.putBoolean("calibrated",snapshot.calibrated);out.putInt("granted",snapshot.granted)
        for((id,value)in snapshot.values)out.putBundle("metric-$id",Bundle().apply {
            value.raw?.let { putDouble("raw",it) };putLong("event",value.eventNs);putLong("received",value.receivedNs)
            value.availability?.let { putInt("availability",it) };putString("read",value.read.name)
        })
        out.putDoubleArray("trend-values",snapshot.speedHistory.map { it.kmh?:Double.NaN }.toDoubleArray())
        out.putLongArray("trend-times",snapshot.speedHistory.map { it.elapsedNs }.toLongArray())
        out.putStringArrayList("events",ArrayList(snapshot.events));super.onSaveInstanceState(out)
    }
    private fun restore(state:Bundle):ProbeDashboardSnapshot {
        val values=ProbeDashboardSnapshot.ALL_IDS.mapNotNull { id -> state.getBundle("metric-$id")?.let { b -> id to ProbeValue(
            if(b.containsKey("raw"))b.getDouble("raw") else null,b.getLong("event"),b.getLong("received"),
            if(b.containsKey("availability"))b.getInt("availability") else null,runCatching { ProbeRead.valueOf(b.getString("read").orEmpty()) }.getOrDefault(ProbeRead.WAITING)) } }.toMap()
        val times=state.getLongArray("trend-times")?:longArrayOf();val points=state.getDoubleArray("trend-values")?:doubleArrayOf()
        return ProbeDashboardSnapshot(ProbePhase.STOPPED,state.getBoolean("calibrated"),state.getInt("granted"),values,
            times.zip(points.toList()).map { ProbeTrendPoint(it.first,it.second.takeIf(Double::isFinite)) },state.getStringArrayList("events").orEmpty(),"恢复的历史快照；点击开始探测可新建报告")
    }
}
