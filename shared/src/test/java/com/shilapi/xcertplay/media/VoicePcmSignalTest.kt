package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VoicePcmSignalTest {
    @Test fun distinguishesSilentInputFromSignalRemovedBeforeSubmission() {
        val decoded = byteArrayOf(0, 0, 0, -128, -1, 127, 1, 0)
        val input = VoicePcmSignal()
        val submitted = VoicePcmSignal()
        input.observe(decoded, 0, decoded.size)
        submitted.observe(ByteArray(decoded.size), 0, decoded.size)
        assertEquals(VoicePcmSignal.Window(4, 3, 32768), input.take())
        assertEquals(VoicePcmSignal.Window(4, 0, 0), submitted.take())
        assertEquals(VoicePcmSignal.Window(0, 0, 0), input.take())
    }

    @Test fun measuresOnlyTheRequestedLittleEndianPcmWindow() {
        val signal = VoicePcmSignal()
        signal.observe(byteArrayOf(127, 127, -2, -1, 0, 0, 127), 2, 5)
        assertEquals(VoicePcmSignal.Window(2, 1, 2), signal.take())
    }

    @Test fun busLabelsNeverExposeOtherDeviceIdentifiers() {
        assertEquals("bus0_media_out", voiceBusDiagnostic(21, "bus0_media_out"))
        assertEquals("not_bus", voiceBusDiagnostic(8, "12:34:56:78:90:AB"))
        assertEquals("redacted", voiceBusDiagnostic(21, "12:34:56:78:90:AB"))
        assertEquals("redacted", voiceBusDiagnostic(21, "bus" + "a".repeat(80)))
    }
}
