package com.example.thermallabeler

import java.io.ByteArrayOutputStream

/**
 * ตัวช่วยสร้างคำสั่ง ESC/POS พื้นฐานสำหรับเครื่องพิมพ์ความร้อน (thermal printer)
 * รองรับ: ข้อความ, จัดตำแหน่ง, ตัวหนา, ขนาดตัวอักษร, QR code, บาร์โค้ด, ป้อนกระดาษ, ตัดกระดาษ
 *
 * หมายเหตุ: เครื่องพิมพ์แต่ละยี่ห้ออาจรองรับชุดคำสั่งไม่เหมือนกัน 100%
 * หากพิมพ์ QR/บาร์โค้ดไม่ออก ให้ลองปรับค่าตามคู่มือเครื่องพิมพ์รุ่นนั้น ๆ
 */
object EscPosBuilder {

    private const val ESC = 0x1B
    private const val GS = 0x1D

    fun init(): ByteArray = byteArrayOf(ESC.toByte(), '@'.code.toByte())

    /** mode: 0 = ซ้าย, 1 = กลาง, 2 = ขวา */
    fun align(mode: Int): ByteArray {
        return byteArrayOf(ESC.toByte(), 'a'.code.toByte(), mode.toByte())
    }

    fun bold(on: Boolean): ByteArray {
        return byteArrayOf(ESC.toByte(), 'E'.code.toByte(), (if (on) 1 else 0).toByte())
    }

    /** ขนาดตัวอักษร multiplier ตั้งแต่ 0 (x1) ถึง 7 (x8) */
    fun textSize(widthMultiplier: Int, heightMultiplier: Int): ByteArray {
        val w = widthMultiplier.coerceIn(0, 7)
        val h = heightMultiplier.coerceIn(0, 7)
        val n = (w shl 4) or h
        return byteArrayOf(GS.toByte(), '!'.code.toByte(), n.toByte())
    }

    fun text(content: String): ByteArray {
        // เครื่องพิมพ์ส่วนใหญ่รองรับ UTF-8 สำหรับภาษาอังกฤษ/ตัวเลขได้ดี
        // หากพิมพ์ภาษาไทยไม่ออก ดูหมายเหตุเรื่อง Thai font ในไฟล์ README
        return content.toByteArray(Charsets.UTF_8)
    }

    fun feed(lines: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(lines.coerceAtLeast(0)) { out.write('\n'.code) }
        return out.toByteArray()
    }

    fun cut(): ByteArray {
        // Full cut
        return byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00)
    }

    /**
     * สร้างคำสั่งพิมพ์ QR Code ด้วยชุดคำสั่งมาตรฐาน GS ( k (ESC/POS ทั่วไป, ใช้ได้กับเครื่องพิมพ์ส่วนใหญ่)
     */
    fun qrCode(data: String, moduleSize: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = data.toByteArray(Charsets.UTF_8)

        // 1) เลือกโมเดล QR (Model 2)
        out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte(), 4, 0, 49, 65, 50, 0))
        // 2) ตั้งขนาดโมดูล
        out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 67, moduleSize.toByte()))
        // 3) ตั้งระดับ error correction (ระดับ M)
        out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 69, 49))
        // 4) เก็บข้อมูลลง buffer ของเครื่องพิมพ์
        val pL = ((bytes.size + 3) % 256)
        val pH = ((bytes.size + 3) / 256)
        out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte(), pL.toByte(), pH.toByte(), 49, 80, 48))
        out.write(bytes)
        // 5) สั่งพิมพ์ QR ที่เก็บไว้
        out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 81, 48))

        return out.toByteArray()
    }

    /**
     * บาร์โค้ดแบบ CODE128 (ใช้คำสั่ง GS k)
     */
    fun barcodeCode128(data: String, height: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = data.toByteArray(Charsets.US_ASCII)

        out.write(byteArrayOf(GS.toByte(), 'h'.code.toByte(), height.toByte())) // ความสูงบาร์โค้ด
        out.write(byteArrayOf(GS.toByte(), 'w'.code.toByte(), 2))               // ความกว้างเส้น
        out.write(byteArrayOf(GS.toByte(), 'H'.code.toByte(), 2))               // พิมพ์ตัวเลขใต้บาร์โค้ดด้วย

        // CODE128 ต้องขึ้นต้นด้วย {B ตามด้วยข้อมูล
        val codeBytes = byteArrayOf('{'.code.toByte(), 'B'.code.toByte()) + bytes
        out.write(byteArrayOf(GS.toByte(), 'k'.code.toByte(), 73, codeBytes.size.toByte()))
        out.write(codeBytes)

        return out.toByteArray()
    }
}
