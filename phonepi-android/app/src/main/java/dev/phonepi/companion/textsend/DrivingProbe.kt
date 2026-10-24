package dev.phonepi.companion.textsend

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat

object DrivingProbe {
    fun isDriving(context: Context): Boolean = SendTextPolicy.drivingDetected(
        toggle = SendTextPrefs.drivingMode(context),
        androidAuto = androidAutoConnected(context),
        bluetoothCarAudio = bluetoothCarAudio(context),
        inVehicle = InVehicleState.current(context),
    )

    fun androidAutoConnected(context: Context): Boolean {
        val ui = context.getSystemService(android.app.UiModeManager::class.java) ?: return false
        return ui.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    /**
     * True when a connected Bluetooth device reports car audio or hands-free.
     * Needs BLUETOOTH_CONNECT on Android 12+. Headphones that are not hands-free do not count.
     */
    @SuppressLint("MissingPermission")
    fun bluetoothCarAudio(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val devices = buildList {
            addAll(manager.getConnectedDevices(BluetoothProfile.HEADSET))
            addAll(manager.getConnectedDevices(BluetoothProfile.A2DP))
        }
        return devices.any { device ->
            val kind = device.bluetoothClass?.deviceClass ?: return@any false
            kind == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                kind == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }
    }
}
