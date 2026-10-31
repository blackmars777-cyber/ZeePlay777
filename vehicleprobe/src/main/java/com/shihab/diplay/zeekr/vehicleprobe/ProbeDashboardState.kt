package com.shihab.diplay.zeekr.vehicleprobe

internal enum class ProbePhase(val label: String) {
    IDLE("准备就绪"), CONNECTING("正在连接"), CONNECTED("车辆已连接"),
    DISCONNECTED("连接已断开"), ERROR("接口不可用"), STOPPED("已停止 · 历史快照")
}
internal enum class ProbeRead { WAITING, VALUE, UNSUPPORTED, ERROR, INVALID }
internal data class ProbeValue(val raw: Double? = null, val eventNs: Long = 0, val receivedNs: Long = 0,
    val availability: Int? = null, val read: ProbeRead = ProbeRead.WAITING) {
    // The observed firmware reports -1 even for changing, manually verified readings.
    // A diagnostic display can show this evidence as unknown; it does not make it AVAILABLE.
    val statusUnknown: Boolean get() = availability == null || availability == -1
    val visible: Double? get() = raw?.takeIf { it.isFinite() && read == ProbeRead.VALUE && eventNs > 0 && receivedNs >= eventNs && (statusUnknown || availability == 0) }
    val stateLabel: String get() = when {
        read == ProbeRead.UNSUPPORTED -> "接口未暴露"
        read == ProbeRead.ERROR -> "读取失败"
        read == ProbeRead.INVALID -> "数值范围异常"
        read != ProbeRead.VALUE -> "等待采样"
        !statusUnknown && availability != 0 -> "信号不可用"
        raw == null || !raw.isFinite() -> "数值无效"
        eventNs <= 0 || receivedNs < eventNs -> "时间戳待核对"
        statusUnknown -> "缓存读数 · 状态待核对"
        else -> "可读 · 按信号变化更新"
    }
}
internal data class ProbeTrendPoint(val elapsedNs: Long, val kmh: Double?)
internal data class ProbeDashboardSnapshot(
    val phase: ProbePhase = ProbePhase.IDLE,
    val calibrated: Boolean = false,
    val granted: Int = 0,
    val values: Map<Int, ProbeValue> = emptyMap(),
    val speedHistory: List<ProbeTrendPoint> = emptyList(),
    val events: List<String> = emptyList(),
    val note: String = "请驻车操作；行驶时由乘员采集",
) {
    fun value(id: Int) = values[id] ?: ProbeValue()
    val speed: Double? get() = value(SPEED).visible?.takeIf { calibrated && it in 0.0..250.0 }
    val battery: Double? get() = value(BATTERY).visible?.takeIf { calibrated && it in 0.0..100.0 }
    val gear: Int? get() = value(GEAR).visible?.takeIf { calibrated && it in 0.0..3.0 && it == it.toInt().toDouble() }?.toInt()
    companion object {
        const val SPEED = 291504647
        const val GEAR = 289408000
        const val BATTERY = 1610612780
        val RANGE_IDS = listOf(1610612745, 1610612781)
        val ALL_IDS = listOf(SPEED, GEAR, BATTERY) + RANGE_IDS
    }
}

/** Structured UI state never parses logs or substitutes missing data with zero. Worker-owned. */
internal class ProbeDashboardState(private val calibrated: Boolean) {
    private var phase = ProbePhase.IDLE
    private var granted = 0
    private var note = "请驻车操作；行驶时由乘员采集"
    private val values = mutableMapOf<Int, ProbeValue>()
    private val trend = ArrayDeque<ProbeTrendPoint>()
    private val events = ArrayDeque<String>()
    fun phase(next: ProbePhase, message: String? = null) {
        phase = next
        if (message != null) note = message
        if (next == ProbePhase.DISCONNECTED || next == ProbePhase.ERROR) values.clear()
    }
    fun permissions(count: Int) { granted = count }
    fun supported(ids: Set<Int>) {
        ProbeDashboardSnapshot.ALL_IDS.forEach { id -> values[id] = ProbeValue(read = if (id in ids) ProbeRead.WAITING else ProbeRead.UNSUPPORTED) }
    }
    fun reading(id: Int, raw: Double?, eventNs: Long, receivedNs: Long, availability: Int?) {
        if (id !in ProbeDashboardSnapshot.ALL_IDS) return
        val inRange = !calibrated || raw == null || when(id) {
            ProbeDashboardSnapshot.SPEED -> raw in 0.0..250.0
            ProbeDashboardSnapshot.BATTERY -> raw in 0.0..100.0
            ProbeDashboardSnapshot.GEAR -> raw in 0.0..3.0 && raw == raw.toInt().toDouble()
            else -> raw.isFinite() && raw >= 0
        }
        values[id] = ProbeValue(raw, eventNs, receivedNs, availability, if(inRange)ProbeRead.VALUE else ProbeRead.INVALID)
        if (id == ProbeDashboardSnapshot.SPEED) {
            val visible = values[id]!!.visible?.takeIf { calibrated && it in 0.0..250.0 && receivedNs-eventNs <= 3_000_000_000L }
            if (trend.size == 60) trend.removeFirst()
            trend.addLast(ProbeTrendPoint(receivedNs, visible))
        }
    }
    fun failed(id: Int) { if (id in ProbeDashboardSnapshot.ALL_IDS) values[id] = ProbeValue(read = ProbeRead.ERROR) }
    fun gearEvent(raw: Int?, eventNs: Long, receivedNs: Long, availability: Int?) {
        if (events.size == 12) events.removeFirst()
        val gear = if (calibrated) when (raw) { 0 -> "P 驻车"; 1 -> "R 倒车"; 2 -> "N 空挡"; 3 -> "D 前进"; else -> "未知挡位" } else "原始值 $raw"
        events.addLast("$gear · ${receivedNs / 1_000_000_000} s · 状态 ${availability ?: "未知"}")
    }
    fun snapshot() = ProbeDashboardSnapshot(phase, calibrated, granted, values.toMap(), trend.toList(), events.toList(), note)
}
