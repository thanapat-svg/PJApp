package com.example.thermallabeler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.thermallabeler.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream

/**
 * แอปนี้ทำงานแบบ Bluetooth ล้วน ๆ ไม่มีเซิร์ฟเวอร์ HTTP และไม่ต้องรู้ IP/พอร์ตของโทรศัพท์เลย
 *
 * เว็บสั่งพิมพ์โดยเปิดลิงก์แบบนี้ (ผู้ใช้ต้องติดตั้งแอปนี้ไว้ก่อน):
 *   intent://print?text=ข้อความ&qr=ข้อมูลqr#Intent;scheme=thermalprint;package=com.example.thermallabeler;end
 *
 * หรือแบบง่าย (ใช้ได้ในเบราว์เซอร์ส่วนใหญ่บน Android):
 *   thermalprint://print?text=ข้อความ&qr=ข้อมูลqr
 *
 * แอปจะเปิดขึ้นมา เชื่อมต่อเครื่องพิมพ์ตัวล่าสุดที่เคยเชื่อมไว้โดยอัตโนมัติ (ถ้ายังไม่ได้เชื่อม)
 * แล้วพิมพ์ให้ทันที
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var service: PrintServerService? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    /** เก็บคำขอพิมพ์ที่มาจากลิงก์เว็บไว้ ถ้ายังเชื่อมเครื่องพิมพ์ไม่เสร็จ จะพิมพ์ให้ทันทีที่เชื่อมสำเร็จ */
    private var pendingPrintUri: Uri? = null

    /** ภาษาคำสั่งที่เครื่องพิมพ์รองรับ: ESC/POS (ใบเสร็จทั่วไป) หรือ TSPL (เครื่องพิมพ์ป้าย/สติกเกอร์) */
    private enum class PrinterLanguage(val label: String) {
        ESCPOS("ESC/POS (เครื่องพิมพ์ใบเสร็จทั่วไป)"),
        TSPL("TSPL (เครื่องพิมพ์ป้าย/สติกเกอร์ เช่น TSC, Xprinter label mode)")
    }

    private var printerLanguage: PrinterLanguage = PrinterLanguage.ESCPOS
    private var paperWidthMm: Double = 58.0
    private var paperHeightMm: Double = 40.0
    private var paperGapMm: Double = 2.0

    companion object {
        private const val PREFS_NAME = "thermal_labeler_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_PRINTER_LANGUAGE = "printer_language"
        private const val KEY_PAPER_WIDTH_MM = "paper_width_mm"
        private const val KEY_PAPER_HEIGHT_MM = "paper_height_mm"
        private const val KEY_PAPER_GAP_MM = "paper_gap_mm"
    }

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.BLUETOOTH)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            refreshDevices()
            tryAutoConnectAndPrint()
        } else {
            Toast.makeText(this, "จำเป็นต้องอนุญาต Bluetooth เพื่อใช้งาน", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ไม่บังคับ ผู้ใช้ปฏิเสธได้ */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PrintServerService.LocalBinder).getService()
            service?.onLog = { msg -> runOnUiThread { updateStatus(msg) } }
            tryAutoConnectAndPrint()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        bindService(Intent(this, PrintServerService::class.java), connection, Context.BIND_AUTO_CREATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnRefreshDevices.setOnClickListener { ensurePermissionsThen { refreshDevices() } }
        binding.btnConnect.setOnClickListener { connectSelectedDevice() }
        binding.btnTestPrint.setOnClickListener { testPrint() }
        binding.btnSaveSettings.setOnClickListener { savePaperSettingsFromUi() }

        setupLanguageSpinner()
        loadSettings()

        handleIncomingIntent(intent)
        ensurePermissionsThen { refreshDevices() }
    }

    private fun setupLanguageSpinner() {
        val languages = PrinterLanguage.values()
        binding.spinnerLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.label }
        )
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updatePaperSizeFieldsVisibility(languages[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** ช่องความสูง/gap มีความหมายเฉพาะ TSPL เท่านั้น เลยซ่อนไว้ตอนเลือก ESC/POS เพื่อไม่ให้สับสน */
    private fun updatePaperSizeFieldsVisibility(language: PrinterLanguage) {
        val visible = language == PrinterLanguage.TSPL
        binding.tvHeightLabel.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        binding.etHeightMm.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvGapLabel.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        binding.etGapMm.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadSettings() {
        val savedLang = prefs.getString(KEY_PRINTER_LANGUAGE, PrinterLanguage.ESCPOS.name)
        printerLanguage = runCatching { PrinterLanguage.valueOf(savedLang ?: "") }.getOrDefault(PrinterLanguage.ESCPOS)
        paperWidthMm = prefs.getFloat(KEY_PAPER_WIDTH_MM, 58f).toDouble()
        paperHeightMm = prefs.getFloat(KEY_PAPER_HEIGHT_MM, 40f).toDouble()
        paperGapMm = prefs.getFloat(KEY_PAPER_GAP_MM, 2f).toDouble()

        binding.spinnerLanguage.setSelection(PrinterLanguage.values().indexOf(printerLanguage))
        binding.etWidthMm.setText(formatMm(paperWidthMm))
        binding.etHeightMm.setText(formatMm(paperHeightMm))
        binding.etGapMm.setText(formatMm(paperGapMm))
        updatePaperSizeFieldsVisibility(printerLanguage)
    }

    private fun savePaperSettingsFromUi() {
        val languages = PrinterLanguage.values()
        printerLanguage = languages[binding.spinnerLanguage.selectedItemPosition]
        paperWidthMm = binding.etWidthMm.text.toString().toDoubleOrNull() ?: paperWidthMm
        paperHeightMm = binding.etHeightMm.text.toString().toDoubleOrNull() ?: paperHeightMm
        paperGapMm = binding.etGapMm.text.toString().toDoubleOrNull() ?: paperGapMm

        prefs.edit()
            .putString(KEY_PRINTER_LANGUAGE, printerLanguage.name)
            .putFloat(KEY_PAPER_WIDTH_MM, paperWidthMm.toFloat())
            .putFloat(KEY_PAPER_HEIGHT_MM, paperHeightMm.toFloat())
            .putFloat(KEY_PAPER_GAP_MM, paperGapMm.toFloat())
            .apply()

        Toast.makeText(this, "บันทึกการตั้งค่าแล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun formatMm(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** อ่านลิงก์พิมพ์ที่เว็บส่งเข้ามา (thermalprint://print?text=...&qr=...) แล้วเก็บไว้รอพิมพ์ */
    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "thermalprint") return
        pendingPrintUri = uri
        updateStatus("ได้รับงานพิมพ์จากเว็บ กำลังเตรียมพิมพ์...")
        ensurePermissionsThen { tryAutoConnectAndPrint() }
    }

    private fun ensurePermissionsThen(action: () -> Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        val manager = service?.printerManager ?: BluetoothPrinterManager()
        pairedDevices = manager.getPairedDevices()
        val names = pairedDevices.map { "${it.name} (${it.address})" }
        binding.spinnerDevices.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        // ถ้าเคยเชื่อมเครื่องนี้ไว้ก่อนหน้า ให้เลือกไว้ให้อัตโนมัติในรายการ
        val lastAddress = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
        if (lastAddress != null) {
            val idx = pairedDevices.indexOfFirst { it.address == lastAddress }
            if (idx >= 0) binding.spinnerDevices.setSelection(idx)
        }
        if (names.isEmpty()) {
            Toast.makeText(
                this,
                "ไม่พบเครื่องพิมพ์ที่จับคู่ไว้ กรุณาจับคู่ผ่านหน้า Bluetooth Settings ของโทรศัพท์ก่อน",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun connectSelectedDevice() {
        val index = binding.spinnerDevices.selectedItemPosition
        if (index < 0 || index >= pairedDevices.size) {
            Toast.makeText(this, "กรุณาเลือกเครื่องพิมพ์ก่อน", Toast.LENGTH_SHORT).show()
            return
        }
        val device = pairedDevices[index]
        updateStatus("กำลังเชื่อมต่อ...")
        service?.connectPrinter(device) { success ->
            if (success) {
                prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, device.address).apply()
                runOnUiThread { executePendingPrintIfReady() }
            }
        }
    }

    /**
     * เรียกตอนแอปเปิดขึ้นมา หรือ service เพิ่งเชื่อมเสร็จ: ถ้ายังไม่ได้เชื่อมเครื่องพิมพ์
     * แต่เคยจำเครื่องล่าสุดไว้ และมีงานพิมพ์ค้างอยู่ ให้เชื่อมต่อให้อัตโนมัติแล้วพิมพ์ต่อเลย
     */
    @SuppressLint("MissingPermission")
    private fun tryAutoConnectAndPrint() {
        val manager = service?.printerManager ?: return
        if (manager.isConnected) {
            executePendingPrintIfReady()
            return
        }
        if (pendingPrintUri == null) return

        val lastAddress = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null) ?: run {
            updateStatus("มีงานพิมพ์ค้างอยู่ กรุณาเลือกและกดเชื่อมต่อเครื่องพิมพ์ก่อน")
            return
        }
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return

        val allDevices = manager.getPairedDevices()
        val device = allDevices.find { it.address == lastAddress } ?: run {
            updateStatus("มีงานพิมพ์ค้างอยู่ กรุณาเลือกและกดเชื่อมต่อเครื่องพิมพ์ก่อน")
            return
        }
        updateStatus("กำลังเชื่อมต่อเครื่องพิมพ์เพื่อพิมพ์งานที่ส่งมา...")
        service?.connectPrinter(device) { success ->
            runOnUiThread {
                if (success) executePendingPrintIfReady()
                else updateStatus("เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ ลองกดเชื่อมต่อเองอีกครั้ง")
            }
        }
    }

    private fun executePendingPrintIfReady() {
        val uri = pendingPrintUri ?: return
        val manager = service?.printerManager ?: return
        if (!manager.isConnected) return
        pendingPrintUri = null
        printFromUri(uri)
    }

    /**
     * อ่านค่า text / qr จาก query string ของลิงก์ แล้วสร้างคำสั่งพิมพ์ส่งออกไปที่เครื่องพิมพ์
     * รองรับ override ค่าตั้งค่าเฉพาะงานนี้ผ่าน query param ด้วย (ไม่บันทึกทับค่าเริ่มต้นในแอป):
     *   lang=escpos|tspl, width=<mm>, height=<mm>, gap=<mm>
     */
    private fun printFromUri(uri: Uri) {
        val text = uri.getQueryParameter("text")
        val qr = uri.getQueryParameter("qr")

        val lang = when (uri.getQueryParameter("lang")?.lowercase()) {
            "tspl" -> PrinterLanguage.TSPL
            "escpos" -> PrinterLanguage.ESCPOS
            else -> printerLanguage
        }
        val width = uri.getQueryParameter("width")?.toDoubleOrNull() ?: paperWidthMm
        val height = uri.getQueryParameter("height")?.toDoubleOrNull() ?: paperHeightMm
        val gap = uri.getQueryParameter("gap")?.toDoubleOrNull() ?: paperGapMm

        printPayload(text, qr, lang, width, height, gap, "พิมพ์งานจากเว็บสำเร็จ")
    }

    private fun testPrint() {
        printPayload(
            text = "ทดสอบพิมพ์",
            qr = "https://example.com",
            lang = printerLanguage,
            widthMm = paperWidthMm,
            heightMm = paperHeightMm,
            gapMm = paperGapMm,
            successMessage = "ส่งงานทดสอบพิมพ์แล้ว"
        )
    }

    private fun printPayload(
        text: String?,
        qr: String?,
        lang: PrinterLanguage,
        widthMm: Double,
        heightMm: Double,
        gapMm: Double,
        successMessage: String
    ) {
        val manager = service?.printerManager
        if (manager == null || !manager.isConnected) {
            updateStatus("ยังไม่ได้เชื่อมต่อเครื่องพิมพ์")
            return
        }
        Thread {
            try {
                val payload = when (lang) {
                    PrinterLanguage.ESCPOS -> buildEscPosPayload(text, qr, widthMm)
                    PrinterLanguage.TSPL -> TsplBuilder.buildLabel(text, qr, widthMm, heightMm, gapMm)
                }
                manager.write(payload)
                runOnUiThread { updateStatus(successMessage) }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("พิมพ์ไม่สำเร็จ: ${e.message}") }
            }
        }.start()
    }

    /** ความกว้างกระดาษมีผลต่อขนาดตัวอักษรเริ่มต้นของ ESC/POS: กระดาษกว้าง (>= 76mm) ใช้ตัวใหญ่ขึ้นอัตโนมัติ */
    private fun buildEscPosPayload(text: String?, qr: String?, widthMm: Double): ByteArray {
        val out = ByteArrayOutputStream()
        val sizeMultiplier = if (widthMm >= 76.0) 1 else 0
        out.write(EscPosBuilder.init())
        out.write(EscPosBuilder.align(1))
        out.write(EscPosBuilder.textSize(sizeMultiplier, sizeMultiplier))
        if (!text.isNullOrBlank()) {
            out.write(EscPosBuilder.text("$text\n"))
        }
        out.write(EscPosBuilder.textSize(0, 0))
        if (!qr.isNullOrBlank()) {
            out.write(EscPosBuilder.qrCode(qr))
        }
        out.write(EscPosBuilder.feed(3))
        out.write(EscPosBuilder.cut())
        return out.toByteArray()
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = "สถานะ: $text"
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}
