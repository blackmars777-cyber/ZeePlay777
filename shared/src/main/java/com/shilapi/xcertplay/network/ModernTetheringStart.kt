package com.shilapi.xcertplay.network

import android.content.Context
import android.os.ResultReceiver
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Android 11+ equivalent of the call used by Zeekr's projection app. Uses the saved AP config. */
internal object ModernTetheringStart {
    fun start(context: Context, receiver: ResultReceiver, log: (String) -> Unit) {
        val manager = context.getSystemService("tethering")
            ?: throw NoSuchMethodException("TetheringManager service unavailable")
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        invoke(manager, callbackType, { receiver.send(it, null) }, log)
    }

    internal fun invoke(manager: Any, callbackType: Class<*>, result: (Int) -> Unit, log: (String) -> Unit) {
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result(0); null }
                "onTetheringFailed" -> {
                    // Zero must never turn a malformed failure into success.
                    val code = (args?.firstOrNull() as? Int)?.takeIf { it > 0 } ?: 1
                    log("car hotspot start: TetheringManager callback failed code=$code")
                    result(code)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DiPlayStartTetheringCallback"
                else -> null
            }
        }
        // This is the exact overload used by dlnavideo on Android 12. No new AP configuration,
        // entitlement exemption, caller-package substitution or hidden-API exemption is requested.
        manager.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackType)
            .invoke(manager, 0, Executor { it.run() }, callback)
        log("car hotspot start: called TetheringManager.startTethering/3")
    }
}
