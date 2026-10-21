package com.tablet.penemulator

import android.Manifest
import android.annotation.SuppressLint
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

        // Keep screen active while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissionsAndInit()
    }

    private fun setupUI() {
        // Aspect ratio selection
        binding.toggleAspectRatio.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnRatio16_9 -> binding.canvasView.aspectRatioMode = TabletCanvasView.AspectRatioMode.MONITOR_16_9
                    R.id.btnRatio16_10 -> binding.canvasView.aspectRatioMode = TabletCanvasView.AspectRatioMode.TABLET_16_10
                    R.id.btnRatio21_9 -> binding.canvasView.aspectRatioMode = TabletCanvasView.AspectRatioMode.ULTRAWIDE_21_9
                }
            }
        }

        // Hide / Show UI for distraction-free drawing
        binding.btnImmersive.setOnClickListener {
            binding.cardTopBar.visibility = View.GONE
            binding.fabShowUI.visibility = View.VISIBLE
        }

        binding.fabShowUI.setOnClickListener {
            binding.cardTopBar.visibility = View.VISIBLE
            binding.fabShowUI.visibility = View.GONE
        }

        // Connect button
        binding.btnConnect.setOnClickListener {
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

        // Wire canvas events to Bluetooth HID
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
                        updateStatus("HID Registration Failed", Color.parseColor("#F87171"))
                    }
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
        pairedDevicesList.clear()
        pairedDevicesList.addAll(paired)

        val names = pairedDevicesList.map { device ->
            val name = device.name ?: "Unknown Device"
            "$name (${device.address})"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerDevices.adapter = adapter

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
