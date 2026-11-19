package com.example.camera

import android.graphics.RectF
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.MathValueResult
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.CalculationStatus
import com.example.model.GlobalVariable
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

    private val lock = Any()
    private val activeTracked = mutableListOf<TrackedQr>()
    private val mathEvaluator = MathExpressionEvaluator()

    /**
     * Clears all currently tracked QR entries safely.
     */
    fun clear() {
        synchronized(lock) {
            activeTracked.clear()
        }
    }

    /**
     * Advances fade-out / expiration for tracked items without injecting a new camera frame.
     * Returns null when nothing is tracked or no alpha/expiry change occurred, preventing
     * redundant UI recompositions and effect cancellations.
     */
    fun tickFade(
        profile: QrProfile,
        currentTimeMs: Long = System.currentTimeMillis()
    ): List<TrackedQr>? = synchronized(lock) {
        if (activeTracked.isEmpty()) return@synchronized null

        val timeoutMs = profile.tracking.disappearanceTimeoutMs.coerceAtLeast(100L)
        var changed = false
        val iterator = activeTracked.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val elapsed = currentTimeMs - item.lastSeenMs
            if (elapsed > timeoutMs) {
                iterator.remove()
                changed = true
            } else if (elapsed > 60L) {
                val newAlpha = (1.0f - (elapsed.toFloat() / timeoutMs)).coerceIn(0f, 1f)
                if (kotlin.math.abs(newAlpha - item.alpha) >= 0.02f) {
                    item.alpha = newAlpha
                    changed = true
                }
            }
        }
        if (!changed) return@synchronized null
        activeTracked.map { it.copy(currentBox = RectF(it.currentBox)) }
    }

    /**
     * Updates tracked QR codes with newly detected codes in screen coordinates.
     */
    fun update(
        detectedScreenBoxes: List<RawDetectedCode>,
        profile: QrProfile,
        currentTimeMs: Long = System.currentTimeMillis(),
        attachmentFinder: ((String) -> QrAttachment?)? = null,
        globalVariables: List<GlobalVariable> = emptyList()
    ): List<TrackedQr> = synchronized(lock) {
        val smoothingFactor = profile.tracking.smoothingFactor.coerceIn(0.1f, 1.0f)
        val timeoutMs = profile.tracking.disappearanceTimeoutMs.coerceAtLeast(100L)

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

            val initialResult = processQr(
                unmatched.rawValue,
                profile,
                unmatched.format,
                unmatched.formatName,
                globalVariables
            )
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
                item.result = processQr(
                    item.rawContent,
                    profile,
                    item.barcodeFormat,
                    item.formatName,
                    globalVariables
                )
            }
        }

        activeTracked.map { it.copy(currentBox = RectF(it.currentBox)) }
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
        formatName: String = "QR CODE",
        globalVariables: List<GlobalVariable> = emptyList()
    ): ParsedQrResult {
        // Step 1: Parse variables & evaluate user-defined computed variables
        return when (val parseOutcome = QrParser.parse(rawText, profile.parsingRules, globalVariables)) {
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
                // Pre-compute formatted display string for every user variable
                val pillDisplay = mutableMapOf<String, String>()
                for (v in profile.parsingRules.variables) {
                    val strVal = parseOutcome.stringVariables[v.name]
                    val numVal = parseOutcome.numericVariables[v.name]
                    val isTextualType = v.dataType == com.example.model.FieldDataType.DATE ||
                        v.dataType == com.example.model.FieldDataType.STRING ||
                        v.dataType == com.example.model.FieldDataType.BOOLEAN
                    if (isTextualType && strVal != null) {
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

                // Step 2: Variable-centric headline selection (no forced embedded formula!)
                // Priority:
                // 1) User-starred primary variable (isPrimaryOutput = true)
                // 2) Last visible computed variable (or last computed variable)
                // 3) Legacy profile.formula (backward compatibility with older saved profiles)
                // 4) First visible variable (so formula-less profiles like library/warehouse work seamlessly)
                val allVars = profile.parsingRules.variables
                val pinnedVar = allVars.firstOrNull { it.isPrimaryOutput }
                val computedVars = allVars.filter { it.isComputed }
                val primaryVar = pinnedVar
                    ?: computedVars.lastOrNull { it.showPill }
                    ?: computedVars.lastOrNull()

                if (primaryVar != null) {
                    val numVal = parseOutcome.numericVariables[primaryVar.name] ?: 0.0
                    val formattedResult = pillDisplay[primaryVar.name]
                        ?: parseOutcome.stringVariables[primaryVar.name]?.let { "${primaryVar.prefix}$it${primaryVar.postfix}" }
                        ?: ResultFormatter.formatVariable(numVal, primaryVar, profile.formatting)
                    val exprHint = if (primaryVar.isComputed && primaryVar.computationFormula.isNotBlank()) {
                        "${primaryVar.name} = ${primaryVar.computationFormula}"
                    } else {
                        primaryVar.alias.ifBlank { primaryVar.name }
                    }
                    ParsedQrResult(
                        rawText = rawText,
                        extractedVariables = parseOutcome.numericVariables,
                        extractedStrings = parseOutcome.stringVariables,
                        variablePillDisplay = pillDisplay,
                        status = CalculationStatus.Success(numVal, formattedResult),
                        formulaExpression = exprHint,
                        barcodeFormat = format,
                        formatName = formatName
                    )
                } else if (profile.formula.expression.isNotBlank()) {
                    // Legacy fallback when an older profile relies on profile.formula
                    when (val evalOutcome = mathEvaluator.evaluateValue(profile.formula.expression, parseOutcome.numericVariables, parseOutcome.stringVariables)) {
                        is MathValueResult.SuccessNum -> {
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
                        is MathValueResult.SuccessText -> {
                            ParsedQrResult(
                                rawText = rawText,
                                extractedVariables = parseOutcome.numericVariables,
                                extractedStrings = parseOutcome.stringVariables,
                                variablePillDisplay = pillDisplay,
                                status = CalculationStatus.Success(0.0, evalOutcome.text),
                                formulaExpression = profile.formula.expression,
                                barcodeFormat = format,
                                formatName = formatName
                            )
                        }
                        is MathValueResult.Error -> {
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
                    // Pure variable profile with zero formulas: display first visible variable cleanly
                    val firstVar = allVars.firstOrNull { it.showPill }
                        ?: allVars.firstOrNull { !it.isManual }
                        ?: allVars.firstOrNull()
                    val numVal = if (firstVar != null) parseOutcome.numericVariables[firstVar.name] ?: 0.0 else 0.0
                    val formatted = if (firstVar != null) {
                        pillDisplay[firstVar.name]
                            ?: parseOutcome.stringVariables[firstVar.name]?.let { "${firstVar.prefix}$it${firstVar.postfix}" }
                            ?: ResultFormatter.formatVariable(numVal, firstVar, profile.formatting)
                    } else {
                        rawText
                    }

                    ParsedQrResult(
                        rawText = rawText,
                        extractedVariables = parseOutcome.numericVariables,
                        extractedStrings = parseOutcome.stringVariables,
                        variablePillDisplay = pillDisplay,
                        status = CalculationStatus.Success(numVal, formatted),
                        formulaExpression = firstVar?.alias?.ifBlank { firstVar.name } ?: "",
                        barcodeFormat = format,
                        formatName = formatName
                    )
                }
            }
        }
    }
}
