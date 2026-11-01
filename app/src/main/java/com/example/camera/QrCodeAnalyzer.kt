package com.example.camera

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlin.math.max

class QrCodeAnalyzer(
    private val onBarcodesDetected: (List<RawDetectedCode>) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var viewWidth: Float = 1080f

    @Volatile
    var viewHeight: Float = 1920f

    @Volatile
    var isFrontCamera: Boolean = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isPaused) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Upright dimensions after rotation
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val uprightHeight = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val detectedList = mutableListOf<RawDetectedCode>()
                for (barcode in barcodes) {
                    val rawVal = barcode.rawValue ?: continue
                    val box = barcode.boundingBox ?: continue
                    val transformed = transformCoordinates(
                        box,
                        uprightWidth,
                        uprightHeight,
                        viewWidth,
                        viewHeight,
                        isFrontCamera
                    )
                    detectedList.add(RawDetectedCode(rawVal, transformed))
                }
                onBarcodesDetected(detectedList)
            }
            .addOnFailureListener {
                // Ignore transient frame detection errors
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun transformCoordinates(
        box: Rect,
        srcWidth: Float,
        srcHeight: Float,
        dstWidth: Float,
        dstHeight: Float,
        mirrorHorizontal: Boolean
    ): RectF {
        if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) {
            return RectF(box)
        }

        // CameraX PreviewView FILL_CENTER scale factor
        val scale = max(dstWidth / srcWidth, dstHeight / srcHeight)
        val offsetX = (dstWidth - srcWidth * scale) / 2f
        val offsetY = (dstHeight - srcHeight * scale) / 2f

        var left = box.left * scale + offsetX
        var right = box.right * scale + offsetX
        val top = box.top * scale + offsetY
        val bottom = box.bottom * scale + offsetY

        if (mirrorHorizontal) {
            val mirroredLeft = dstWidth - right
            val mirroredRight = dstWidth - left
            left = mirroredLeft
            right = mirroredRight
        }

        return RectF(left, top, right, bottom)
    }
}
