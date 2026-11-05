package com.example.model

import android.graphics.RectF

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Status of the QR parse and formula calculation.
 */
sealed class CalculationStatus {
    data class Success(val resultNumber: Double, val formattedText: String) : CalculationStatus()
    data class ParseError(val message: String) : CalculationStatus()
    data class EvaluationError(val message: String) : CalculationStatus()
}

/**
 * Parsed payload of a single QR or barcode.
 */
data class ParsedQrResult(
    val rawText: String,
    val extractedVariables: Map<String, Double> = emptyMap(),
    val extractedStrings: Map<String, String> = emptyMap(),
    val variablePillDisplay: Map<String, String> = emptyMap(),
    val status: CalculationStatus,
    val formulaExpression: String = "",
    val barcodeFormat: Int = Barcode.FORMAT_QR_CODE,
    val formatName: String = "QR CODE"
)

/**
 * Represents an active, tracked QR code or barcode on the live camera preview.
 */
data class TrackedQr(
    val id: String,
    val rawContent: String,
    var currentBox: RectF, // Smoothed bounding box in screen coordinates
    var targetBox: RectF,  // Raw bounding box transformed to screen coordinates
    var result: ParsedQrResult,
    var lastSeenMs: Long,
    var firstSeenMs: Long,
    var alpha: Float = 1.0f,
    var attachment: QrAttachment? = null,
    var barcodeFormat: Int = Barcode.FORMAT_QR_CODE,
    var formatName: String = "QR CODE"
)
