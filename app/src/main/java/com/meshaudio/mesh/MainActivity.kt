package com.meshaudio.mesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeshAudioScreen()
            }
        }
    }
}

private enum class AppMode {
    Transmitter,
    Speaker,
    Both,
}

@Composable
private fun MeshAudioScreen() {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val clientCount by MeshSessionBus.clientCount.collectAsStateWithLifecycle()
    val listenPort by MeshSessionBus.listeningPort.collectAsStateWithLifecycle()
    val busStatus by MeshSessionBus.status.collectAsStateWithLifecycle()
    val active by MeshSessionBus.active.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(AppMode.Transmitter) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(StreamProtocol.DEFAULT_PORT.toString()) }

    val discovered = remember { mutableStateListOf<MeshBroadcast>() }
    val nsd = remember { MeshNsd(appCtx) }

    fun txPermissionsGranted(): Boolean {
        val mic = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        return mic && notif
    }

    fun rxPermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    val txPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    val rxPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    DisposableEffect(mode, active) {
        if (mode == AppMode.Speaker && !active) {
            discovered.clear()
            nsd.startDiscovery { b ->
                val key = "${b.host}:${b.port}"
                if (discovered.none { "${it.host}:${it.port}" == key }) {
                    discovered.add(b)
                }
            }
        } else {
            nsd.stopDiscovery()
        }
        onDispose {
            nsd.stopDiscovery()
        }
    }

    Scaffold { inner ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.hint_same_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == AppMode.Transmitter,
                    onClick = { if (!active) mode = AppMode.Transmitter },
                    label = { Text(stringResource(R.string.mode_transmitter)) },
                    enabled = !active,
                )
                FilterChip(
                    selected = mode == AppMode.Speaker,
                    onClick = { if (!active) mode = AppMode.Speaker },
                    label = { Text(stringResource(R.string.mode_speaker)) },
                    enabled = !active,
                )
                FilterChip(
                    selected = mode == AppMode.Both,
                    onClick = { if (!active) mode = AppMode.Both },
                    label = { Text(stringResource(R.string.mode_both)) },
                    enabled = !active,
                )
            }

            when (mode) {
                AppMode.Transmitter, AppMode.Both -> {
                    if (listenPort != null) {
                        Text(
                            text = stringResource(R.string.listening_on, listenPort!!),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.clients_connected, clientCount),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (busStatus.isNotEmpty()) {
                        Text(text = busStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.padding(0.dp))
                    if (!active) {
                        Button(
                            onClick = {
                                if (!txPermissionsGranted()) {
                                    val perms =
                                        buildList {
                                            add(Manifest.permission.RECORD_AUDIO)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }.toTypedArray()
                                    txPermLauncher.launch(perms)
                                } else {
                                    val monitor = mode == AppMode.Both
                                    MeshAudioService.startTransmit(appCtx, monitor)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (!txPermissionsGranted()) {
                                    stringResource(R.string.grant_perms_tx)
                                } else {
                                    stringResource(R.string.start_broadcast)
                                },
                            )
                        }
                    } else {
                        Button(
                            onClick = { MeshAudioService.stop(appCtx) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.stop_session))
                        }
                    }
                }
                AppMode.Speaker -> {
                    Text(
                        text = stringResource(R.string.discovered),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(discovered, key = { "${it.host}:${it.port}:${it.displayName}" }) { item ->
                            Button(
                                onClick = {
                                    if (!rxPermissionsGranted()) {
                                        rxPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        MeshAudioService.startReceive(appCtx, item.host, item.port)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !active,
                            ) {
                                Text("${item.displayName} · ${item.host}:${item.port}")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it.trim() },
                        label = { Text(stringResource(R.string.manual_host)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !active,
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.manual_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !active,
                    )
                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
                            if (manualHost.isNotBlank()) {
                                if (!rxPermissionsGranted()) {
                                    rxPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    MeshAudioService.startReceive(appCtx, manualHost, port)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !active && manualHost.isNotBlank(),
                    ) {
                        Text(
                            if (!rxPermissionsGranted()) {
                                stringResource(R.string.grant_perms_rx)
                            } else {
                                stringResource(R.string.connect)
                            },
                        )
                    }
                    if (active && busStatus.isNotEmpty()) {
                        Text(text = busStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    if (active) {
                        Button(
                            onClick = { MeshAudioService.stop(appCtx) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.stop_session))
                        }
                    }
                }
            }
        }
    }
}
