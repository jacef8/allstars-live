package com.libertyclerk.allstarslive.ingest

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Tiny QR-bitmap generator (zxing). Used to show a "scan to set up your phone as the
 *  camera" code in the camera-setup sheet — encodes a Larix Grove deep link. */
object QrUtil {
    fun encode(text: String, size: Int = 640): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** Build a Larix Broadcaster "Grove" deep link that auto-configures an RTMP push to [rtmpUrl].
     *  Scanned inside Larix → one connection is added pointing at the tablet. */
    fun larixGrove(rtmpUrl: String, name: String = "All-Stars Live"): String {
        val enc = java.net.URLEncoder.encode(rtmpUrl, "UTF-8").replace("+", "%20")
        val nm = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "larix://set/v1?conn[][url]=$enc&conn[][name]=$nm"
    }
}
