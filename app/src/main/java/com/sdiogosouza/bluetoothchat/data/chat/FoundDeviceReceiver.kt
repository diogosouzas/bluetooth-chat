package com.sdiogosouza.bluetoothchat.data.chat

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_FOUND
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FoundDeviceReceiver(private val onDeviceFound: (BluetoothDevice) -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_FOUND -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(
                    EXTRA_DEVICE
                )
                device?.let { onDeviceFound(it) }
            }
        }
    }
}

