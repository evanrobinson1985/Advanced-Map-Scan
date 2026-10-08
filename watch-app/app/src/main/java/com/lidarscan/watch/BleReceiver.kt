package com.lidarscan.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Receives a watch package from the web app over Bluetooth Low Energy.
 *
 * The watch advertises a small GATT service; Chrome on the phone (Web
 * Bluetooth) connects to it and writes the package in numbered pieces to the
 * RX characteristic (see [Reassembly]): BEGIN size+CRC, DATA offset+bytes,
 * END. Pieces up to 512 bytes arrive as long ("prepared") writes, which are
 * put together here. Progress and the result go back as notifications on the
 * STATUS characteristic ("p received total", "ok N", "err message").
 */
@SuppressLint("MissingPermission")   // the screen asks for the Bluetooth permissions before start()
class BleReceiver(
    private val ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Int, Int) -> Unit,
    private val onPackage: (ByteArray) -> String,   // returns a short summary, or throws
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("7b1e0001-5a4c-4b8e-9d3a-2f6c1a9e4d10")
        val RX: UUID = UUID.fromString("7b1e0002-5a4c-4b8e-9d3a-2f6c1a9e4d10")
        val STATUS: UUID = UUID.fromString("7b1e0003-5a4c-4b8e-9d3a-2f6c1a9e4d10")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var status: BluetoothGattCharacteristic? = null
    private val subscribers = HashSet<BluetoothDevice>()
    private val prepared = HashMap<BluetoothDevice, ByteArrayOutputStream>()
    private val reasm = Reassembly()
    private var lastNotified = 0
    private var advertising = false
    private var withName = true

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post { onStatus("Ready. On your phone, open the LiDAR app and press ⌚ Send to watch.") }
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            // a long watch name can overflow the scan response: try once without it
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withName) { withName = false; main.post { startAdvertising() }; return }
            main.post { onStatus("This watch could not start Bluetooth advertising (error $errorCode). Turn Bluetooth off and on, then try again.") }
        }
    }

    fun start(): Boolean {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) { onStatus("Turn on Bluetooth on the watch, then try again."); return false }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) { onStatus("This watch can't receive over Bluetooth LE."); return false }

        val srv = mgr.openGattServer(ctx, callback) ?: run { onStatus("Bluetooth is busy; try again in a moment."); return false }
        server = srv
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val st = BluetoothGattCharacteristic(
            STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        st.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(st)
        status = st
        srv.addService(service)   // advertising starts once the service is in place (onServiceAdded)
        onStatus("Starting Bluetooth...")
        return true
    }

    private fun startAdvertising() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adv = mgr.adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        advertising = true
        adv.startAdvertising(settings, data, scan, advCallback)
    }

    fun stop() {
        try {
            if (advertising) {
                val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                mgr?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback)
            }
        } catch (e: Exception) { /* Bluetooth already off */ }
        advertising = false
        try { server?.close() } catch (e: Exception) { }
        server = null
        subscribers.clear(); prepared.clear()
    }

    private fun notifyStatus(text: String) {
        val srv = server ?: return
        val ch = status ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        setValue(ch, bytes)
        for (d in subscribers.toList()) {
            try {
                if (Build.VERSION.SDK_INT >= 33) srv.notifyCharacteristicChanged(d, ch, false, bytes)
                else notifyOld(srv, d, ch)
            } catch (e: Exception) { /* the phone went away */ }
        }
    }

    @Suppress("DEPRECATION")
    private fun setValue(ch: BluetoothGattCharacteristic, v: ByteArray) { ch.value = v }
    @Suppress("DEPRECATION")
    private fun notifyOld(srv: BluetoothGattServer, d: BluetoothDevice, ch: BluetoothGattCharacteristic) { srv.notifyCharacteristicChanged(d, ch, false) }

    /** One complete message from the phone. */
    private fun handle(msg: ByteArray) {
        if (msg.isEmpty()) return
        try {
            when (msg[0]) {
                Reassembly.OP_BEGIN -> {
                    val bb = ByteBuffer.wrap(msg, 1, 8)
                    val total = bb.int
                    val crc = bb.int.toLong() and 0xFFFFFFFFL
                    reasm.begin(total, crc)
                    lastNotified = 0
                    main.post { onProgress(0, total); onStatus("Receiving...") }
                    notifyStatus("p 0 $total")
                }
                Reassembly.OP_DATA -> {
                    val off = ByteBuffer.wrap(msg, 1, 4).int
                    reasm.data(off, msg, 5, msg.size - 5)
                    val r = reasm.received; val t = reasm.total
                    if (r - lastNotified >= 8192 || r == t) {
                        lastNotified = r
                        main.post { onProgress(r, t) }
                        notifyStatus("p $r $t")
                    }
                }
                Reassembly.OP_END -> {
                    val bytes = reasm.end()
                    val summary = onPackage(bytes)
                    notifyStatus("ok $summary")
                    main.post { onStatus("Received: $summary") }
                }
            }
        } catch (e: Exception) {
            val m = e.message ?: e.toString()
            notifyStatus("err $m")
            main.post { onStatus("Problem: $m") }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(st: Int, service: BluetoothGattService) {
            if (st == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else main.post { onStatus("Bluetooth service could not be set up ($st).") }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) main.post { onStatus("Phone connected...") }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { subscribers.remove(device); prepared.remove(device) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val v = value ?: ByteArray(0)
            if (characteristic.uuid != RX) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            if (preparedWrite) {
                // a long write: pieces are queued until the phone says "execute"
                val buf = prepared.getOrPut(device) { ByteArrayOutputStream() }
                if (offset != buf.size()) {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, v)
                    return
                }
                buf.write(v)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            handle(v)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = prepared.remove(device)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) handle(buf.toByteArray())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                if (on) subscribers.add(device) else subscribers.remove(device)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val v = if (subscribers.contains(device)) byteArrayOf(1, 0) else byteArrayOf(0, 0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: ByteArray(0)
            val part = if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }
    }
}
