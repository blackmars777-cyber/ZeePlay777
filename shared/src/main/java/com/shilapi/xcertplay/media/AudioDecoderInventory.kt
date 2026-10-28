package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/** Cached off the UI thread. An advertised component is a candidate, not proof it can start. */
internal object AudioDecoderInventory {
    private val snapshot by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
                .flatMap { codec -> codec.supportedTypes.map { mime -> mime.lowercase() to codec.name } }
                .groupBy({ it.first }, { it.second })
        }
    }

    fun names(mime: String): List<String>? = snapshot.getOrNull()?.get(mime.lowercase()).orEmpty()
        .takeIf { snapshot.isSuccess }

    fun report(report: (String) -> Unit) {
        for (mime in listOf(MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_AAC)) {
            val names = names(mime)
            runCatching {
                report("Audio: decoder capabilities mime=$mime query=${if (names == null) "failed" else "ok"} " +
                    "candidates=${names?.joinToString(",")?.ifEmpty { "none" } ?: "unknown"} " +
                    "bundledSoftware=${mime == MediaFormat.MIMETYPE_AUDIO_OPUS}")
            }
        }
    }
}
