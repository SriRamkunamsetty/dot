package com.dj.dot.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The connected Bluetooth earphones and the battery level they report. Android only exposes one
 * overall level to regular apps; per-earbud and case levels are reserved for system apps.
 */
@SuppressLint("MissingPermission")
class Headphones(private val context: Context) {
    data class Info(val name: String, val battery: Int?, val address: String? = null) {
        /** Whether the Bluetooth device at [address] is these earphones. */
        fun isDevice(address: String?) = address != null && address == this.address
    }

    private val _connected = MutableStateFlow<Info?>(null)
    val connected: StateFlow<Info?> = _connected
    private var started = false

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || !hasPermission()) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_BATTERY_LEVEL_CHANGED) {
                val current = _connected.value
                if (current != null) {
                    // Watches and fitness trackers send this broadcast too; their levels aren't the earphones'.
                    val sender = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (!current.isDevice(sender?.address)) return
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (level in 0..100) {
                        _connected.value = current.copy(battery = level)
                        return
                    }
                }
            }
            refresh()
        }
    }

    /**
     * Queries connected audio devices across A2DP and LE Audio (API 33+),
     * matching against active audio outputs to pick the device currently playing.
     */
    fun refresh() {
        if (!hasPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val profilesToQuery = if (Build.VERSION.SDK_INT >= 33) {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.LE_AUDIO)
        } else {
            listOf(BluetoothProfile.A2DP)
        }
        val allConnected = mutableListOf<BluetoothDevice>()
        var pending = profilesToQuery.size

        fun checkComplete() {
            if (pending > 0) return
            val audioManager = context.getSystemService(AudioManager::class.java)
            val activeOutputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.mapNotNull { it.address.takeIf { addr -> !addr.isNullOrEmpty() } }
                ?.toSet() ?: emptySet()

            val chosenAddress = pickActiveDevice(allConnected.mapNotNull { it.address }, activeOutputs)
            val chosen = allConnected.firstOrNull { it.address == chosenAddress }
            _connected.value = chosen?.let { d ->
                Info(
                    runCatching { (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name }.getOrNull() ?: "Earphones",
                    batteryOf(d),
                    runCatching { d.address }.getOrNull(),
                )
            }
        }

        for (profile in profilesToQuery) {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrNull() ?: emptyList()
                    allConnected.addAll(devices)
                    adapter.closeProfileProxy(p, proxy)
                    pending--
                    checkComplete()
                }

                override fun onServiceDisconnected(p: Int) {
                    pending--
                    checkComplete()
                }
            }
            val started = runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)
            if (!started) {
                pending--
                checkComplete()
            }
        }
    }

    /** The level the earphones report over Bluetooth, if they report one. */
    private fun batteryOf(device: BluetoothDevice): Int? = runCatching {
        device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        /**
         * Picks the active audio device among connected devices by matching against
         * active audio output addresses, falling back to the first connected device.
         */
        fun pickActiveDevice(
            connectedAddresses: List<String>,
            activeOutputAddresses: Set<String>,
        ): String? {
            if (connectedAddresses.isEmpty()) return null
            return connectedAddresses.firstOrNull { it in activeOutputAddresses } ?: connectedAddresses.first()
        }
    }
}
