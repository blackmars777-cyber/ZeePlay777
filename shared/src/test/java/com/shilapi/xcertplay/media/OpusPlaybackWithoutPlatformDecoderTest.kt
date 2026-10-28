package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Zeekr DHU: packets arrive, but creating the platform Opus decoder always fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 32], manifest = Config.NONE, shadows = [
    OpusPlaybackWithoutPlatformDecoderTest.MissingDecoder::class,
    OpusPlaybackWithoutPlatformDecoderTest.RecordingTrack::class,
])
class OpusPlaybackWithoutPlatformDecoderTest {
    @Test fun receivedNavigationOpusStillReachesAudioTrackWithoutAMediaCodecDecoder() {
        val encoder = SoftwareOpusEncoder(48_000)
        val source = ByteArray(1920) { index -> if (index % 2 == 0) (index % 127).toByte() else 8 }
        try {
            play(encoder.encode(source).single(), "default", 101)
        } finally {
            encoder.close()
        }
    }

    @Test fun shortSilencePacketsWorkForNavigationSiriAndCalls() {
        for (audioType in listOf("default", "speechrecognition", "telephony")) {
            play(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), audioType, 100)
        }
    }

    @Test fun missingDecoderStopsTheRendererWithoutReportingReadyOrKeepingPacketsQueued() {
        val reports = CopyOnWriteArrayList<String>()
        val renderer = renderer(AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media"), reports)
        val type = renderer.javaClass
        type.getDeclaredMethod("run").apply { isAccessible = true }.invoke(renderer)
        assertTrue(reports.any { it.contains("Audio: unavailable") && it.contains("reason=no_decoder") })
        assertFalse(reports.any { it.startsWith("Audio: ready") })
        type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
            .invoke(renderer, ByteArray(16), 0)
        val queue = type.getDeclaredField("queue").apply { isAccessible = true }.get(renderer) as Collection<*>
        assertTrue(queue.isEmpty())
    }

    private fun play(opus: ByteArray, audioType: String, payloadType: Int) {
        RecordingTrack.written = CountDownLatch(1)
        RecordingTrack.bytes = 0
        val reports = CopyOnWriteArrayList<String>()
        val renderer = renderer(AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType), reports)
        val type = renderer.javaClass
        try {
            type.getDeclaredMethod("start").invoke(renderer)
            type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(renderer, ByteArray(12) + opus, 0)
            val wrote = RecordingTrack.written.await(3, TimeUnit.SECONDS)
            assertTrue("Opus packets must produce PCM even when MediaCodec creation fails: $reports", wrote)
            assertEquals("20 ms of 48 kHz mono PCM16", 1920, RecordingTrack.bytes)
            assertTrue(reports.any { it.contains("Audio: decoder ready") && it.contains("implementation=software") })
        } finally {
            (renderer as Closeable).close()
            val worker = type.getDeclaredField("thread").apply { isAccessible = true }.get(renderer) as Thread
            worker.join(1000)
            assertFalse("audio worker must release on close", worker.isAlive)
        }
        assertTrue(type.getDeclaredField("totalWrittenFrames").apply { isAccessible = true }.getLong(renderer) > 0)
    }

    private fun renderer(format: AudioFormat, reports: MutableList<String>): Any {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val constructor = type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        val arguments = mutableListOf<Any?>(
            format, false, false, 0, 0,
            AudioFocusCoordinator(null, false, false), 0, 500,
        )
        constructor.parameterTypes.drop(arguments.size).forEach { parameter ->
            arguments.add(when (parameter) {
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                EchoReference::class.java -> null
                else -> {
                    check(parameter.name == "kotlin.jvm.functions.Function1")
                    val report: (String) -> Unit = { reports.add(it) }
                    report
                }
            })
        }
        return constructor.newInstance(*arguments.toTypedArray())
    }

    @Implements(MediaCodec::class)
    class MissingDecoder {
        companion object {
            @JvmStatic @Implementation fun createDecoderByType(mime: String): MediaCodec {
                throw IllegalArgumentException("No platform decoder for $mime")
            }
            @JvmStatic @Implementation fun createByCodecName(name: String): MediaCodec {
                throw IllegalArgumentException("No platform decoder named $name")
            }
        }
    }

    @Implements(AudioTrack::class)
    class RecordingTrack {
        @Implementation fun write(data: ByteArray, offset: Int, size: Int, mode: Int): Int {
            bytes += size
            written.countDown()
            return size
        }
        companion object {
            lateinit var written: CountDownLatch
            @Volatile var bytes = 0
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, encoding: Int): Int = 4096
        }
    }
}
