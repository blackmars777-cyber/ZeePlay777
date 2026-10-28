package com.shilapi.xcertplay.media

/** Detect received audio with no PCM writes, without treating idle prompts as failed playback. */
internal class AudioOutputHealth {
    private var waitingSinceNs: Long? = null
    private var lastInputNs: Long? = null
    private var stalled = false
    private var recovered = false

    fun input(nowNs: Long) {
        if (lastInputNs?.let { nowNs - it > ACTIVE_INPUT_NS } != false) waitingSinceNs = null
        if (waitingSinceNs == null) waitingSinceNs = nowNs
        lastInputNs = nowNs
    }

    fun output(nowNs: Long) {
        waitingSinceNs = nowNs
        if (stalled) recovered = true
        stalled = false
    }

    /** Emits only transitions. This never reconnects or interrupts the driver's session. */
    fun transition(nowNs: Long): String? {
        if (recovered) {
            recovered = false
            return "recovered"
        }
        val since = waitingSinceNs ?: return null
        val last = lastInputNs ?: return null
        val noOutput = nowNs - since >= NO_OUTPUT_NS && nowNs - last <= ACTIVE_INPUT_NS
        if (!noOutput || stalled) return null
        stalled = true
        return "stalled"
    }

    private companion object {
        const val NO_OUTPUT_NS = 2_000_000_000L
        const val ACTIVE_INPUT_NS = 1_000_000_000L
    }
}
