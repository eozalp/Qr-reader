package com.example.camera

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.debug.AppDebugLogger
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

    private class ManagedScanner(val scanner: BarcodeScanner) {
        private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        private val closeRequested = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        fun tryAcquire(): Boolean {
            if (closeRequested.get()) return false
            inFlight.incrementAndGet()
            if (closeRequested.get()) {
                release()
                return false
            }
            return true
        }

        fun release() {
            val remaining = inFlight.decrementAndGet()
            if (remaining <= 0 && closeRequested.get()) {
                closeNow()
            }
        }

        fun requestClose() {
            closeRequested.set(true)
            if (inFlight.get() <= 0) {
                closeNow()
            }
        }

        private fun closeNow() {
            if (closed.compareAndSet(false, true)) {
                runCatching { scanner.close() }
                    .onFailure { AppDebugLogger.w("CAM-QR", "Scanner kapatma uyarısı", it) }
            }
        }
    }

    @Volatile
    private var currentScanner: ManagedScanner = ManagedScanner(createScanner(listOf(Barcode.FORMAT_ALL_FORMATS)))

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
    private val isClosed = AtomicBoolean(false)
    private var frameCounter = 0
    private var lastAnalyzedTimeMs = 0L
    private var cachedNv21: ByteArray? = null

    fun close() {
        isClosed.set(true)
        currentScanner.requestClose()
    }

    fun updateBarcodeFormats(
        preset: BarcodeScanPreset,
        customFormats: List<Int>,
        enableInverted: Boolean
    ) {
        val effective = BarcodeCatalog.resolveEffectiveFormats(preset, customFormats)
        enableInvertedDetection = enableInverted
        if (effective != activeFormats && !isClosed.get()) {
            activeFormats = effective
            val oldScanner = currentScanner
            currentScanner = ManagedScanner(createScanner(effective))
            oldScanner.requestClose()
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
        if (mediaImage == null || isPaused || isClosed.get()) {
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

        val managed = currentScanner
        if (!managed.tryAcquire()) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Upright dimensions after rotation
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val uprightHeight = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        frameCounter++
        val invertedFrequency = if (isBatterySaverEnabled) 9 else 3
        val shouldCheckInverted = enableInvertedDetection && (frameCounter % invertedFrequency == 0)

        var handedOffToInverted = false
        managed.scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (isClosed.get()) return@addOnSuccessListener
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
                    // Hand off this specific ImageProxy exclusively to inverted pass
                    handedOffToInverted = tryInvertedScan(
                        imageProxy = imageProxy,
                        uprightWidth = uprightWidth,
                        uprightHeight = uprightHeight,
                        rotationDegrees = rotationDegrees
                    )
                    if (!handedOffToInverted) {
                        onBarcodesDetected(emptyList())
                    }
                } else {
                    onBarcodesDetected(emptyList())
                }
            }
            .addOnFailureListener { err ->
                if (!isClosed.get()) {
                    AppDebugLogger.w("CAM-QR", "ML Kit barkod tarama hatası", err)
                }
            }
            .addOnCompleteListener {
                managed.release()
                if (!handedOffToInverted) {
                    imageProxy.close()
                }
            }
    }

    /**
     * Returns true if ownership of [imageProxy] was accepted by the async inverted pass
     * (which is then solely responsible for closing [imageProxy]), or false if caller must close it.
     */
    private fun tryInvertedScan(
        imageProxy: ImageProxy,
        uprightWidth: Float,
        uprightHeight: Float,
        rotationDegrees: Int
    ): Boolean {
        if (isClosed.get() || !isProcessingInverted.compareAndSet(false, true)) {
            return false
        }
        val mediaImage = imageProxy.image ?: run {
            isProcessingInverted.set(false)
            return false
        }
        val managed = currentScanner
        if (!managed.tryAcquire()) {
            isProcessingInverted.set(false)
            return false
        }

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

            managed.scanner.process(invertedImage)
                .addOnSuccessListener { barcodes ->
                    if (isClosed.get()) return@addOnSuccessListener
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
                .addOnFailureListener { err ->
                    if (!isClosed.get()) {
                        AppDebugLogger.w("CAM-QR", "Ters renk ML Kit hatası", err)
                    }
                }
                .addOnCompleteListener {
                    managed.release()
                    isProcessingInverted.set(false)
                    imageProxy.close()
                }
            return true
        } catch (e: Exception) {
            AppDebugLogger.w("CAM-QR", "Ters renk kare işleme hatası", e)
            managed.release()
            isProcessingInverted.set(false)
            imageProxy.close()
            return true
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
