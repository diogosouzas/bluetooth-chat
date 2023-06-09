package com.sdiogosouza.bluetoothchat.data.chat

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED
import android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
import android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.sdiogosouza.bluetoothchat.domain.chat.BluetoothController
import com.sdiogosouza.bluetoothchat.domain.chat.BluetoothDeviceDomain
import com.sdiogosouza.bluetoothchat.domain.chat.BluetoothMessage
import com.sdiogosouza.bluetoothchat.domain.chat.ConnectionResult
import com.sdiogosouza.bluetoothchat.domain.chat.ConnectionResult.ConnectionEstablished
import com.sdiogosouza.bluetoothchat.domain.chat.ConnectionResult.TransferSucceeded
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy {
        bluetoothManager?.adapter
    }

    private var dataTransferService: BluetoothDataTransferService? = null
    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null

    private val _isConnected = MutableStateFlow(false)
    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    private val _errors = MutableSharedFlow<String>()
    private val foundDeviceReceiver = FoundDeviceReceiver { device ->
        _scannedDevices.update { devices ->
            val newDevice = device.toBluetoothDeviceDomain()
            if (newDevice in devices) devices else devices + newDevice
        }
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver { isConnected, bluetoothDevice ->
        if (bluetoothAdapter?.bondedDevices?.contains(bluetoothDevice) == true) {
            _isConnected.update { isConnected }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                _errors.emit("Can't connect to a non-paired device.")
            }
        }
    }

    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override val scannedDevices: StateFlow<List<BluetoothDeviceDomain>> =
        _scannedDevices.asStateFlow()

    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>> =
        _pairedDevices.asStateFlow()

    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        updatePairedDevices()
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACTION_ACL_CONNECTED)
                addAction(ACTION_ACL_DISCONNECTED)
            }
        )
    }

    override fun startDiscovery() {
        if (!hasPermission(BLUETOOTH_SCAN)) {
            return
        }

        context.registerReceiver(
            foundDeviceReceiver,
            IntentFilter(ACTION_FOUND)
        )

        updatePairedDevices()

        bluetoothAdapter?.startDiscovery()
    }

    override fun stopDiscovery() {
        if (!hasPermission(BLUETOOTH_SCAN)) {
            return
        }

        bluetoothAdapter?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> {
        return flow {
            if (!hasPermission(BLUETOOTH_CONNECT)) {
                throw SecurityException("No BLUETOOTH_CONNECT permission")
            }

            currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                "chat_service",
                UUID.fromString(SERVICE_UUID)
            )

            var shouldLoop = true
            while (shouldLoop) {
                currentClientSocket = try {
                    currentServerSocket?.accept()
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }
                emit(ConnectionEstablished)
                currentClientSocket?.let { bluetoothSocket ->
                    currentServerSocket?.close()
                    val service = BluetoothDataTransferService(bluetoothSocket)
                    dataTransferService = service

                    emitAll(
                        service
                            .listenForIncomingMessages()
                            .map {
                                TransferSucceeded(it)
                            }
                    )
                }
            }
        }.onCompletion {
            closeConnection()
        }.flowOn(Dispatchers.IO)
    }

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> {
        return flow {
            if (!hasPermission(BLUETOOTH_CONNECT)) {
                throw SecurityException("No BLUETOOTH_CONNECT permission")
            }

            currentClientSocket = bluetoothAdapter
                ?.getRemoteDevice(device.address)
                ?.createRfcommSocketToServiceRecord(
                    UUID.fromString(SERVICE_UUID)
                )
            stopDiscovery()

            currentClientSocket?.let { socket ->
                try {
                    socket.connect()
                    emit(ConnectionEstablished)

                    BluetoothDataTransferService(socket).also { message ->
                        dataTransferService = message
                        emitAll(
                            message.listenForIncomingMessages()
                                .map { TransferSucceeded(it) }
                        )
                    }
                } catch (e: IOException) {
                    socket.close()
                    currentClientSocket = null
                    emit(Error("Connection was interrupted"))
                }
            }
        }.onCompletion {
            closeConnection()
        }.flowOn(Dispatchers.IO) as Flow<ConnectionResult>
    }

    override suspend fun trySendMessage(message: String): BluetoothMessage? {
        if (!hasPermission(BLUETOOTH_CONNECT)) {
            return null
        }

        if (dataTransferService == null) {
            return null
        }

        val bluetoothMessage = BluetoothMessage(
            message = message,
            senderName = bluetoothAdapter?.name ?: "Unknown name",
            isFromLocalUser = true
        )

        dataTransferService?.sendMessage(bluetoothMessage.toByteArray())

        return bluetoothMessage
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        context.unregisterReceiver(foundDeviceReceiver)
        context.unregisterReceiver(bluetoothStateReceiver)
        closeConnection()
    }

    private fun updatePairedDevices() {
        if (!hasPermission(BLUETOOTH_CONNECT)) {
            return
        }
        bluetoothAdapter
            ?.bondedDevices
            ?.map { it.toBluetoothDeviceDomain() }
            ?.also { devices ->
                _pairedDevices.update { devices }
            }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val SERVICE_UUID = "ab810eb0-d3b0-11ed-afa1-0242ac120002"
    }
}
