package com.example.thermallabeler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * จัดการการเชื่อมต่อกับเครื่องพิมพ์ผ่าน Bluetooth Classic (SPP profile)
 * เครื่องพิมพ์ thermal ส่วนใหญ่ที่ขายทั่วไปใช้โปรไฟล์นี้
 */
class BluetoothPrinterManager {

    companion object {
        // UUID มาตรฐานของ Serial Port Profile (SPP)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /** คืนรายการอุปกรณ์ Bluetooth ที่ "จับคู่" (paired) ไว้แล้วในเครื่อง */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice) {
        disconnect()
        val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        newSocket.connect()
        socket = newSocket
        outputStream = newSocket.outputStream
    }

    @Throws(IOException::class)
    @Synchronized
    fun write(data: ByteArray) {
        val out = outputStream ?: throw IOException("ยังไม่ได้เชื่อมต่อเครื่องพิมพ์")
        out.write(data)
        out.flush()
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) {
            // ไม่ต้องทำอะไร ปิดอยู่แล้วก็ถือว่าสำเร็จ
        } finally {
            outputStream = null
            socket = null
        }
    }
}
