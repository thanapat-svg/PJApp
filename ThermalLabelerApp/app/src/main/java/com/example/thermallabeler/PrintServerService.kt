package com.example.thermallabeler

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service ที่ถือการเชื่อมต่อ Bluetooth กับเครื่องพิมพ์ไว้ตลอด
 * แม้ผู้ใช้จะสลับหน้าจอไปแอปอื่นชั่วคราว ก็ยังพิมพ์ได้ทันทีที่มีลิงก์พิมพ์เข้ามาจากเว็บ
 * (ไม่มีการเปิดพอร์ต/เซิร์ฟเวอร์ใด ๆ ทั้งสิ้น ทุกอย่างทำงานผ่าน Bluetooth ในเครื่องล้วน ๆ)
 */
class PrintServerService : Service() {

    companion object {
        const val CHANNEL_ID = "print_server_channel"
        const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    val printerManager = BluetoothPrinterManager()

    var onLog: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PrintServerService = this@PrintServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("พร้อมพิมพ์ผ่าน Bluetooth"))
    }

    @SuppressLint("MissingPermission")
    fun connectPrinter(device: BluetoothDevice, onResult: ((Boolean) -> Unit)? = null) {
        Thread {
            try {
                printerManager.connect(device)
                onLog?.invoke("เชื่อมต่อเครื่องพิมพ์สำเร็จ: ${device.name}")
                onResult?.invoke(true)
            } catch (e: Exception) {
                onLog?.invoke("เชื่อมต่อไม่สำเร็จ: ${e.message}")
                onResult?.invoke(false)
            }
        }.start()
    }

    override fun onDestroy() {
        printerManager.disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Thermal Labeler",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Thermal Labeler")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
    }
}
