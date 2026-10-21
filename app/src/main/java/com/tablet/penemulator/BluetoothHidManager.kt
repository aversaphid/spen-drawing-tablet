package com.tablet.penemulator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

class BluetoothHidManager(
    private val context: Context,
    private val listener: Callback
) {

    interface Callback {
        fun onServiceConnected()
        fun onAppRegistered(registered: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
        fun onError(message: String)
    }

    private val tag = "BluetoothHidManager"
    private val executor = Executors.newSingleThreadExecutor()

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    var hidDevice: BluetoothHidDevice? = null
        private set

    var connectedDevice: BluetoothDevice? = null
        private set

    var isRegistered = false
        private set

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "BluetoothHidDevice service connected")
                hidDevice = proxy as BluetoothHidDevice
                listener.onServiceConnected()
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "BluetoothHidDevice service disconnected")
                hidDevice = null
                isRegistered = false
                listener.onAppRegistered(false)
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(tag, "onAppStatusChanged: registered=$registered, device=$pluggedDevice")
            isRegistered = registered
            listener.onAppRegistered(registered)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(tag, "onConnectionStateChanged: device=${device?.name}, state=$state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice == device) {
                    connectedDevice = null
                }
            }
            listener.onConnectionStateChanged(device, state)
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(tag, "onGetReport: type=$type, id=$id")
            if (id == HidDescriptor.REPORT_ID_PEN) {
                // Send neutral hover-out report
                val emptyReport = HidDescriptor.buildReport(
                    tipDown = false,
                    barrelButton = false,
                    inRange = false,
                    x = 0,
                    y = 0,
                    pressure = 0,
                    tiltX = 0,
                    tiltY = 0
                )
                try {
                    hidDevice?.replyReport(device, type, id, emptyReport)
                } catch (e: SecurityException) {
                    Log.e(tag, "Permission error on replyReport", e)
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(tag, "onSetReport: type=$type, id=$id")
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        if (bluetoothAdapter == null) {
            listener.onError("Bluetooth is not supported on this device.")
            return false
        }

        if (!bluetoothAdapter!!.isEnabled) {
            listener.onError("Bluetooth is disabled. Please enable Bluetooth.")
            return false
        }

        val success = bluetoothAdapter!!.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!success) {
            listener.onError("Could not get Bluetooth HID Device profile proxy.")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun registerHidApp() {
        val proxy = hidDevice ?: return
        if (isRegistered) return

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Tab S6 Lite Digitizer",
            "S-Pen Graphics Tablet",
            "OpenTablet",
            BluetoothHidDevice.SUBCLASS2_DIGITIZER_TABLET,
            HidDescriptor.REPORT_DESCRIPTOR
        )

        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            proxy.registerApp(sdpSettings, inQos, outQos, executor, hidCallback)
            Log.d(tag, "Requested HID App registration")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register HID app", e)
            listener.onError("Failed to register HID app: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val proxy = hidDevice ?: return
        try {
            proxy.connect(device)
            Log.d(tag, "Connecting to ${device.name ?: device.address}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to device", e)
            listener.onError("Failed to connect: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val proxy = hidDevice ?: return
        val device = connectedDevice ?: return
        try {
            proxy.disconnect(device)
            Log.d(tag, "Disconnecting from ${device.name ?: device.address}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to disconnect", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPenReport(
        tipDown: Boolean,
        barrelButton: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int,
        tiltX: Int,
        tiltY: Int
    ): Boolean {
        val proxy = hidDevice ?: return false
        val device = connectedDevice ?: return false

        val reportData = HidDescriptor.buildReport(
            tipDown = tipDown,
            barrelButton = barrelButton,
            inRange = inRange,
            x = x,
            y = y,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY
        )

        return try {
            proxy.sendReport(device, HidDescriptor.REPORT_ID_PEN.toInt(), reportData)
        } catch (e: Exception) {
            Log.e(tag, "Error sending report", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        try {
            if (isRegistered) {
                hidDevice?.unregisterApp()
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (e: Exception) {
            Log.e(tag, "Error closing proxy", e)
        }
    }
}
