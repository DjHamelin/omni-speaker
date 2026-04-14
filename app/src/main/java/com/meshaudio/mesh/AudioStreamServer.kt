package com.meshaudio.mesh

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AudioStreamServer(
    private val requestedPort: Int,
    private val localMonitor: Boolean,
    private val onReady: (Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientWriter>()
    @Volatile
    private var running = false
    private var acceptThread: Thread? = null
    private var captureThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        try {
            serverSocket = try {
                ServerSocket(requestedPort)
            } catch (_: Exception) {
                ServerSocket(0)
            }
        } catch (e: Exception) {
            running = false
            onError("Could not open server socket: ${e.message}")
            return
        }
        val port = serverSocket!!.localPort
        MeshSessionBus.setPort(port)
        onReady(port)

        acceptThread = thread(name = "mesh-accept") {
            val ss = serverSocket ?: return@thread
            while (running) {
                try {
                    val socket = ss.accept()
                    socket.tcpNoDelay = true
                    val writer = ClientWriter(socket)
                    clients.add(writer)
                    MeshSessionBus.setClients(clients.size)
                    thread(name = "mesh-client-${socket.inetAddress}") {
                        try {
                            socket.getInputStream().readBytes()
                        } catch (_: Exception) {
                        } finally {
                            clients.remove(writer)
                            MeshSessionBus.setClients(clients.size)
                            try {
                                socket.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed", e)
                }
            }
        }

        captureThread = thread(name = "mesh-capture") {
            val minBuf =
                AudioRecord.getMinBufferSize(
                    StreamProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBuf <= 0) {
                onError("AudioRecord not supported on this device")
                stop()
                return@thread
            }
            val record =
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    StreamProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            val track =
                if (localMonitor) {
                    android.media.AudioTrack.Builder()
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
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
                        .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    null
                }
            try {
                record.startRecording()
                track?.play()
            } catch (e: Exception) {
                onError("Mic error: ${e.message}")
                stop()
                return@thread
            }

            val pcm = ShortArray(minBuf / 2)
            var seq = 0
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val bytes = read * 2
                val header = StreamProtocol.header(seq++, bytes)
                val body = ByteArray(bytes)
                var i = 0
                while (i < read) {
                    val s = pcm[i]
                    body[i * 2] = (s.toInt() and 0xFF).toByte()
                    body[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                    i++
                }
                track?.write(pcm, 0, read)
                broadcast(header, body)
            }
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun broadcast(header: ByteArray, body: ByteArray) {
        val dead = mutableListOf<ClientWriter>()
        for (c in clients) {
            try {
                c.out.write(header)
                c.out.write(body)
                c.out.flush()
            } catch (_: IOException) {
                dead.add(c)
            }
        }
        dead.forEach { clients.remove(it) }
        if (dead.isNotEmpty()) MeshSessionBus.setClients(clients.size)
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        clients.forEach {
            try {
                it.socket.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
        MeshSessionBus.setClients(0)
        MeshSessionBus.setPort(null)
    }

    private class ClientWriter(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
    }

    companion object {
        private const val TAG = "AudioStreamServer"
    }
}
