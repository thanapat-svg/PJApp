package com.example.thermallabeler

/**
 * ตัวช่วยสร้างคำสั่ง TSPL (TSC Printer Language) สำหรับเครื่องพิมพ์ป้ายฉลาก/สติกเกอร์
 * (เช่น TSC, Xprinter รุ่นโหมด label, Zebra ที่รองรับ TSPL emulation)
 *
 * ต่างจาก ESC/POS ตรงที่ TSPL ต้องบอกขนาดกระดาษ (SIZE) และระยะ gap ระหว่างป้ายทุกครั้งก่อนพิมพ์
 * พิกัด x,y ของคำสั่ง TEXT/QRCODE เป็นหน่วย "dot" ไม่ใช่ mm จึงต้องแปลงจาก mm ด้วยค่า DPI ของเครื่องพิมพ์
 * (ค่า default ที่ใช้กันทั่วไปคือ 203 dpi ~ เครื่องพิมพ์ป้ายราคาประหยัดส่วนใหญ่)
 */
object TsplBuilder {

    private fun mmToDots(mm: Double, dpi: Int): Int = (mm * dpi / 25.4).toInt()

    private fun escape(text: String): String = text.replace("\"", "'")

    /**
     * @param text ข้อความบนป้าย (รองรับหลายบรรทัดด้วย \n)
     * @param qr ข้อมูลสำหรับ QR code (ใส่หรือไม่ใส่ก็ได้)
     * @param widthMm ความกว้างกระดาษ/ป้าย เป็นมิลลิเมตร
     * @param heightMm ความสูงกระดาษ/ป้าย เป็นมิลลิเมตร
     * @param gapMm ระยะห่างระหว่างป้าย (ถ้าเป็นม้วนต่อเนื่องไม่มีรอยตัด ใส่ 0)
     * @param dpi ความละเอียดหัวพิมพ์ (203 เป็นค่าทั่วไปที่พบบ่อยสุด)
     */
    fun buildLabel(
        text: String?,
        qr: String?,
        widthMm: Double,
        heightMm: Double,
        gapMm: Double,
        dpi: Int = 203
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("SIZE $widthMm mm,$heightMm mm\r\n")
        sb.append("GAP $gapMm mm,0 mm\r\n")
        sb.append("DIRECTION 1\r\n")
        sb.append("CLS\r\n")

        val marginX = mmToDots(2.0, dpi)
        var y = mmToDots(2.0, dpi)
        val lineHeight = mmToDots(6.0, dpi)

        if (!text.isNullOrBlank()) {
            text.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    sb.append("TEXT $marginX,$y,\"3\",0,1,1,\"${escape(line)}\"\r\n")
                }
                y += lineHeight
            }
        }

        if (!qr.isNullOrBlank()) {
            val cellSize = 5
            sb.append("QRCODE $marginX,$y,H,$cellSize,A,0,\"${escape(qr)}\"\r\n")
        }

        sb.append("PRINT 1,1\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
