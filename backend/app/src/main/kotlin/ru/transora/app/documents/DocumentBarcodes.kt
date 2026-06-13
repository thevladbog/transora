package ru.transora.app.documents

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object DocumentBarcodes {
    const val THERMAL_WIDTH_PT = 227f
    const val THERMAL_MAX_HEIGHT_PT = 425f
    const val QR_SIZE_PT = 99f

    fun renderQrCode(payload: String, sizePx: Int = 140): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val image = MatrixToImageWriter.toBufferedImage(matrix)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", output)
        return output.toByteArray()
    }

    fun renderCode128(value: String, widthPx: Int = 200, heightPx: Int = 40): ByteArray {
        val matrix = Code128Writer().encode(value, BarcodeFormat.CODE_128, widthPx, heightPx)
        val image = MatrixToImageWriter.toBufferedImage(matrix)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", output)
        return output.toByteArray()
    }
}
