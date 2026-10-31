package com.shihab.diplay.zeekr.vehicleprobe

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only evidence collector. There is deliberately no vehicle-status or CarPlay output. */
internal class ZeekrVehicleProbe(
    private val context: Context,
    private val publish: (String) -> Unit,
    private val createCar: (Context, ServiceConnection) -> Any? = ::createCar,
    private val onSnapshot: (ProbeDashboardSnapshot) -> Unit = {},
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "zeekr-vehicle-probe") }
    private val report = ProbeReport()
    private val dashboard = ProbeDashboardState(android.os.Build.MODEL == "Zeekr DHU for arm64" &&
        android.os.Build.DISPLAY == "zeekr_dhu-userdebug 12 SQ3A.220705.003.A1 eng.buildf.20260902.185825 test-keys")
    private fun emit() { publish(report.text()); onSnapshot(dashboard.snapshot()) }
    private var car: Any? = null
    private var property: Any? = null
    private var sensor: Any? = null
    private var sensorListener: Any? = null
    private var properties = emptySet<Int>()
    private var sensors = emptySet<Int>()
    private var sensorCandidates = emptyMap<Int, String>()
    private var connected = false
    private var connectionRequestedAt = 0L
    private var connectionTimedOut = false
    private var connectionEpoch = 0
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = enqueue {
            connected = true
            connectionEpoch++
            dashboard.phase(ProbePhase.CONNECTED, "只读采集；静态或按变化更新的信号可保留旧时间戳")
            report.keep("车辆服务已连接： $name epoch=$connectionEpoch")
            discover()
        }
        override fun onServiceDisconnected(name: ComponentName) = enqueue {
            connected = false
            dashboard.phase(ProbePhase.DISCONNECTED, "车辆连接已断开，当前读数已清空")
            property = null
            sensor = null
            sensorListener = null
            properties = emptySet()
            sensors = emptySet()
            report.add("DISCONNECTED（已断开）：之前的样本已失效；服务未恢复时请重新探测。")
        }
    }

    fun start() = enqueue {
        dashboard.phase(ProbePhase.CONNECTING, "正在请求车辆只读接口")
        dashboard.permissions(PERMISSIONS.count { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
        report.keep("只读探测：原始读数仅作验证依据，不代表已确认的车辆状态。")
        report.keep("车型／设备：${android.os.Build.MODEL}；固件：${android.os.Build.DISPLAY}")
        PERMISSIONS.forEach { report.keep("权限 $it=${context.checkSelfPermission(it)}（0=已授权，-1=未授权）") }
        emit() // Preserve a usable report even if the vendor connect call stalls.
        try {
            car = createCar(context.applicationContext, connection)
            checkNotNull(car) { "Car.createCar 未返回车辆服务对象" }
            invoke(car!!, "connect")
            connectionRequestedAt = SystemClock.elapsedRealtimeNanos()
            report.keep("已请求连接车辆服务；以连接回调状态为准。")
        } catch (error: Exception) {
            report.keep("车辆接口：${ProbeReport.error(error)}")
            dashboard.phase(ProbePhase.ERROR, "当前设备未提供可用车辆接口；可导出报告排查")
        }
        emit()
        if (!stopped.get()) runCatching { worker.scheduleWithFixedDelay({
            if (!stopped.get()) {
                if (connected) sample()
                else if (connectionRequestedAt > 0 && !connectionTimedOut && SystemClock.elapsedRealtimeNanos()-connectionRequestedAt > 10_000_000_000L) {
                    connectionTimedOut=true
                    report.keep("车辆服务连接超过 10 秒未响应；可停止后重新探测。")
                    dashboard.phase(ProbePhase.ERROR,"车辆服务暂未响应；可停止后重新探测或导出诊断")
                }
                emit()
            }
        }, 2, 2, TimeUnit.SECONDS) }
    }

    private fun discover() {
        property = attempt("车辆属性管理器") { invoke(car!!, "getCarManager", "property") }
        properties = property?.let { manager ->
            val configs = attempt("车辆属性列表（可能经过权限过滤）") {
                invoke(manager, "getPropertyList") as List<*>
            }
            configs?.mapNotNull { config ->
                config ?: return@mapNotNull null
                attempt("车辆属性配置") {
                    val id = invoke(config, "getPropertyId") as Int
                    if (id in PROPERTY_CANDIDATES) {
                        report.keep("config $id ${PROPERTY_CANDIDATES[id]} areas=${ProbeReport.raw(invoke(config, "getAreaIds"))}" +
                            " changeMode=${invoke(config, "getChangeMode")} minHz=${invoke(config, "getMinSampleRate")}" +
                            " maxHz=${invoke(config, "getMaxSampleRate")}")
                    }
                    id
                }
            }?.toSet().orEmpty()
        }.orEmpty()
        PROPERTY_CANDIDATES.forEach { (id, label) ->
            if (id !in properties) report.keep("property $id $label NOT_EXPOSED（未暴露：不支持或被权限过滤）")
        }
        sensor = attempt("车辆传感器管理器") { invoke(car!!, "getCarManager", "sensor") }
        sensors = sensor?.let { manager ->
            (attempt("支持的传感器列表") { invoke(manager, "getSupportedSensors") } as? IntArray)?.toSet()
        }.orEmpty()
        dashboard.supported(sensors)
        sensorCandidates = buildMap {
            val type = attempt("CarSensorManager 类") { Class.forName("android.car.hardware.CarSensorManager") }
            listOf("SENSOR_TYPE_CAR_SPEED", "SENSOR_TYPE_GEAR", "SENSOR_TYPE_EV_BATTERY_LEVEL").forEach { name ->
                type?.let { attempt(name) { put(it.getField(name).getInt(null), name) } }
            }
            // IDs observed in this firmware's SensorHalService. Units/enums remain unverified.
            put(1610612747, "ZEEKR_VEHICLE_SPEED 单位未知（UNKNOWN）")
            put(1610612748, "ZEEKR_WHEEL_SPEED 单位未知（UNKNOWN）")
            put(1610612753, "ZEEKR_GEAR_STATE 枚举未知（UNKNOWN）")
            put(1610612780, "ZEEKR_HV_BATT_LVL_IND 电量候选")
            put(1610612745, "ZEEKR_DTEHV_BATT_IND 动态续航候选（单位待验证）")
            put(1610612781, "ZEEKR_FUNCENDURANCE_DATA 续航候选（单位待验证）")
        }
        sensorCandidates.forEach { (id, label) ->
            report.keep("sensor $id $label ${if (id in sensors) "EXPOSED（已暴露）" else "NOT_EXPOSED（未暴露）"}")
        }
        sensor?.let { manager ->
            attempt("订阅传感器") {
                val listenerType = Class.forName("android.car.hardware.CarSensorManager\$OnSensorChangedListener")
                val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "DiPlayReadOnlySensorObserver"
                        "onSensorChanged" -> {
                            val received = SystemClock.elapsedRealtimeNanos()
                            val event = args?.firstOrNull()
                            runCatching {
                                if (event != null && event.javaClass.getField("sensorType").getInt(event) == 289408000) {
                                    val timestamp = event.javaClass.getField("timestamp").getLong(event)
                                    val value = (event.javaClass.getField("intValues").get(event) as IntArray).firstOrNull()
                                    val available = runCatching { event.javaClass.getField("availability").getInt(event) }.getOrNull()
                                    enqueue {
                                        report.keep("挡位回调（含注册时缓存）：raw=$value eventNs=$timestamp receivedNs=$received availability=${available ?: "unknown"}")
                                        dashboard.gearEvent(value, timestamp, received, available)
                                    }
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }
                sensorListener = listener
                val rate = manager.javaClass.getField("SENSOR_RATE_NORMAL").getInt(null)
                sensorCandidates.keys.filter { it in sensors }.forEach { id ->
                    attempt("订阅传感器 $id") {
                        report.keep("订阅传感器 $id 接受结果=${invoke(manager, "registerListener", listener, id, rate)}")
                    }
                }
            }
        }
        report.keep("轮询间隔：2000 毫秒（不代表信号更新频率）；传感器读数来自 SDK 缓存。")
    }

    private fun sample() {
        property?.let { manager ->
            PROPERTY_CANDIDATES.forEach { (id, label) ->
                if (id in properties) attempt("property $id $label") {
                    val value = invoke(manager, "getProperty", id, 0)
                    if (value == null) report.add("property $id NO_DATA（无数据）") else {
                        val status = invoke(value, "getStatus") as Int
                        val timestamp = invoke(value, "getTimestamp") as Long
                        report.add("property $id $label status=$status （0=可用） " +
                            ProbeReport.sample(invoke(value, "getValue"), timestamp, SystemClock.elapsedRealtimeNanos()))
                    }
                }
            }
        }
        sensor?.let { manager ->
            sensorCandidates.forEach { (id, label) ->
                if (id in sensors) {
                val result = attempt("sensor $id $label") {
                    val event = invoke(manager, "getLatestSensorEvent", id)
                    if (event == null) {
                        report.add("sensor $id NO_DATA（无数据）")
                        dashboard.failed(id)
                    } else {
                        val type = event.javaClass
                        val timestamp = type.getField("timestamp").getLong(event)
                        val raw = if (id == ProbeDashboardSnapshot.GEAR) (type.getField("intValues").get(event) as IntArray).firstOrNull()?.toDouble()
                            else (type.getField("floatValues").get(event) as FloatArray).firstOrNull()?.toDouble()
                                ?: (type.getField("intValues").get(event) as IntArray).firstOrNull()?.toDouble()
                                ?: (type.getField("longValues").get(event) as LongArray).firstOrNull()?.toDouble()
                        dashboard.reading(id, raw, timestamp, SystemClock.elapsedRealtimeNanos(),
                            runCatching { type.getField("availability").getInt(event) }.getOrNull())
                        report.add("sensor $id $label CACHED（缓存读数） " + ProbeReport.sample(
                            type.getField("floatValues").get(event), timestamp, SystemClock.elapsedRealtimeNanos()) +
                            decodedSensor(id, event) +
                            " availability=${runCatching { type.getField("availability").getInt(event) }.getOrNull() ?: "unknown"}" +
                            " ints=${ProbeReport.raw(type.getField("intValues").get(event))}" +
                            " longs=${ProbeReport.raw(type.getField("longValues").get(event))}")
                    }
                    true
                }
                if (result != true) dashboard.failed(id)
                }
            }
        }
    }

    private fun decodedSensor(id: Int, event: Any): String {
        if (android.os.Build.MODEL != "Zeekr DHU for arm64" || android.os.Build.DISPLAY !=
            "zeekr_dhu-userdebug 12 SQ3A.220705.003.A1 eng.buildf.20260902.185825 test-keys") return ""
        val type = event.javaClass
        return when (id) {
            291504647 -> (type.getField("floatValues").get(event) as FloatArray).firstOrNull()
                ?.takeIf { it.isFinite() && it in 0f..250f }?.let { " 车速=$it km/h（${it / 3.6} m/s）" }.orEmpty()
            289408000 -> (type.getField("intValues").get(event) as IntArray).firstOrNull()
                ?.let { " 挡位=" + when (it) { 0 -> "P（驻车）"; 1 -> "R（倒车）"; 2 -> "N（空挡）"; 3 -> "D（前进）"; else -> "未知（$it）" } }.orEmpty()
            1610612780 -> (type.getField("floatValues").get(event) as FloatArray).firstOrNull()
                ?.takeIf { it.isFinite() && it in 0f..100f }?.let { " 电量=$it%" }.orEmpty()
            else -> ""
        }
    }

    private fun <T> attempt(label: String, block: () -> T): T? = try {
        block()
    } catch (error: Exception) {
        report.add("$label: ${ProbeReport.error(error)}")
        null
    }

    private fun enqueue(block: () -> Unit) {
        if (!stopped.get()) runCatching { worker.execute { if (!stopped.get()) block() } }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        // Serial cleanup follows any in-flight RPC. No UI thread waits for a vendor service.
        worker.execute {
            sensorListener?.let { listener -> sensor?.let { manager ->
                attempt("取消传感器订阅") { invoke(manager, "unregisterListener", listener) }
            } }
            car?.let { attempt("断开车辆服务") { invoke(it, "disconnect") } }
            car = null
            property = null
            sensor = null
            report.add("STOPPED（已停止）；以上读数均为历史记录。")
            dashboard.phase(ProbePhase.STOPPED, "历史快照；点击开始探测可新建报告")
            emit()
        }
        worker.shutdown()
    }

    companion object {
        val PERMISSIONS = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_ENERGY", "android.car.permission.CAR_INFO")
        val PROPERTY_CANDIDATES = linkedMapOf(
            291504647 to "PERF_VEHICLE_SPEED m/s", 289408000 to "GEAR_SELECTION raw enum",
            289408001 to "CURRENT_GEAR raw enum", 291504905 to "EV_BATTERY_LEVEL Wh",
            291504390 to "INFO_EV_BATTERY_CAPACITY Wh", 291504904 to "RANGE_REMAINING m",
        )

        private fun createCar(context: Context, connection: ServiceConnection): Any? =
            Class.forName("android.car.Car")
                .getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, connection)

        private fun invoke(target: Any, name: String, vararg args: Any): Any? {
            val method = target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.zip(args).all { (type, arg) ->
                        type.isInstance(arg) || type == Int::class.javaPrimitiveType && arg is Int
                    }
            } ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
            return method.invoke(target, *args)
        }
    }
}

/** Bounded report, independent of Android so invalid/stale data behavior can be checked on the host. */
internal class ProbeReport {
    private val lines = ArrayDeque<String>()
    private val evidence = ArrayDeque<String>()
    fun keep(line: String) {
        if (evidence.size == 100) evidence.removeFirst()
        evidence.addLast(line.take(600))
    }
    private var omitted = 0
    fun add(line: String) {
        if (lines.size == 300) { lines.removeFirst(); omitted++ }
        lines.addLast(line.take(600))
    }
    fun text(): String = "极氪车辆数据只读探针\n已丢弃旧记录：$omitted\n" +
        evidence.joinToString("\n") + "\n--- 最近的采样与错误记录 ---\n" + lines.joinToString("\n")

    companion object {
        fun raw(value: Any?): String = when (value) {
            null -> "NO_DATA（无数据）"
            is FloatArray -> value.contentToString()
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            else -> value.toString()
        }.take(200)

        fun sample(value: Any?, timestamp: Long, now: Long): String {
            val age = if (timestamp > 0 && timestamp <= now) (now - timestamp) / 1_000_000 else null
            val state = when {
                value == null -> "NO_DATA（无数据）"
                value is Float && !value.isFinite() -> "INVALID（无效数值）"
                value is FloatArray && value.any { !it.isFinite() } -> "INVALID（无效数值）"
                age == null -> "UNKNOWN_TIMESTAMP（时间戳未知或异常）"
                age > 2000 -> "OLD_SAMPLE（距最近事件超过 2 秒，不代表按变化更新的信号已失效）"
                else -> "RECENT_SAMPLE（近期样本，数据含义尚未验证）"
            }
            return "raw=${raw(value)} timestampNs=$timestamp ageMs=${age ?: "unknown"} $state"
        }
        fun error(error: Exception): String {
            var cause: Throwable = error
            while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
            val state = if (cause is SecurityException) "PERMISSION_DENIED（权限被拒绝）" else "ERROR（错误）"
            return "$state ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(180)}"
        }
    }
}
