# Concentus (vendored)

The Java port of the Opus reference library from
[lostromb/concentus](https://github.com/lostromb/concentus) at commit
`3885c4e46513ef0fc81fca100189e54f1714c6ca`: every file of `Java/Concentus/src/main/java/org/concentus`,
unmodified. Upstream publishes no release artifact of this port.

DiPlay uses `OpusEncoder` as the microphone encoder on head units whose Android has no MediaCodec
Opus encoder (Android 9 and earlier), and `OpusDecoder` for received CarPlay Opus audio independently
of the platform codec registry. See `shared/src/main/java/com/shilapi/xcertplay/media/SoftwareOpusEncoder.kt`
and `SoftwareOpusDecoder.kt`.

Licensed under the BSD-style licence in `LICENSE`, which retains the Opus copyright notices.
