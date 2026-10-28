package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusDecoderTest {
    @Test fun decodesContinuousVoiceAtDifferentSenderRatesAndPacketDurationsTo48KhzPcm() {
        for (senderRate in listOf(16_000, 24_000, 48_000)) {
            val encoder = OpusEncoder(senderRate, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            val reference = OpusDecoder(48_000, 1)
            val decoder = SoftwareOpusDecoder(48_000, 1)
            for (durationMs in listOf(10, 20, 60, 20)) {
                val source = ShortArray(senderRate * durationMs / 1000) { i ->
                    (8000 * sin(2 * PI * 440 * i / senderRate)).toInt().toShort()
                }
                val compressed = ByteArray(1275)
                val size = encoder.encode(source, 0, source.size, compressed, 0, compressed.size)
                val packet = compressed.copyOf(size)
                val expected = ShortArray(5760)
                val count = reference.decode(packet, 0, size, expected, 0, expected.size, false)
                val actual = decoder.decode(packet)!!
                assertEquals(48_000 * durationMs / 1000, count)
                assertEquals(count * 2, actual.size)
                for (index in 0 until count) {
                    val value = ((actual[index * 2].toInt() and 255) or (actual[index * 2 + 1].toInt() shl 8)).toShort()
                    assertEquals(expected[index], value)
                }
                assertTrue(actual.any { it != 0.toByte() })
            }
            decoder.close()
        }
    }

    @Test fun validThreeByteSilencePacketsAreNotDiscardedAsTooShort() {
        val decoder = SoftwareOpusDecoder(48_000, 1)
        assertEquals(1920, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))!!.size)
        assertNull(decoder.decode(byteArrayOf()))
        decoder.close()
        assertNull(decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())))
    }

    @Test fun assertionFailureDropsOnePacketResetsStateAndDoesNotKillTheStream() {
        val errors = mutableListOf<Throwable>()
        var attempts = 0
        var resets = 0
        val decoder = SoftwareOpusDecoder(48_000, 1, onError = { errors += it }, create = {
            object : OpusDecoder(48_000, 1) {
                override fun decode(data: ByteArray?, offset: Int, length: Int, pcm: ShortArray,
                    pcmOffset: Int, frameSize: Int, fec: Boolean): Int {
                    if (attempts++ == 0) throw AssertionError("injected decoder check")
                    return super.decode(data, offset, length, pcm, pcmOffset, frameSize, fec)
                }
                override fun resetState() {
                    resets++
                    super.resetState()
                }
            }
        })
        val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        val initialResets = resets
        assertNull(decoder.decode(packet))
        assertTrue(errors.single() is AssertionError)
        assertEquals(initialResets + 1, resets)
        assertEquals(1920, decoder.decode(packet)!!.size)
        decoder.close()
    }

    @Test fun malformedPacketDoesNotPreventTheNextPromptFromDecoding() {
        val decoder = SoftwareOpusDecoder(48_000, 1)
        assertNull(decoder.decode(byteArrayOf(0xff.toByte())))
        assertEquals(1920, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))!!.size)
    }
}
