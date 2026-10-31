package com.shihab.diplay.zeekr.vehicleprobe

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.*

internal object ProbeUi {
    val BG = Color.rgb(10, 16, 22)
    val CARD = Color.rgb(18, 28, 38)
    val LINE = Color.rgb(42, 56, 69)
    val TEXT = Color.rgb(239, 245, 249)
    val MUTED = Color.rgb(145, 161, 176)
    val LIME = Color.rgb(190, 244, 119)
    val BLUE = Color.rgb(122, 188, 240)
    val AMBER = Color.rgb(246, 195, 114)
    const val GAP = 16
    const val PAD = 24
    const val RADIUS = 20
    fun dp(context: Context, value: Number) = (value.toFloat() * context.resources.displayMetrics.density).roundToInt()
    fun sp(context: Context, value: Float) = value * context.resources.displayMetrics.scaledDensity
    fun shape(context: Context, fill: Int, radius: Float = RADIUS.toFloat(), border: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(context, radius).toFloat()
        if (border) setStroke(dp(context, 1), LINE)
    }
    fun label(context: Context, text: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun button(context: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title; isAllCaps = false; textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) BG else TEXT)
        val height = 64
        minHeight = dp(context, height); minimumHeight = dp(context, height)
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x22447766), shape(context, if (primary) LIME else CARD, height * .24f, !primary), null)
        setOnClickListener { action() }
    }
}

internal class ProbeSpeedGauge(context: Context) : View(context) {
    init { isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    var snapshot = ProbeDashboardSnapshot(); set(value) {
        field = value
        contentDescription = "车速 ${value.speed?.let { "${it.roundToInt()} 公里每小时" } ?: "等待有效信号"}，${value.value(ProbeDashboardSnapshot.SPEED).stateLabel}"
        invalidate()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value: Float) = ProbeUi.dp(context, value).toFloat()
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(if (bold) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        paint.textSize = ProbeUi.sp(context, size)
        canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * .48f
        val radius = min(width * .35f, height * .35f)
        val bounds = RectF(cx-radius, cy-radius, cx+radius, cy+radius)
        val live = snapshot.phase == ProbePhase.CONNECTED
        val accent = if (live) { if(snapshot.value(ProbeDashboardSnapshot.SPEED).availability==0)ProbeUi.LIME else ProbeUi.AMBER } else ProbeUi.MUTED
        text(canvas, "车速", cx, dp(28f), 15f, ProbeUi.MUTED)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(7f); paint.strokeCap = Paint.Cap.ROUND
        paint.color = ProbeUi.LINE
        canvas.drawArc(bounds, 145f, 250f, false, paint)
        snapshot.speed?.let {
            paint.color = accent
            canvas.drawArc(bounds, 145f, (it.coerceIn(0.0,240.0) / 240 * 250).toFloat(), false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        for (i in 0..24) {
            val angle = Math.toRadians((145f + i * 250f/24).toDouble())
            val outer = radius - dp(13f)
            val inner = outer - dp(if (i%6==0) 10f else 5f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (i%6==0) 2f else 1f); paint.color = ProbeUi.LINE
            canvas.drawLine(cx+cos(angle).toFloat()*inner,cy+sin(angle).toFloat()*inner,
                cx+cos(angle).toFloat()*outer,cy+sin(angle).toFloat()*outer,paint)
            if (i%6==0) text(canvas, (i*10).toString(), cx+cos(angle).toFloat()*(inner-dp(10f)),
                cy+sin(angle).toFloat()*(inner-dp(10f)),10f,ProbeUi.MUTED)
        }
        val size = min(84f, min(width / resources.displayMetrics.density * .23f, height / resources.displayMetrics.density * .25f))
        text(canvas, snapshot.speed?.roundToInt()?.toString() ?: "—", cx, cy, size, ProbeUi.TEXT, true)
        text(canvas,"km/h",cx,cy+ProbeUi.sp(context,size)*.55f+dp(12f),14f,ProbeUi.MUTED)
        val staleMoving = snapshot.speed?.let { it > 0 && snapshot.value(ProbeDashboardSnapshot.SPEED).receivedNs-snapshot.value(ProbeDashboardSnapshot.SPEED).eventNs > 3_000_000_000L } == true
        val state = if(staleMoving) "缓存读数 · 尚无新速度事件" else if (!snapshot.calibrated && snapshot.phase == ProbePhase.CONNECTED) "固件未标定 · 查看原始信号"
            else snapshot.value(ProbeDashboardSnapshot.SPEED).stateLabel
        text(canvas, state, cx, height-dp(26f), 12f, if(snapshot.speed==null||staleMoving) ProbeUi.AMBER else accent)
    }
}

internal class ProbeBatteryRing(context: Context) : View(context) {
    var percent: Double? = null; set(value) { field=value; contentDescription="电量 ${value?.let { "${it.roundToInt()}%" } ?: "未知"}"; invalidate() }
    var live = false; set(value) { field=value; invalidate() }
    var unknown = false; set(value) { field=value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val cx=width/2f; val cy=height/2f; val r=min(width,height)*.40f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=ProbeUi.dp(context,6).toFloat();paint.strokeCap=Paint.Cap.ROUND
        paint.color=ProbeUi.LINE;canvas.drawCircle(cx,cy,r,paint)
        percent?.let { paint.color=if(live)ProbeUi.LIME else if(unknown)ProbeUi.AMBER else ProbeUi.MUTED;canvas.drawArc(RectF(cx-r,cy-r,cx+r,cy+r),-90f,(it*3.6).toFloat(),false,paint) }
        paint.style=Paint.Style.FILL;paint.color=ProbeUi.TEXT;paint.textAlign=Paint.Align.CENTER
        paint.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
        paint.textSize=min(ProbeUi.sp(context,34f),min(width,height)*.42f)
        val value=percent?.roundToInt()?.toString() ?: "—"
        canvas.drawText(value,cx,cy-(paint.ascent()+paint.descent())/2,paint)
    }
}

internal class ProbeSpeedTrend(context: Context) : View(context) {
    init { isClickable=true;isFocusable=true;contentDescription="车速趋势；有效采样到达后可点按查看" }
    var live = false; set(value) { field=value; invalidate() }
    var points = emptyList<ProbeTrendPoint>(); set(value) { field=value; selected=null; invalidate() }
    private var selected: Int? = null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val plot=RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad=ProbeUi.dp(context,24).toFloat()
        plot.set(pad,pad+ProbeUi.dp(context,24),width-pad,(height-ProbeUi.dp(context,24)).toFloat())
        paint.color=ProbeUi.MUTED;paint.textSize=ProbeUi.sp(context,13f);paint.textAlign=Paint.Align.LEFT
        paint.style=Paint.Style.FILL;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        canvas.drawText("车速趋势",pad,pad,paint)
        paint.textAlign=Paint.Align.RIGHT;paint.textSize=ProbeUi.sp(context,11f)
        canvas.drawText(if(width/resources.displayMetrics.density<600) "${points.size} 次 · 点按查看" else "${if(live)"最近" else "历史"} ${points.size} 次采样 · 点击查看",width-pad,pad,paint)
        if(plot.height()<=0)return
        paint.strokeWidth=ProbeUi.dp(context,1).toFloat();paint.color=ProbeUi.LINE
        for(i in 0..2)canvas.drawLine(plot.left,plot.top+i*plot.height()/2,plot.right,plot.top+i*plot.height()/2,paint)
        val actual=points.mapNotNull { it.kmh }
        if(actual.isEmpty()) {
            paint.color=ProbeUi.MUTED;paint.textAlign=Paint.Align.CENTER;paint.textSize=ProbeUi.sp(context,13f)
            canvas.drawText("有效车速到达后显示趋势",plot.centerX(),plot.centerY(),paint);return
        }
        val upper=max(20.0,ceil((actual.maxOrNull() ?: 0.0)/20)*20)
        val first=points.first().elapsedNs;val span=max(1L,points.last().elapsedNs-first)
        fun x(index:Int)=plot.left+((points[index].elapsedNs-first).toDouble()/span*plot.width()).toFloat()
        fun y(value:Double)=plot.bottom-(value/upper*plot.height()).toFloat()
        val path=Path();var segment=false
        points.forEachIndexed { index,p ->
            val value=p.kmh
            if(value==null)segment=false else { if(segment)path.lineTo(x(index),y(value)) else path.moveTo(x(index),y(value));segment=true }
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=ProbeUi.dp(context,2).toFloat();paint.color=ProbeUi.BLUE
        canvas.drawPath(path,paint)
        selected?.takeIf { it in points.indices }?.let { index -> points[index].kmh?.let { value ->
            paint.strokeWidth=ProbeUi.dp(context,1).toFloat();paint.color=ProbeUi.MUTED
            canvas.drawLine(x(index),plot.top,x(index),plot.bottom,paint)
            paint.style=Paint.Style.FILL;paint.color=ProbeUi.BLUE;canvas.drawCircle(x(index),y(value),ProbeUi.dp(context,4).toFloat(),paint)
            paint.color=ProbeUi.TEXT;paint.textAlign=Paint.Align.RIGHT;paint.textSize=ProbeUi.sp(context,12f)
            canvas.drawText("${value.roundToInt()} km/h · +${(points[index].elapsedNs-first)/1_000_000_000} 秒",plot.right,plot.top-ProbeUi.dp(context,5),paint)
        } }
        contentDescription="车速趋势，${actual.size} 个有效采样，最低 ${actual.minOrNull()?.roundToInt()}，最高 ${actual.maxOrNull()?.roundToInt()} 公里每小时"
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(points.isEmpty())return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { val fraction=((event.x-plot.left)/max(1f,plot.width())).coerceIn(0f,1f)
                val target=points.first().elapsedNs+((points.last().elapsedNs-points.first().elapsedNs)*fraction).toLong()
                selected=points.indices.minByOrNull { abs(points[it].elapsedNs-target) };invalidate();return true }
            MotionEvent.ACTION_UP -> { performClick();return true }
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
