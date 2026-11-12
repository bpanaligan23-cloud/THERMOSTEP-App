package com.thermostep.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val serviceUuid = UUID.fromString("7b3e0001-8a6d-4f2b-9c1d-5e7a0b2c0001")
    private val rxUuid = UUID.fromString("7b3e0002-8a6d-4f2b-9c1d-5e7a0b2c0001")
    private val txUuid = UUID.fromString("7b3e0003-8a6d-4f2b-9c1d-5e7a0b2c0001")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private val found = linkedMapOf<String, BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(BleBridge(), "ThermostepBle")
        setContentView(web)
        web.loadUrl("file:///android_asset/thermostep.html")
    }

    inner class BleBridge {
        @JavascriptInterface fun connect() { main.post { beginConnect() } }
        @JavascriptInterface fun disconnect() { main.post { disconnectGatt("Disconnected") } }
        @JavascriptInterface fun send(json: String) { main.post { writeMessage(json) } }
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else if (Build.VERSION.SDK_INT >= 23) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } else emptyArray()

    private fun hasPermissions() = requiredPermissions().all {
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun beginConnect() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), 41); status("Allow Bluetooth permissions", false); return
        }
        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            status("Turn on Bluetooth", false); return
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) { status("BLE scanner unavailable", false); return }
        found.clear(); scanning = true
        status("Scanning for THERMOSTEP…", false)
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(listOf(filter), settings, scanCallback) }
        catch (e: Exception) { scanning = false; status("Scan failed: ${e.message}", false); return }
        main.postDelayed({
            if (scanning) {
                stopScan()
                if (found.isEmpty()) status("No THERMOSTEP controller found", false)
                else chooseDevice()
            }
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            found[device.address] = device
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false; status("BLE scan error: $errorCode", false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice() {
        val devices = found.values.toList()
        val labels = devices.map { device ->
            val name = try { device.name } catch (_: SecurityException) { null }
            "${name ?: "BLE device"}  (${device.address})"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select THERMOSTEP controller")
            .setItems(labels) { _, which -> connectDevice(devices[which]) }
            .setNegativeButton("Cancel") { _, _ -> status("Connection cancelled", false) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        status("Connecting to controller…", false)
        gatt?.close()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { disconnectGatt("Controller disconnected") }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { main.post { status("Service discovery failed", false) }; return }
            val service = g.getService(serviceUuid)
            rx = service?.getCharacteristic(rxUuid)
            tx = service?.getCharacteristic(txUuid)
            if (rx == null || tx == null) { main.post { disconnectGatt("Required THERMOSTEP BLE characteristics missing") }; return }
            g.setCharacteristicNotification(tx, true)
            val descriptor = tx?.getDescriptor(cccdUuid)
            if (descriptor == null) { main.post { status("BLE notification descriptor missing", false) }; return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else {
                @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (descriptor.uuid == cccdUuid) main.post {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) status("Connected: ${g.device.name ?: "THERMOSTEP"}", true)
                else status("BLE notification setup failed", false)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val bytes = characteristic.value ?: byteArrayOf()
            deliverNotification(bytes)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliverNotification(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            main.post {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) status("BLE write failed: $statusCode", false)
                writeInProgress = false
                pumpWrites()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeMessage(json: String) {
        if (rx == null || gatt == null) { status("Not connected", false); return }
        pendingWrites.add((json + "\n").toByteArray(Charsets.UTF_8))
        pumpWrites()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        val characteristic = rx ?: return
        val activeGatt = gatt ?: return
        if (writeInProgress || pendingWrites.isEmpty()) return
        val payload = pendingWrites.removeFirst()
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") characteristic.value = payload
                @Suppress("DEPRECATION") if (activeGatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            if (result == BluetoothGatt.GATT_SUCCESS) writeInProgress = true
            else { status("BLE write failed: $result", false); pumpWrites() }
        } catch (e: Exception) { status("BLE write error: ${e.message}", false); pumpWrites() }
    }

    private fun deliverNotification(bytes: ByteArray) {
        val message = bytes.toString(Charsets.UTF_8).trim()
        main.post { evaluate("window.onNativeBleNotification(${org.json.JSONObject.quote(message)});") }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(message: String) {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; rx = null; tx = null; pendingWrites.clear(); writeInProgress = false
        status(message, false)
    }

    private fun status(message: String, connected: Boolean) {
        evaluate("window.onNativeBleStatus(${org.json.JSONObject.quote(message)}, $connected);")
    }

    private fun evaluate(script: String) { if (::web.isInitialized) web.evaluateJavascript(script, null) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else status("Bluetooth permissions denied", false)
        }
    }

    override fun onDestroy() {
        disconnectGatt("App closed")
        web.removeJavascriptInterface("ThermostepBle")
        web.destroy()
        super.onDestroy()
    }
}
