package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.ByteArrayOutputStream

/** Wire contracts observed in the car client's SDK. No vendor SDK code or binaries are bundled. */
internal object ZeekrMediaProtocol {
    const val CORE = "com.zeekr.sdk.base.internal.IZeekrCoreService"
    const val SUPPORT = "com.zeekr.sdk.base.internal.IZeekrSupportService"
    const val SERVICE_CALLBACK = "com.zeekr.sdk.base.internal.IServiceCallback"
    const val REGISTER_CALLBACK = "com.zeekr.sdk.mediacenter.IRegisterCallBack"
    const val TOKEN_CALLBACK = "com.zeekr.mediacenter.ITokenCallBack"
    const val MEDIA_CALLBACK = "com.zeekr.sdk.mediacenter.IMediaCenterSvcCallBack"
    const val MEDIA = "com.zeekr.sdk.mediacenter.IMediaCenterSvc"
    const val CLIENT = "com.zeekr.sdk.mediacenter.IZeekrMusicClient"
    const val INFO = "com.zeekr.sdk.mediacenter.IMusicPlaybackInfo"
    const val SOURCE = 6

    fun <T> call(binder: IBinder, descriptor: String, code: Int,
        write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(descriptor)
            write(request)
            check(binder.transact(code, request, response, 0)) { "Unsupported transaction $code" }
            response.readException()
            return read(response)
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    fun command(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit = {}) =
        call(binder, descriptor, code, write) { Unit }

    /** Both CoreService and the exported media support service accept the same envelope. */
    fun route(core: IBinder, method: String, parameter: String, callback: IBinder,
        descriptor: String = CORE): Int =
        call(core, descriptor, if (descriptor == SUPPORT) 3 else 6, { request ->
            request.writeInt(1)
            request.writeString("mediacenter")
            request.writeString("ZeekrMediaCenterAPI")
            request.writeString(method)
            request.writeByteArray(stringMessage(parameter))
            request.writeByteArray(null)
            request.writeStrongBinder(callback)
        }) { reply ->
            // Only the status is needed; don't load vendor Parcelable classes from the tail.
            if (reply.readInt() == 0) 0 else reply.readInt()
        }

    fun stringMessage(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(10)
            var length = bytes.size
            while (length > 127) { write((length and 127) or 128); length = length ushr 7 }
            write(length)
            write(bytes)
        }.toByteArray()
    }

    fun readStringMessage(bytes: ByteArray?): String {
        require(bytes != null && bytes.size <= 65536 && bytes.firstOrNull() == 10.toByte())
        var position = 1
        var size = 0
        var shift = 0
        do {
            require(position < bytes.size && shift <= 28)
            val byte = bytes[position++].toInt() and 255
            size = size or ((byte and 127) shl shift)
            shift += 7
        } while (byte and 128 != 0)
        require(size >= 0 && size <= bytes.size - position)
        return String(bytes, position, size, Charsets.UTF_8)
    }

    /** A status envelope for the SDK extension endpoint; unsupported methods return no result. */
    fun writeResult(reply: Parcel, success: Boolean) {
        val code = if (success) 200 else 400
        val message = if (success) "SUCCESS" else "CarPlay unavailable or unsupported source/display"
        reply.writeInt(1) // ZeekrPlatformRetMessage
        reply.writeInt(code)
        reply.writeString(message)
        reply.writeByteArray(null)
        // FuncResult in the observed SDK uses reflection over its four static fields, then
        // code (field 5), mIntent (6) and msg (7). mIntent is absent for transport commands.
        val text = stringMessage(message).also { it[0] = 58 } // field 7, length-delimited
        val result = byteArrayOf(40, (code and 127 or 128).toByte(), (code ushr 7).toByte()) + text
        reply.writeString("com.zeekr.sdk.base.msg.ServiceRetMessage")
        reply.writeInt(code)
        reply.writeString(message)
        reply.writeByteArray(result)
    }
}

/** Handles descriptor queries and rejects unknown transactions rather than inventing wire replies. */
internal class ZeekrCallbackBinder(
    private val descriptor: String,
    private val dispatch: (Int, Parcel, Parcel?) -> Boolean,
) : Binder() {
    init { attachInterface(null, descriptor) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(descriptor)
            return true
        }
        if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(descriptor)
        return dispatch(code, data, reply)
    }
}
