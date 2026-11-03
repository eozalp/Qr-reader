package com.example.model

import android.graphics.RectF

/**
 * Status of the QR parse and formula calculation.
 */
sealed class CalculationStatus {
    data class Success(val resultNumber: Double, val formattedText: String) : CalculationStatus()
    data class ParseError(val message: String) : CalculationStatus()
    data class EvaluationError(val message: String) : CalculationStatus()
}

/**
 * Parsed payload of a single QR code.
 */
data class ParsedQrResult(
    val rawText: String,
    val extractedVariables: Map<String, Double> = emptyMap(),
    val extractedStrings: Map<String, String> = emptyMap(),
    val variablePillDisplay: Map<String, String> = emptyMap(),
    val status: CalculationStatus,
    val formulaExpression: String = ""
)

/**
 * Represents an active, tracked QR code on the live camera preview.
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
    var attachment: QrAttachment? = null
)
