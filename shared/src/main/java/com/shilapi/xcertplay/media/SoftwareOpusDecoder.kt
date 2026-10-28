package com.shilapi.xcertplay.media

import java.io.Closeable
import org.concentus.OpusDecoder

/** Bundled PCM16 decoder; one instance per stream, used only by its audio worker. */
internal class SoftwareOpusDecoder(
    sampleRate: Int,
    private val channels: Int,
    private val onError: (Throwable) -> Unit = {},
    create: () -> OpusDecoder = { OpusDecoder(sampleRate, channels) },
) : Closeable {
    private var decoder: OpusDecoder? = create()
    // Opus packets can contain up to 120 ms, independent of the RTP clock rate.
    private val samplesPerChannel = sampleRate * 120 / 1000
    private val samples = ShortArray(samplesPerChannel * channels)
    private var errors = 0

    fun decode(packet: ByteArray): ByteArray? {
        val current = decoder ?: return null
        // Empty network input is not an instruction to synthesize packet-loss concealment.
        if (packet.isEmpty()) return null
        return try {
            val count = current.decode(packet, 0, packet.size, samples, 0, samplesPerChannel, false)
            if (count <= 0) return null
            ByteArray(count * channels * 2).also { pcm ->
                for (index in 0 until count * channels) {
                    pcm[index * 2] = samples[index].toByte()
                    pcm[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
                }
            }
        } catch (error: Exception) {
            failed(current, error)
        } catch (error: AssertionError) {
            // Concentus also uses assertions for internal checks (#865 on the encoder).
            failed(current, error)
        }
    }

    private fun failed(current: OpusDecoder, error: Throwable): ByteArray? {
        if (errors++ < 3) runCatching { onError(error) }
        runCatching { current.resetState() }
        return null
    }

    override fun close() {
        decoder = null
    }
}
