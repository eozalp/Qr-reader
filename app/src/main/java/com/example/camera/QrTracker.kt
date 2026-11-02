package com.example.camera

import android.graphics.RectF
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.CalculationStatus
import com.example.model.ParsedQrResult
import com.example.model.QrProfile
import com.example.model.TrackedQr
import java.util.UUID
import kotlin.math.hypot

data class RawDetectedCode(
    val rawValue: String,
    val uprightBoundingBox: RectF
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
        currentTimeMs: Long = System.currentTimeMillis()
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
                matchedIds.add(tracked.id)
            }
        }

        // 2. For remaining unmatched detected codes, create new tracked entries (up to maxTrackedCodes)
        for (unmatched in unmatchedDetected) {
            if (activeTracked.size >= profile.tracking.maxTrackedCodes) break

            val initialResult = processQr(unmatched.rawValue, profile)
            val newTracked = TrackedQr(
                id = UUID.randomUUID().toString(),
                rawContent = unmatched.rawValue,
                currentBox = RectF(unmatched.uprightBoundingBox),
                targetBox = RectF(unmatched.uprightBoundingBox),
                result = initialResult,
                lastSeenMs = currentTimeMs,
                firstSeenMs = currentTimeMs,
                alpha = 1.0f
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
                item.result = processQr(item.rawContent, profile)
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

    fun processQr(rawText: String, profile: QrProfile): ParsedQrResult {
        // Step 1: Parse variables
        return when (val parseOutcome = QrParser.parse(rawText, profile.parsingRules)) {
            is QrParseOutcome.Error -> {
                ParsedQrResult(
                    rawText = rawText,
                    status = CalculationStatus.ParseError(parseOutcome.message),
                    formulaExpression = profile.formula.expression
                )
            }
            is QrParseOutcome.Success -> {
                // Pre-compute formatted display string for each variable pill with its individual prefix and postfix
                val pillDisplay = mutableMapOf<String, String>()
                for (v in profile.parsingRules.variables) {
                    val numVal = parseOutcome.numericVariables[v.name]
                    if (numVal != null) {
                        val clean = if (numVal % 1.0 == 0.0) numVal.toLong().toString() else "%.2f".format(numVal)
                        val displayStr = "${v.prefix}$clean${v.postfix}"
                        pillDisplay[v.name] = displayStr
                        if (v.alias.isNotBlank()) {
                            pillDisplay[v.alias] = displayStr
                        }
                    } else {
                        val strVal = parseOutcome.stringVariables[v.name]
                        if (strVal != null) {
                            val displayStr = "${v.prefix}$strVal${v.postfix}"
                            pillDisplay[v.name] = displayStr
                            if (v.alias.isNotBlank()) {
                                pillDisplay[v.alias] = displayStr
                            }
                        }
                    }
                }

                // Step 2: Math formula evaluation
                when (val evalOutcome = mathEvaluator.evaluate(profile.formula.expression, parseOutcome.numericVariables)) {
                    is MathResult.Error -> {
                        ParsedQrResult(
                            rawText = rawText,
                            extractedVariables = parseOutcome.numericVariables,
                            extractedStrings = parseOutcome.stringVariables,
                            variablePillDisplay = pillDisplay,
                            status = CalculationStatus.EvaluationError(evalOutcome.message),
                            formulaExpression = profile.formula.expression
                        )
                    }
                    is MathResult.Success -> {
                        // Step 3: Format the calculated result
                        val formatted = ResultFormatter.format(evalOutcome.value, profile.formatting)
                        ParsedQrResult(
                            rawText = rawText,
                            extractedVariables = parseOutcome.numericVariables,
                            extractedStrings = parseOutcome.stringVariables,
                            variablePillDisplay = pillDisplay,
                            status = CalculationStatus.Success(evalOutcome.value, formatted),
                            formulaExpression = profile.formula.expression
                        )
                    }
                }
            }
        }
    }
}
