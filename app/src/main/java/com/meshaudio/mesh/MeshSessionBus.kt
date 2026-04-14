package com.meshaudio.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MeshSessionBus {
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _listeningPort = MutableStateFlow<Int?>(null)
    val listeningPort: StateFlow<Int?> = _listeningPort.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setClients(n: Int) {
        _clientCount.value = n
    }

    fun setPort(port: Int?) {
        _listeningPort.value = port
    }

    fun setStatus(message: String) {
        _status.value = message
    }

    fun setActive(running: Boolean) {
        _active.value = running
    }
}
