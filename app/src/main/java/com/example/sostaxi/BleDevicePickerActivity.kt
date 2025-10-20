package com.example.sostaxi

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * Простой сканер BLE-устройств для выбора кнопки в настройках.
 * Отображает список найденных устройств и возвращает выбранный MAC-адрес.
 */
class BleDevicePickerActivity : AppCompatActivity() {
    private val tag = "BleDevicePicker"
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar

    private val devices = LinkedHashMap<String, BluetoothDevice>() // address -> device
    private lateinit var adapter: ArrayAdapter<String>

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Сделаем фон непрозрачным
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(android.graphics.Color.WHITE)

        progress = ProgressBar(this)
        progress.isIndeterminate = true
        progress.visibility = View.GONE
        layout.addView(progress)

        listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = adapter
        layout.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val btnLoad = Button(this)
        btnLoad.text = "Показать подключённые устройства"
        btnLoad.isAllCaps = false
        btnLoad.setOnClickListener { loadConnectedDevices() }
        layout.addView(btnLoad)

        setContentView(layout)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            val parts = item.split(" | ")
            val address = parts.lastOrNull() ?: return@setOnItemClickListener
            val data = Intent().putExtra("ble_address", address)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun ensurePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needConnect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            if (needConnect) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2001)
            !needConnect
        } else true
    }

    private fun loadConnectedDevices() {
        if (!ensurePermissions()) return
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connected = try {
            bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
        } catch (e: SecurityException) {
            Log.e(tag, "Permission error: ${e.message}")
            emptyList<BluetoothDevice>()
        }

        devices.clear()
        adapter.clear()
        progress.visibility = View.VISIBLE

        for (d in connected) {
            if (d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                addDevice(d)
            }
        }
        progress.visibility = View.GONE
        if (devices.isEmpty()) {
            adapter.add("Нет подключённых BLE-устройств")
        }
    }

    private fun addDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        if (devices.containsKey(address)) return
        devices[address] = device
        val name = device.name ?: "(без имени)"
        runOnUiThread { adapter.add("$name | $address") }
    }
}


