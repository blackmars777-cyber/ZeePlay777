package com.shilapi.xcertplay.media

/** Worker-owned amplitude counters only. Never retains PCM or records speech content. */
internal class VoicePcmSignal {
    data class Window(val samples: Long, val nonZero: Long, val peak: Int)
    private var samples = 0L
    private var nonZero = 0L
    private var peak = 0

    fun observe(data: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length - 1
        while (index < end) {
            val sample = (data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)
            samples++
            if (sample != 0) nonZero++
            peak = maxOf(peak, kotlin.math.abs(sample))
            index += 2
        }
    }

    fun take(): Window = Window(samples, nonZero, peak).also {
        samples = 0L; nonZero = 0L; peak = 0
    }
}

/** Generic device addresses can contain Bluetooth/network identifiers. Report named car buses only. */
internal fun voiceBusDiagnostic(type: Int?, address: String?): String = when {
    type != 21 -> "not_bus"
    address.isNullOrEmpty() -> "unknown"
    address.matches(Regex("bus[A-Za-z0-9_./-]{0,60}", RegexOption.IGNORE_CASE)) -> address
    else -> "redacted"
}
