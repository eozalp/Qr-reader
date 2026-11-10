package com.example.camera

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.model.BarcodeCatalog
import com.example.model.BarcodeScanPreset
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class QrCodeAnalyzer(
    private val onBarcodesDetected: (List<RawDetectedCode>) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    private var currentScanner: BarcodeScanner = createScanner(listOf(Barcode.FORMAT_ALL_FORMATS))

    @Volatile
    private var activeFormats: List<Int> = listOf(Barcode.FORMAT_ALL_FORMATS)

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    var isBatterySaverEnabled: Boolean = false

    @Volatile
    var viewWidth: Float = 1080f

    @Volatile
    var viewHeight: Float = 1920f

    @Volatile
    var isFrontCamera: Boolean = false

    @Volatile
    var enableInvertedDetection: Boolean = true

    private val isProcessingInverted = AtomicBoolean(false)
    private var frameCounter = 0
    private var lastAnalyzedTimeMs = 0L
    private var cachedNv21: ByteArray? = null

    fun updateBarcodeFormats(
        preset: BarcodeScanPreset,
        customFormats: List<Int>,
        enableInverted: Boolean
    ) {
        val effective = BarcodeCatalog.resolveEffectiveFormats(preset, customFormats)
        enableInvertedDetection = enableInverted
        if (effective != activeFormats) {
            activeFormats = effective
            val oldScanner = currentScanner
            currentScanner = createScanner(effective)
            runCatching { oldScanner.close() }
        }
    }

    private fun createScanner(formats: List<Int>): BarcodeScanner {
        val optionsBuilder = BarcodeScannerOptions.Builder()
        if (formats.contains(Barcode.FORMAT_ALL_FORMATS) || formats.isEmpty()) {
            optionsBuilder.setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        } else {
            val first = formats.first()
            val remaining = formats.drop(1).toIntArray()
            optionsBuilder.setBarcodeFormats(first, *remaining)
        }
        return BarcodeScanning.getClient(optionsBuilder.build())
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || isPaused) {
            imageProxy.close()
            return
        }

        // Frame rate throttling:
        // Battery saver: ~5.5 FPS (180ms min interval)
        // Normal mode: ~20 FPS (50ms min interval) to avoid maxing out CPU/GPU
        val currentTime = System.currentTimeMillis()
        val minIntervalMs = if (isBatterySaverEnabled) 180L else 50L
        if (currentTime - lastAnalyzedTimeMs < minIntervalMs) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimeMs = currentTime

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Upright dimensions after rotation
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val uprightHeight = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        frameCounter++
        val invertedFrequency = if (isBatterySaverEnabled) 9 else 3
        val shouldCheckInverted = enableInvertedDetection && (frameCounter % invertedFrequency == 0)

        currentScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val detectedList = mutableListOf<RawDetectedCode>()
                    for (barcode in barcodes) {
                        val rawVal = barcode.rawValue ?: continue
                        val box = barcode.boundingBox ?: continue
                        val format = barcode.format
                        val formatName = BarcodeCatalog.getFormatName(format)
                        val transformed = transformCoordinates(
                            box,
                            uprightWidth,
                            uprightHeight,
                            viewWidth,
                            viewHeight,
                            isFrontCamera
                        )
                        detectedList.add(RawDetectedCode(rawVal, transformed, format, formatName))
                    }
                    onBarcodesDetected(detectedList)
                } else if (shouldCheckInverted && !isProcessingInverted.get()) {
                    // Try inverted luminance pass for 3D engraved, laser-etched, or inverted barcodes
                    tryInvertedScan(imageProxy, uprightWidth, uprightHeight, rotationDegrees)
                } else {
                    onBarcodesDetected(emptyList())
                }
            }
            .addOnFailureListener {
                // Ignore transient frame detection errors
            }
            .addOnCompleteListener {
                if (!isProcessingInverted.get()) {
                    imageProxy.close()
                }
            }
    }

    private fun tryInvertedScan(
        imageProxy: ImageProxy,
        uprightWidth: Float,
        uprightHeight: Float,
        rotationDegrees: Int
    ) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        isProcessingInverted.set(true)
        try {
            val yPlane = mediaImage.planes[0]
            val yBuffer = yPlane.buffer
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            val requiredSize = width * height + (width * height / 2)
            var nv21 = cachedNv21
            if (nv21 == null || nv21.size != requiredSize) {
                nv21 = ByteArray(requiredSize)
                cachedNv21 = nv21
            }
            // Fill chroma with neutral 128
            java.util.Arrays.fill(nv21, width * height, nv21.size, 128.toByte())

            yBuffer.rewind()
            var outIdx = 0
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    val pixel = yBuffer.get(rowStart + col * pixelStride).toInt() and 0xFF
                    nv21[outIdx++] = (255 - pixel).toByte()
                }
            }

            val invertedImage = InputImage.fromByteArray(
                nv21,
                width,
                height,
                rotationDegrees,
                InputImage.IMAGE_FORMAT_NV21
            )

            currentScanner.process(invertedImage)
                .addOnSuccessListener { barcodes ->
                    val detectedList = mutableListOf<RawDetectedCode>()
                    for (barcode in barcodes) {
                        val rawVal = barcode.rawValue ?: continue
                        val box = barcode.boundingBox ?: continue
                        val format = barcode.format
                        val formatName = "${BarcodeCatalog.getFormatName(format)} (3D/INV)"
                        val transformed = transformCoordinates(
                            box,
                            uprightWidth,
                            uprightHeight,
                            viewWidth,
                            viewHeight,
                            isFrontCamera
                        )
                        detectedList.add(RawDetectedCode(rawVal, transformed, format, formatName))
                    }
                    onBarcodesDetected(detectedList)
                }
                .addOnCompleteListener {
                    isProcessingInverted.set(false)
                    imageProxy.close()
                }
        } catch (_: Exception) {
            isProcessingInverted.set(false)
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
