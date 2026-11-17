package com.example.camera

import android.graphics.RectF
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.CalculationStatus
import com.example.model.ParsedQrResult
import com.example.model.QrAttachment
import com.example.model.QrProfile
import com.example.model.TrackedQr
import java.util.UUID
import kotlin.math.hypot

data class RawDetectedCode(
    val rawValue: String,
    val uprightBoundingBox: RectF,
    val format: Int = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
    val formatName: String = "QR CODE"
)

class QrTracker {

    private val activeTracked = mutableListOf<TrackedQr>()
    private val mathEvaluator = MathExpressionEvaluator()

    /**
     * Updates tracked QR codes with newly detected codes in screen coordinates.
     */
    fun update(
        detectedScreenBoxes: List<RawDetectedCode>,
        profile: QrProfile,
        currentTimeMs: Long = System.currentTimeMillis(),
        attachmentFinder: ((String) -> QrAttachment?)? = null
    ): List<TrackedQr> {
        val smoothingFactor = profile.tracking.smoothingFactor.coerceIn(0.1f, 1.0f)
        val timeoutMs = profile.tracking.disappearanceTimeoutMs

        val matchedIds = mutableSetOf<String>()
        val unmatchedDetected = detectedScreenBoxes.toMutableList()

        // 1. Try matching existing tracked items with closest detected code of same content
        for (tracked in activeTracked) {
            val candidateIndex = unmatchedDetected.indexOfFirst { candidate ->
                candidate.rawValue == tracked.rawContent &&
                        distance(tracked.currentBox.centerX(), tracked.currentBox.centerY(),
                            candidate.uprightBoundingBox.centerX(), candidate.uprightBoundingBox.centerY()) < 350f
            }

            if (candidateIndex != -1) {
                val match = unmatchedDetected.removeAt(candidateIndex)
                tracked.targetBox = RectF(match.uprightBoundingBox)
                // EMA smoothing
                smoothRect(tracked.currentBox, tracked.targetBox, smoothingFactor)
                tracked.lastSeenMs = currentTimeMs
                tracked.alpha = 1.0f
                tracked.barcodeFormat = match.format
                tracked.formatName = match.formatName
                if (tracked.attachment == null && attachmentFinder != null) {
                    tracked.attachment = attachmentFinder(tracked.rawContent)
                }
                matchedIds.add(tracked.id)
            }
        }

        // 2. For remaining unmatched detected codes, create new tracked entries (up to maxTrackedCodes)
        for (unmatched in unmatchedDetected) {
            if (activeTracked.size >= profile.tracking.maxTrackedCodes) break

            val initialResult = processQr(unmatched.rawValue, profile, unmatched.format, unmatched.formatName)
            val attachment = attachmentFinder?.invoke(unmatched.rawValue)
            val newTracked = TrackedQr(
                id = UUID.randomUUID().toString(),
                rawContent = unmatched.rawValue,
                currentBox = RectF(unmatched.uprightBoundingBox),
                targetBox = RectF(unmatched.uprightBoundingBox),
                result = initialResult,
                lastSeenMs = currentTimeMs,
                firstSeenMs = currentTimeMs,
                alpha = 1.0f,
                attachment = attachment,
                barcodeFormat = unmatched.format,
                formatName = unmatched.formatName
            )
            activeTracked.add(newTracked)
            matchedIds.add(newTracked.id)
        }

        // 3. For active items not seen in this frame, fade out or remove
        val iterator = activeTracked.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.id !in matchedIds) {
                val elapsed = currentTimeMs - item.lastSeenMs
                if (elapsed > timeoutMs) {
                    iterator.remove()
                } else {
                    // Fade out smoothly
                    val remainingFraction = 1.0f - (elapsed.toFloat() / timeoutMs)
                    item.alpha = remainingFraction.coerceIn(0f, 1f)
                }
            } else {
                // If profile changed, recalculate result
                item.result = processQr(item.rawContent, profile, item.barcodeFormat, item.formatName)
            }
        }

        return activeTracked.map { it.copy(currentBox = RectF(it.currentBox)) }
    }

    private fun smoothRect(current: RectF, target: RectF, alpha: Float) {
        current.left = current.left + (target.left - current.left) * alpha
        current.top = current.top + (target.top - current.top) * alpha
        current.right = current.right + (target.right - current.right) * alpha
        current.bottom = current.bottom + (target.bottom - current.bottom) * alpha
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
    }

    fun processQr(
        rawText: String,
        profile: QrProfile,
        format: Int = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
        formatName: String = "QR CODE"
    ): ParsedQrResult {
        // Step 1: Parse variables & evaluate computed variables
        return when (val parseOutcome = QrParser.parse(rawText, profile.parsingRules)) {
            is QrParseOutcome.Error -> {
                ParsedQrResult(
                    rawText = rawText,
                    status = CalculationStatus.ParseError(parseOutcome.message),
                    formulaExpression = "",
                    barcodeFormat = format,
                    formatName = formatName
                )
            }
            is QrParseOutcome.Success -> {
                // Pre-compute formatted display string for each variable pill with its individual prefix, postfix, and decimal settings
                val pillDisplay = mutableMapOf<String, String>()
                for (v in profile.parsingRules.variables) {
                    val strVal = parseOutcome.stringVariables[v.name]
                    val numVal = parseOutcome.numericVariables[v.name]
                    if ((v.dataType == com.example.model.FieldDataType.DATE || v.dataType == com.example.model.FieldDataType.STRING || v.dataType == com.example.model.FieldDataType.BOOLEAN) && strVal != null) {
                        val displayStr = "${v.prefix}$strVal${v.postfix}"
                        pillDisplay[v.name] = displayStr
                        if (v.alias.isNotBlank()) {
                            pillDisplay[v.alias] = displayStr
                        }
                    } else if (numVal != null) {
                        val displayStr = ResultFormatter.formatVariable(numVal, v, profile.formatting)
                        pillDisplay[v.name] = displayStr
                        if (v.alias.isNotBlank()) {
                            pillDisplay[v.alias] = displayStr
                        }
                    } else if (strVal != null) {
                        val displayStr = "${v.prefix}$strVal${v.postfix}"
                        pillDisplay[v.name] = displayStr
                        if (v.alias.isNotBlank()) {
                            pillDisplay[v.alias] = displayStr
                        }
                    }
                }

                // Step 2: Determine primary result from computed variables
                val computedVars = profile.parsingRules.variables.filter { it.isComputed }
                val primaryComputed = computedVars.lastOrNull() ?: computedVars.firstOrNull()

                if (primaryComputed != null) {
                    val compValue = parseOutcome.numericVariables[primaryComputed.name] ?: 0.0
                    val formattedResult = pillDisplay[primaryComputed.name]
                        ?: ResultFormatter.formatVariable(compValue, primaryComputed, profile.formatting)

                    ParsedQrResult(
                        rawText = rawText,
                        extractedVariables = parseOutcome.numericVariables,
                        extractedStrings = parseOutcome.stringVariables,
                        variablePillDisplay = pillDisplay,
                        status = CalculationStatus.Success(compValue, formattedResult),
                        formulaExpression = "${primaryComputed.name} = ${primaryComputed.computationFormula}",
                        barcodeFormat = format,
                        formatName = formatName
                    )
                } else if (profile.formula.expression.isNotBlank()) {
                    // Graceful formula evaluation fallback when no computed variables defined
                    when (val evalOutcome = mathEvaluator.evaluate(profile.formula.expression, parseOutcome.numericVariables, parseOutcome.stringVariables)) {
                        is MathResult.Success -> {
                            val formatted = ResultFormatter.format(evalOutcome.value, profile.formatting)
                            ParsedQrResult(
                                rawText = rawText,
                                extractedVariables = parseOutcome.numericVariables,
                                extractedStrings = parseOutcome.stringVariables,
                                variablePillDisplay = pillDisplay,
                                status = CalculationStatus.Success(evalOutcome.value, formatted),
                                formulaExpression = profile.formula.expression,
                                barcodeFormat = format,
                                formatName = formatName
                            )
                        }
                        is MathResult.Error -> {
                            ParsedQrResult(
                                rawText = rawText,
                                extractedVariables = parseOutcome.numericVariables,
                                extractedStrings = parseOutcome.stringVariables,
                                variablePillDisplay = pillDisplay,
                                status = CalculationStatus.EvaluationError(evalOutcome.message),
                                formulaExpression = profile.formula.expression,
                                barcodeFormat = format,
                                formatName = formatName
                            )
                        }
                    }
                } else {
                    // No computed variable defined: use first token variable or primary numeric value
                    val firstVar = profile.parsingRules.variables.firstOrNull { !it.isManual }
                        ?: profile.parsingRules.variables.firstOrNull()
                    val numVal = if (firstVar != null) parseOutcome.numericVariables[firstVar.name] ?: 0.0 else 0.0
                    val formatted = if (firstVar != null) {
                        pillDisplay[firstVar.name] ?: ResultFormatter.formatVariable(numVal, firstVar, profile.formatting)
                    } else {
                        ResultFormatter.format(numVal, profile.formatting)
                    }

                    ParsedQrResult(
                        rawText = rawText,
                        extractedVariables = parseOutcome.numericVariables,
                        extractedStrings = parseOutcome.stringVariables,
                        variablePillDisplay = pillDisplay,
                        status = CalculationStatus.Success(numVal, formatted),
                        formulaExpression = "",
                        barcodeFormat = format,
                        formatName = formatName
                    )
                }
            }
        }
    }
}
