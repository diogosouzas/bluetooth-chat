package com.sdiogosouza.bluetoothchat.data.chat

import android.bluetooth.BluetoothSocket
import com.sdiogosouza.bluetoothchat.domain.chat.BluetoothMessage
import com.sdiogosouza.bluetoothchat.domain.chat.TransferFailedException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class BluetoothDataTransferService(private val socket: BluetoothSocket) {

    fun listenForIncomingMessages(): Flow<BluetoothMessage> = flow {
        if (!socket.isConnected) return@flow

        while (true) {
            val message = try {
                readMessage()
            } catch (e: TransferFailedException) {
                return@flow
            }

            emit(message)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun sendMessage(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            socket.outputStream.write(bytes)
            return@withContext true
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun readMessage(): BluetoothMessage {
        val buffer = ByteArray(1024)
        val byteCount = socket.inputStream.read(buffer)

        return buffer.decodeToString(endIndex = byteCount)
            .toBluetoothMessage(isFromLocalUser = false)
    }
}
