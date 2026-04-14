package com.meshaudio.mesh

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class AudioStreamClient(
    private val host: String,
    private val port: Int,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var socket: Socket? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "mesh-receiver") {
            val sock = Socket()
            socket = sock
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 8000)
            } catch (e: Exception) {
                running = false
                socket = null
                onError("Connect failed: ${e.message}")
                return@thread
            }
            val input = DataInputStream(sock.getInputStream())
            val minBuf =
                AudioTrack.getMinBufferSize(
                    StreamProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBuf <= 0) {
                onError("AudioTrack not supported")
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                socket = null
                running = false
                return@thread
            }
            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(StreamProtocol.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            try {
                track.play()
            } catch (e: Exception) {
                onError("Playback failed: ${e.message}")
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                socket = null
                running = false
                return@thread
            }

            val header = ByteArray(StreamProtocol.HEADER_SIZE)
            val pcmShorts = ShortArray(4096)
            try {
                while (running) {
                    input.readFully(header)
                    val parsed = StreamProtocol.parseHeader(header) ?: break
                    val (_, len) = parsed
                    if (len <= 0 || len > 1_000_000) break
                    val body = ByteArray(len)
                    input.readFully(body)
                    var shorts = len / 2
                    if (shorts > pcmShorts.size) {
                        onError("Frame too large")
                        break
                    }
                    var i = 0
                    while (i < shorts) {
                        val lo = body[i * 2].toInt() and 0xFF
                        val hi = body[i * 2 + 1].toInt() and 0xFF
                        pcmShorts[i] = ((hi shl 8) or lo).toShort()
                        i++
                    }
                    track.write(pcmShorts, 0, shorts)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive loop", e)
            } finally {
                try {
                    track.stop()
                } catch (_: Exception) {
                }
                track.release()
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                socket = null
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        worker?.interrupt()
    }

    companion object {
        private const val TAG = "AudioStreamClient"
    }
}
