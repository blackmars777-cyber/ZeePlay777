package com.shilapi.xcertplay.airplay

import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamPortIsolationTest {
    @Test fun liveMusicGuidanceAndSiriPortsCannotBeReboundByAnotherReceiver() {
        val streams = listOf(102, 101, 100).map { AudioStream(ByteArray(32), it) }
        try {
            val ports = streams.flatMap { stream ->
                val (data, control) = stream.listen(object : AudioStream.Listener {})
                listOf(data, control)
            }
            assertEquals(6, ports.toSet().size)
            for (port in ports) DatagramSocket(null).use { competing ->
                competing.reuseAddress = true
                // Linux permits overlapping UDP binds if both sockets opt into address reuse.
                // A later navigation/RTCP socket must not take the music receiver's datagrams.
                assertThrows("Live UDP port $port must be exclusive", BindException::class.java) {
                    competing.bind(InetSocketAddress(InetAddress.getByName("::"), port))
                }
            }
        } finally { streams.forEach { it.close() } }
    }

    @Test fun musicReceivesPacketsBeforeDuringAndAfterGuidanceAndAfterSiriReplacement() {
        val musicPackets = LinkedBlockingQueue<Int>()
        val guidancePackets = LinkedBlockingQueue<Int>()
        val voicePackets = LinkedBlockingQueue<Int>()
        val music = AudioStream(ByteArray(32), 102)
        val guidance = AudioStream(ByteArray(32), 101)
        val siri = AudioStream(ByteArray(32), 100)
        val replacement = AudioStream(ByteArray(32), 102)
        DatagramSocket().use { sender ->
            try {
                val musicPort = music.listen(receiver(musicPackets)).first
                send(sender, musicPort, 1, musicPackets)
                val guidancePort = guidance.listen(receiver(guidancePackets)).first
                send(sender, guidancePort, 2, guidancePackets)
                send(sender, musicPort, 3, musicPackets)
                guidance.close()
                send(sender, musicPort, 4, musicPackets)
                music.close()
                val siriPort = siri.listen(receiver(voicePackets)).first
                send(sender, siriPort, 5, voicePackets)
                siri.close()
                val resumedMusicPort = replacement.listen(receiver(musicPackets)).first
                send(sender, resumedMusicPort, 6, musicPackets)
            } finally { listOf(music, guidance, siri, replacement).forEach { it.close() } }
        }
    }

    private fun receiver(packets: LinkedBlockingQueue<Int>) = object : AudioStream.Listener {
        override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
            // Synthetic short packets exercise socket delivery without keys or recorded audio.
            packets.add(wire[0].toInt())
        }
    }

    private fun send(sender: DatagramSocket, port: Int, value: Int, received: LinkedBlockingQueue<Int>) {
        sender.send(DatagramPacket(byteArrayOf(value.toByte()), 1, InetAddress.getByName("::1"), port))
        assertEquals(value, received.poll(2, TimeUnit.SECONDS))
    }
}
