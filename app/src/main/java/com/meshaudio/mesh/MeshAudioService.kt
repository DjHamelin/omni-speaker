package com.meshaudio.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MeshAudioService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsd by lazy { MeshNsd(this) }
    private var server: AudioStreamServer? = null
    private var client: AudioStreamClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_TX -> {
                val both = intent.getBooleanExtra(EXTRA_LOCAL_MONITOR, false)
                startTransmitting(both)
            }
            ACTION_START_RX -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, StreamProtocol.DEFAULT_PORT)
                startReceiving(host, port)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        server?.stop()
        server = null
        client?.stop()
        client = null
        nsd.shutdown()
        MeshSessionBus.setActive(false)
        MeshSessionBus.setStatus("")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun startTransmitting(localMonitor: Boolean) {
        stopAll()
        MeshSessionBus.setActive(true)
        val notification = buildNotification(
            getString(R.string.notify_transmitting_title),
            getString(R.string.notify_transmitting_text),
        )
        startForegroundWithTypes(notification, transmitting = true, localMonitor = localMonitor)

        server =
            AudioStreamServer(
                requestedPort = StreamProtocol.DEFAULT_PORT,
                localMonitor = localMonitor,
                onReady = { port ->
                    val label = serviceDisplayName()
                    nsd.register(
                        port = port,
                        serviceName = label,
                        onRegistered = { name ->
                            MeshSessionBus.setStatus(
                                getString(R.string.status_tx_registered, name, port),
                            )
                        },
                        onFailed = { msg ->
                            MeshSessionBus.setStatus(msg)
                        },
                    )
                },
                onError = { msg ->
                    MeshSessionBus.setStatus(msg)
                    mainHandler.post {
                        stopAll()
                        stopSelf()
                    }
                },
            )
        server?.start()
    }

    private fun startReceiving(host: String, port: Int) {
        stopAll()
        MeshSessionBus.setActive(true)
        val notification = buildNotification(
            getString(R.string.notify_receiving_title),
            getString(R.string.notify_receiving_text, host),
        )
        startForegroundWithTypes(notification, transmitting = false, localMonitor = false)

        MeshSessionBus.setStatus(getString(R.string.status_rx_connecting, host, port))
        client =
            AudioStreamClient(
                host = host,
                port = port,
                onError = { msg ->
                    MeshSessionBus.setStatus(msg)
                    mainHandler.post {
                        stopAll()
                        stopSelf()
                    }
                },
            )
        client?.start()
    }

    private fun serviceDisplayName(): String {
        val model = Build.MODEL?.replace("\\s+".toRegex(), "-") ?: "Mesh"
        return "${model}-MeshAudio"
    }

    private fun buildNotification(title: String, text: String): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch =
                NotificationChannel(
                    channelId,
                    getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            mgr.createNotificationChannel(ch)
        }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithTypes(
        notification: Notification,
        transmitting: Boolean,
        localMonitor: Boolean,
    ) {
        val types =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when {
                    transmitting && localMonitor ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    transmitting ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
            } else {
                0
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                types,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.meshaudio.mesh.STOP"
        const val ACTION_START_TX = "com.meshaudio.mesh.START_TX"
        const val ACTION_START_RX = "com.meshaudio.mesh.START_RX"
        const val EXTRA_LOCAL_MONITOR = "local_monitor"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        private const val CHANNEL_ID = "mesh_audio_channel"
        private const val NOTIFICATION_ID = 42

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MeshAudioService::class.java).setAction(ACTION_STOP))
        }

        fun startTransmit(ctx: Context, localMonitor: Boolean) {
            val i =
                Intent(ctx, MeshAudioService::class.java).apply {
                    action = ACTION_START_TX
                    putExtra(EXTRA_LOCAL_MONITOR, localMonitor)
                }
            ctx.startForegroundService(i)
        }

        fun startReceive(ctx: Context, host: String, port: Int) {
            val i =
                Intent(ctx, MeshAudioService::class.java).apply {
                    action = ACTION_START_RX
                    putExtra(EXTRA_HOST, host)
                    putExtra(EXTRA_PORT, port)
                }
            ctx.startForegroundService(i)
        }
    }
}
