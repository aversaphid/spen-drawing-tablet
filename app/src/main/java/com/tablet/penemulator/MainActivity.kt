package com.tablet.penemulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tablet.penemulator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BluetoothHidManager

    companion object {
        private const val PREFS_NAME = "pen_emulator_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_connected_device_address"
    }

    private var pairedDevicesList = mutableListOf<BluetoothDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            initBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for digitizer emulation.", Toast.LENGTH_LONG).show()
            updateStatus("Permissions Denied", Color.parseColor("#F87171"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while tablet app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissionsAndInit()
    }

    override fun onResume() {
        super.onResume()
        if (::bluetoothManager.isInitialized) {
            if (bluetoothManager.isRegistered) {
                refreshPairedDevices()
            } else {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null && adapter.isEnabled && bluetoothManager.hidDevice == null) {
                    bluetoothManager.initialize()
                }
            }
        }
    }

    private fun setupUI() {
        // Status Badge Click -> Retry Registration if in failed / disconnected state
        binding.tvStatus.setOnClickListener {
            if (!bluetoothManager.isRegistered) {
                Toast.makeText(this, "Retrying HID registration...", Toast.LENGTH_SHORT).show()
                bluetoothManager.retryRegistration()
            }
        }

        // 1. Aspect Ratio Selection
        binding.toggleAspectRatio.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnRatio16_9 -> binding.canvasView.aspectRatioMode = TabletCanvasView.AspectRatioMode.MONITOR_16_9
                    R.id.btnRatio16_10 -> binding.canvasView.aspectRatioMode = TabletCanvasView.AspectRatioMode.TABLET_16_10
                }
            }
        }

        // 2. Turbo Bluetooth Polling Toggle
        binding.btnTurbo.setOnClickListener {
            val targetTurbo = !bluetoothManager.isTurboMode
            bluetoothManager.setTurboMode(targetTurbo) {
                Toast.makeText(this, "Turbo mode QoS will apply upon reconnecting.", Toast.LENGTH_SHORT).show()
            }

            if (bluetoothManager.isTurboMode) {
                binding.btnTurbo.text = "Turbo ⚡ [ON]"
                binding.btnTurbo.setTextColor(Color.parseColor("#38BDF8"))
                Toast.makeText(this, "Turbo Polling ON (High-Rate ~133Hz, 7.5ms interval)", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnTurbo.text = "Turbo ⚡ [OFF]"
                binding.btnTurbo.setTextColor(Color.parseColor("#9CA3AF"))
                Toast.makeText(this, "Standard Polling (Balanced battery)", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Blackout Battery Saver Mode
        binding.btnBlackout.setOnClickListener {
            enableBlackoutMode()
        }

        // Only wake on finger touch on this bar (ignore S-Pen so drawing never closes blackout)
        binding.btnWakeUI.setOnTouchListener { _, event ->
            val tool = event.getToolType(0)
            if (tool == android.view.MotionEvent.TOOL_TYPE_STYLUS ||
                tool == android.view.MotionEvent.TOOL_TYPE_ERASER) {
                // Reject pen touches on the wake button
                false
            } else {
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    disableBlackoutMode()
                }
                true
            }
        }

        // 5. Connect / Disconnect Button
        binding.btnConnect.setOnClickListener {
            if (!bluetoothManager.isRegistered) {
                Toast.makeText(this, "HID not registered. Resetting and retrying...", Toast.LENGTH_SHORT).show()
                bluetoothManager.retryRegistration()
                return@setOnClickListener
            }

            if (bluetoothManager.connectedDevice != null) {
                bluetoothManager.disconnect()
            } else {
                val selectedIndex = binding.spinnerDevices.selectedItemPosition
                if (selectedIndex in pairedDevicesList.indices) {
                    val targetDevice = pairedDevicesList[selectedIndex]
                    bluetoothManager.connect(targetDevice)
                } else {
                    Toast.makeText(this, "Please pair your PC in Bluetooth settings first.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 6. Wire canvas pen events to Bluetooth HID
        binding.canvasView.onPenEventListener = { tipDown, barrelButton, inRange, x, y, pressure, tiltX, tiltY ->
            bluetoothManager.sendPenReport(
                tipDown = tipDown,
                barrelButton = barrelButton,
                inRange = inRange,
                x = x,
                y = y,
                pressure = pressure,
                tiltX = tiltX,
                tiltY = tiltY
            )
        }
    }

    private fun enableBlackoutMode() {
        binding.cardTopBar.visibility = View.GONE
        binding.btnWakeUI.visibility = View.VISIBLE
        binding.canvasView.isBlackoutMode = true

        // Dim screen to comfortable low brightness (5%) so it's readable but saves battery
        val lp = window.attributes
        lp.screenBrightness = 0.06f
        window.attributes = lp

        Toast.makeText(this, "Blackout Active • Tap top bar with finger to exit", Toast.LENGTH_SHORT).show()
    }

    private fun disableBlackoutMode() {
        binding.cardTopBar.visibility = View.VISIBLE
        binding.btnWakeUI.visibility = View.GONE
        binding.canvasView.isBlackoutMode = false

        // Restore normal screen brightness
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun checkPermissionsAndInit() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initBluetooth()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initBluetooth() {
        bluetoothManager = BluetoothHidManager(this, object : BluetoothHidManager.Callback {
            override fun onServiceConnected() {
                runOnUiThread {
                    updateStatus("HID Profile Ready", Color.parseColor("#38BDF8"))
                }
            }

            override fun onAppRegistered(registered: Boolean) {
                runOnUiThread {
                    if (registered) {
                        updateStatus("Ready to Pair / Connect", Color.parseColor("#34D399"))
                        refreshPairedDevices()
                    } else {
                        updateStatus("HID Reg Failed • Tap to Retry", Color.parseColor("#F87171"))
                        Toast.makeText(
                            this@MainActivity,
                            "HID registration failed. Tap status badge to retry, or toggle Bluetooth.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            override fun onRegistrationStatusChanged(message: String) {
                runOnUiThread {
                    updateStatus(message, Color.parseColor("#FBBF24"))
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                runOnUiThread {
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            val deviceName = device?.name ?: device?.address ?: "Host"
                            updateStatus("Connected: $deviceName", Color.parseColor("#34D399"))
                            binding.btnConnect.text = getString(R.string.disconnect)

                            // Save as last connected device
                            device?.address?.let { addr ->
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putString(KEY_LAST_DEVICE_ADDRESS, addr)
                                    .apply()
                            }
                        }
                        BluetoothProfile.STATE_CONNECTING -> {
                            updateStatus("Connecting...", Color.parseColor("#FBBF24"))
                        }
                        BluetoothProfile.STATE_DISCONNECTING -> {
                            updateStatus("Disconnecting...", Color.parseColor("#FBBF24"))
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            updateStatus("Disconnected", Color.parseColor("#9CA3AF"))
                            binding.btnConnect.text = getString(R.string.connect)
                        }
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })

        val success = bluetoothManager.initialize()
        if (success) {
            updateStatus("Initializing HID...", Color.parseColor("#FBBF24"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        val paired = bluetoothManager.getPairedDevices()
        val lastAddress = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDRESS, null)

        // Prioritize last connected device as the first initial tried device (index 0)
        val sortedList = if (!lastAddress.isNullOrEmpty()) {
            paired.sortedByDescending { it.address.equals(lastAddress, ignoreCase = true) }
        } else {
            paired
        }

        pairedDevicesList.clear()
        pairedDevicesList.addAll(sortedList)

        val names = pairedDevicesList.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() } ?: device.address ?: "Device"
            val isLast = device.address.equals(lastAddress, ignoreCase = true)
            if (isLast) "⭐ $name" else name
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerDevices.adapter = adapter

        if (pairedDevicesList.isNotEmpty()) {
            binding.spinnerDevices.setSelection(0)
        }

        if (names.isEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair your PC in Android Bluetooth Settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus(text: String, color: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::bluetoothManager.isInitialized) {
            bluetoothManager.destroy()
        }
    }
}
