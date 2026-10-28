package com.tablet.penemulator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
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
        fun onRegistrationStatusChanged(message: String) {}
        fun onPairingInProgress(device: BluetoothDevice?) {}
    }

    private enum class RegState {
        UNREGISTERED,
        REGISTERING,
        REGISTERED,
        UNREGISTERING_FOR_RETRY
    }

    private val tag = "BluetoothHidManager"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    var hidDevice: BluetoothHidDevice? = null
        private set

    var connectedDevice: BluetoothDevice? = null
        private set

    // Holds device reference during initial pairing handshake before BOND_BONDED
    private var pendingBondedDevice: BluetoothDevice? = null

    var isRegistered = false
        private set

    private var regState = RegState.UNREGISTERED
    private var retryCount = 0
    private val maxRetries = 3

    var isTurboMode = false
        private set

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "BluetoothHidDevice service connected")
                hidDevice = proxy as BluetoothHidDevice
                mainHandler.post {
                    listener.onServiceConnected()
                }
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "BluetoothHidDevice service disconnected")
                mainHandler.removeCallbacks(retryRunnable)
                hidDevice = null
                isRegistered = false
                regState = RegState.UNREGISTERED
                mainHandler.post {
                    listener.onAppRegistered(false)
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(tag, "onAppStatusChanged: registered=$registered, device=$pluggedDevice, state=$regState, retries=$retryCount")

            if (registered) {
                isRegistered = true
                regState = RegState.REGISTERED
                retryCount = 0
                mainHandler.removeCallbacks(retryRunnable)
                mainHandler.post {
                    listener.onAppRegistered(true)
                }
                checkActiveConnection()
            } else {
                isRegistered = false
                if (regState == RegState.UNREGISTERING_FOR_RETRY) {
                    // Stale unregistration confirmed by Android BT stack. Settle briefly, then register cleanly.
                    Log.d(tag, "Unregistration completed. Proceeding with clean registration attempt...")
                    mainHandler.removeCallbacks(retryRunnable)
                    mainHandler.postDelayed({
                        regState = RegState.UNREGISTERED
                        executeRegistration(attemptDefaultQos = retryCount >= 2)
                    }, 250)
                } else if (regState == RegState.REGISTERING) {
                    // Registration attempt rejected asynchronously
                    Log.w(tag, "Registration was rejected asynchronously by BT service.")
                    handleRegistrationFailure()
                } else {
                    regState = RegState.UNREGISTERED
                    mainHandler.post {
                        listener.onAppRegistered(false)
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(tag, "onConnectionStateChanged: device=${device?.name}, state=$state, bondState=${device?.bondState}")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (device != null) {
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        connectedDevice = device
                        pendingBondedDevice = null
                        mainHandler.post {
                            listener.onConnectionStateChanged(device, state)
                        }
                    } else {
                        Log.d(tag, "Connection detected during initial pairing handshake (bondState=${device.bondState}). Holding pending connection...")
                        pendingBondedDevice = device
                        mainHandler.post {
                            listener.onPairingInProgress(device)
                        }
                    }
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device?.address) {
                    connectedDevice = null
                }
                if (pendingBondedDevice?.address == device?.address) {
                    pendingBondedDevice = null
                }
                mainHandler.post {
                    listener.onConnectionStateChanged(device, state)
                }
            } else {
                mainHandler.post {
                    listener.onConnectionStateChanged(device, state)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(tag, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
            if (id == HidDescriptor.REPORT_ID_PEN || id == 0.toByte()) {
                val emptyReport = HidDescriptor.buildReport(
                    tipDown = false,
                    barrelButton = false,
                    inRange = false,
                    x = 0,
                    y = 0,
                    pressure = 0
                )
                try {
                    hidDevice?.replyReport(device, type, id, emptyReport)
                } catch (e: SecurityException) {
                    Log.e(tag, "Permission error on replyReport", e)
                }
            } else {
                try {
                    hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                } catch (_: Exception) {}
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(tag, "onSetReport: type=$type, id=$id")
            try {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Exception) {}
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
        val proxy = hidDevice
        if (proxy == null) {
            Log.w(tag, "registerHidApp called before proxy ready; initializing proxy...")
            initialize()
            return
        }

        if (isRegistered || regState == RegState.REGISTERED) {
            Log.d(tag, "HID App already registered.")
            return
        }

        if (regState == RegState.REGISTERING || regState == RegState.UNREGISTERING_FOR_RETRY) {
            Log.d(tag, "Registration already in progress ($regState)")
            return
        }

        retryCount = 0
        executeRegistration(attemptDefaultQos = false)
    }

    @SuppressLint("MissingPermission")
    private fun executeRegistration(attemptDefaultQos: Boolean) {
        val proxy = hidDevice ?: return
        regState = RegState.REGISTERING

        val statusMsg = if (retryCount > 0) {
            "Registering HID (Retry $retryCount/$maxRetries)..."
        } else {
            "Registering HID..."
        }
        mainHandler.post {
            listener.onRegistrationStatusChanged(statusMsg)
        }

        // Subclass: SUBCLASS2_DIGITIZER_TABLET declares an external precision graphics tablet
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Tab S6 Lite Digitizer",
            "S-Pen Graphics Tablet",
            "OpenTablet",
            BluetoothHidDevice.SUBCLASS2_DIGITIZER_TABLET,
            HidDescriptor.REPORT_DESCRIPTOR
        )

        // Standard, robust Best Effort QoS profile for low-latency tablet polling
        val (inQos, outQos) = if (attemptDefaultQos || retryCount >= 2) {
            Log.d(tag, "Applying default (null) QoS settings for controller compatibility")
            Pair(null, null)
        } else {
            val latencyUs = if (isTurboMode) 7500 else 11250
            val tokenRate = if (isTurboMode) 1600 else 800
            val inQ = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                tokenRate,
                9,
                1000,
                latencyUs,
                BluetoothHidDeviceAppQosSettings.MAX
            )
            val outQ = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                tokenRate,
                9,
                1000,
                latencyUs,
                BluetoothHidDeviceAppQosSettings.MAX
            )
            Pair(inQ, outQ)
        }

        try {
            val enqueued = proxy.registerApp(sdpSettings, inQos, outQos, executor, hidCallback)
            Log.d(tag, "proxy.registerApp returned: $enqueued (attempt=$retryCount, turbo=$isTurboMode)")
            if (!enqueued) {
                // Common Android issue: A previous instance of the app left Bluetooth stack in 'isAppRegistered=true'.
                // Must unregister first to clear the stale binder registration before trying again.
                Log.w(tag, "registerApp was rejected immediately. Clearing stale registration and retrying...")
                handleRegistrationFailure()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to register HID app", e)
            handleRegistrationFailure()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleRegistrationFailure() {
        val proxy = hidDevice

        if (retryCount < maxRetries && proxy != null) {
            retryCount++
            Log.w(tag, "Initiating self-healing reset and retry ($retryCount/$maxRetries)...")
            mainHandler.post {
                listener.onRegistrationStatusChanged("Resetting HID... ($retryCount/$maxRetries)")
            }

            regState = RegState.UNREGISTERING_FOR_RETRY
            try {
                // Clear any stale registration from Bluetooth daemon
                proxy.unregisterApp()
            } catch (e: Exception) {
                Log.w(tag, "Error during proxy.unregisterApp()", e)
            }

            // Fallback timer in case onAppStatusChanged(false) does not fire from unregisterApp
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, 600)
        } else {
            regState = RegState.UNREGISTERED
            isRegistered = false
            Log.e(tag, "All HID registration attempts exhausted.")
            mainHandler.post {
                listener.onAppRegistered(false)
            }
        }
    }

    private val retryRunnable = Runnable {
        if (regState == RegState.UNREGISTERING_FOR_RETRY) {
            Log.d(tag, "Retry timeout elapsed. Re-attempting registration...")
            regState = RegState.UNREGISTERED
            executeRegistration(attemptDefaultQos = retryCount >= 2)
        }
    }

    /**
     * Manually resets and retries HID registration.
     * Can be invoked by the user tapping the status badge or connect button.
     */
    @SuppressLint("MissingPermission")
    fun retryRegistration() {
        Log.d(tag, "Manual retryRegistration called")
        mainHandler.removeCallbacks(retryRunnable)
        retryCount = 0
        regState = RegState.UNREGISTERED

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            mainHandler.post {
                listener.onError("Please enable Bluetooth first.")
                listener.onAppRegistered(false)
            }
            return
        }

        val proxy = hidDevice
        if (proxy == null) {
            mainHandler.post {
                listener.onRegistrationStatusChanged("Reconnecting Bluetooth profile...")
            }
            initialize()
        } else {
            mainHandler.post {
                listener.onRegistrationStatusChanged("Resetting HID service...")
            }
            regState = RegState.UNREGISTERING_FOR_RETRY
            try {
                proxy.unregisterApp()
            } catch (_: Exception) {}

            mainHandler.postDelayed({
                regState = RegState.UNREGISTERED
                executeRegistration(attemptDefaultQos = false)
            }, 350)
        }
    }

    /**
     * Toggles Turbo Mode (low-latency QoS).
     * If already registered and idle (disconnected), safely re-registers with new QoS.
     */
    @SuppressLint("MissingPermission")
    fun setTurboMode(enabled: Boolean, onNeedsReconnect: (() -> Unit)? = null) {
        if (isTurboMode == enabled) return
        isTurboMode = enabled

        if (isRegistered && connectedDevice == null) {
            Log.d(tag, "Re-registering HID to apply new Turbo mode QoS ($enabled)")
            retryRegistration()
        } else if (connectedDevice != null) {
            onNeedsReconnect?.invoke()
        }
    }

    /**
     * Checks if a device has an active profile connection with BluetoothHidDevice.
     */
    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        if (connectedDevice?.address == device.address) return true
        val proxy = hidDevice ?: return false
        return try {
            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Handles device bonding completion. If the device was already connected
     * during the pairing handshake or by the host, promotes it immediately
     * to the active session without disruptive reconnect attempts.
     */
    @SuppressLint("MissingPermission")
    fun onDeviceBonded(device: BluetoothDevice) {
        Log.d(tag, "onDeviceBonded: device=${device.name ?: device.address}")
        val isAlreadyConnected = (pendingBondedDevice?.address == device.address) ||
                (connectedDevice?.address == device.address) ||
                isDeviceConnected(device) ||
                (hidDevice?.connectedDevices?.any { it.address == device.address } == true)

        if (isAlreadyConnected) {
            Log.i(tag, "Device ${device.name ?: device.address} is already connected via HID. Activating session!")
            pendingBondedDevice = null
            connectedDevice = device
            mainHandler.post {
                listener.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
            }
        } else {
            Log.d(tag, "Device bonded but not yet connected. Attempting connection...")
            connect(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val proxy = hidDevice
        if (proxy == null) {
            Log.w(tag, "connect(): hidDevice proxy is null")
            mainHandler.post {
                listener.onError("Bluetooth HID profile not ready.")
            }
            return false
        }
        if (!isRegistered) {
            Log.w(tag, "connect(): not registered")
            mainHandler.post {
                listener.onError("HID service not registered. Retrying...")
            }
            retryRegistration()
            return false
        }
        if (connectedDevice?.address == device.address || isDeviceConnected(device)) {
            Log.d(tag, "Device ${device.address} is already connected!")
            connectedDevice = device
            mainHandler.post {
                listener.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
            }
            return true
        }
        return try {
            val queued = proxy.connect(device)
            Log.d(tag, "Connecting to ${device.name ?: device.address} -> proxy.connect returned: $queued")
            if (!queued) {
                mainHandler.post {
                    listener.onError("Android rejected connection to ${device.name ?: device.address}. Is device paired?")
                }
            }
            queued
        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to device", e)
            mainHandler.post {
                listener.onError("Failed to connect: ${e.message}")
            }
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val proxy = hidDevice
        val deviceToDisconnect = connectedDevice ?: proxy?.connectedDevices?.firstOrNull()
        Log.d(tag, "disconnect() requested for: ${deviceToDisconnect?.name ?: deviceToDisconnect?.address}")

        if (deviceToDisconnect != null && proxy != null) {
            try {
                proxy.disconnect(deviceToDisconnect)
            } catch (e: Exception) {
                Log.e(tag, "Failed to call proxy.disconnect", e)
            }
        }

        // Proactively clear local state immediately so UI updates and doesn't get stuck
        connectedDevice = null
        pendingBondedDevice = null

        mainHandler.post {
            listener.onConnectionStateChanged(deviceToDisconnect, BluetoothProfile.STATE_DISCONNECTED)
        }
    }

    /**
     * Inspects proxy for existing established connections (e.g. initiated by PC or retained across app resumes)
     */
    @SuppressLint("MissingPermission")
    fun checkActiveConnection() {
        val proxy = hidDevice ?: return
        val active = proxy.connectedDevices?.firstOrNull()
        if (active != null) {
            if (connectedDevice?.address != active.address) {
                Log.d(tag, "Found active connected device: ${active.name ?: active.address}")
                connectedDevice = active
                mainHandler.post {
                    listener.onConnectionStateChanged(active, BluetoothProfile.STATE_CONNECTED)
                }
            }
        } else if (connectedDevice != null) {
            Log.d(tag, "Active device no longer connected in proxy.")
            val lost = connectedDevice
            connectedDevice = null
            mainHandler.post {
                listener.onConnectionStateChanged(lost, BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPenReport(
        tipDown: Boolean,
        barrelButton: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int
    ): Boolean {
        val proxy = hidDevice ?: return false
        val device = connectedDevice ?: run {
            // Self-healing fallback: if an active connection exists in proxy, recover it
            val active = proxy.connectedDevices?.firstOrNull()
            if (active != null) {
                connectedDevice = active
                active
            } else {
                return false
            }
        }

        val reportData = HidDescriptor.buildReport(
            tipDown = tipDown,
            barrelButton = barrelButton,
            inRange = inRange,
            x = x,
            y = y,
            pressure = pressure
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
        mainHandler.removeCallbacks(retryRunnable)
        // Intentionally do NOT call proxy.unregisterApp() here.
        // Preserving the registration in the Bluetooth daemon allows the tablet to
        // remain paired and reconnect seamlessly without corrupting Fluoride's async state machine.
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            hidDevice = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing proxy", e)
        }
    }
}
