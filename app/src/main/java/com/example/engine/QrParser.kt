package com.example.engine

import com.example.model.FieldDataType
import com.example.model.QrParsingRules
import com.example.model.VariableDefinition

sealed class QrParseOutcome {
    data class Success(
        val numericVariables: Map<String, Double>,
        val stringVariables: Map<String, String>
    ) : QrParseOutcome()

    data class Error(val message: String) : QrParseOutcome()
}

object QrParser {

    private val mathEvaluator = MathExpressionEvaluator()

    /**
     * Parses the raw QR code text using the defined parsing rules.
     * Maps extracted tokens by position into variable names and aliases.
     */
    fun parse(rawText: String, rules: QrParsingRules): QrParseOutcome {
        val trimmed = if (rules.trimWhitespace) rawText.trim() else rawText
        if (trimmed.isEmpty()) {
            return QrParseOutcome.Error("QR content is empty")
        }

        val delimiter = rules.effectiveDelimiter
        if (delimiter.isEmpty()) {
            return QrParseOutcome.Error("Delimiter cannot be empty")
        }

        // Split tokens using exact delimiter
        val tokens = if (delimiter == " ") {
            trimmed.split("\\s+".toRegex())
        } else {
            trimmed.split(delimiter)
        }.map { if (rules.trimWhitespace) it.trim() else it }

        val numericMap = mutableMapOf<String, Double>()
        val stringMap = mutableMapOf<String, String>()

        // 1. Process manual / constant variables (not extracted from barcode tokens)
        val manualVars = rules.variables.filter { it.isManual && !it.isComputed }
        for (varDef in manualVars) {
            val valueToUse = varDef.defaultValue.ifBlank { "0" }
            var normalizedNumStr = valueToUse
            if (rules.decimalSeparator == ",") {
                normalizedNumStr = normalizedNumStr.replace(',', '.')
            }

            when (varDef.dataType) {
                FieldDataType.STRING -> {
                    stringMap[varDef.name] = valueToUse
                    if (varDef.alias.isNotBlank()) {
                        stringMap[varDef.alias] = valueToUse
                    }
                }
                FieldDataType.INTEGER, FieldDataType.FLOAT, FieldDataType.DOUBLE -> {
                    val parsedNumber = parseNumber(normalizedNumStr, rules.allowNegative) ?: 0.0
                    numericMap[varDef.name] = parsedNumber
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = parsedNumber
                    }
                }
            }
        }

        // 2. Process direct token variables
        val tokenVars = rules.variables.filter { !it.isComputed && !it.isManual }
        for (varDef in tokenVars) {
            val hasToken = varDef.position in tokens.indices
            val rawToken = if (hasToken) tokens[varDef.position] else null

            val tokenToUse = when {
                varDef.overrideBarcode && varDef.defaultValue.isNotBlank() -> varDef.defaultValue
                !rawToken.isNullOrEmpty() -> rawToken
                varDef.isOptional || varDef.defaultValue.isNotBlank() -> varDef.defaultValue
                else -> {
                    return QrParseOutcome.Error(
                        "Missing required field '${varDef.name}' at position ${varDef.position + 1}"
                    )
                }
            }

            // Normalise decimal separator
            var normalizedNumStr = tokenToUse
            if (rules.decimalSeparator == ",") {
                // If European format with comma decimal (e.g. 12,50), replace with dot for standard Double parsing
                normalizedNumStr = normalizedNumStr.replace(',', '.')
            }

            when (varDef.dataType) {
                FieldDataType.STRING -> {
                    stringMap[varDef.name] = tokenToUse
                    if (varDef.alias.isNotBlank()) {
                        stringMap[varDef.alias] = tokenToUse
                    }
                }
                FieldDataType.INTEGER, FieldDataType.FLOAT, FieldDataType.DOUBLE -> {
                    val parsedNumber = parseNumber(normalizedNumStr, rules.allowNegative)
                        ?: return QrParseOutcome.Error(
                            "Invalid numeric value '$tokenToUse' for '${varDef.name}'"
                        )

                    // Validate min/max boundaries
                    if (varDef.minValue != null && parsedNumber < varDef.minValue) {
                        return QrParseOutcome.Error(
                            "Field '${varDef.name}' value $parsedNumber is below minimum ${varDef.minValue}"
                        )
                    }
                    if (varDef.maxValue != null && parsedNumber > varDef.maxValue) {
                        return QrParseOutcome.Error(
                            "Field '${varDef.name}' value $parsedNumber exceeds maximum ${varDef.maxValue}"
                        )
                    }

                    numericMap[varDef.name] = parsedNumber
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = parsedNumber
                    }
                }
            }
        }

        // 3. Process computed / derived variables (e.g. price per gram = price / grams)
        val remainingComputed = rules.variables.filter { it.isComputed }.toMutableList()
        var progress = true
        var passes = 0
        while (remainingComputed.isNotEmpty() && progress && passes < 10) {
            passes++
            progress = false
            val iterator = remainingComputed.iterator()
            while (iterator.hasNext()) {
                val compVar = iterator.next()
                if (compVar.computationFormula.isBlank()) {
                    iterator.remove()
                    progress = true
                    continue
                }
                when (val res = mathEvaluator.evaluate(compVar.computationFormula, numericMap)) {
                    is MathResult.Success -> {
                        numericMap[compVar.name] = res.value
                        if (compVar.alias.isNotBlank()) {
                            numericMap[compVar.alias] = res.value
                        }
                        iterator.remove()
                        progress = true
                    }
                    is MathResult.Error -> {
                        // Might depend on another computed variable not yet evaluated in this pass
                    }
                }
            }
        }

        if (remainingComputed.isNotEmpty()) {
            val failedVar = remainingComputed.first()
            val err = mathEvaluator.evaluate(failedVar.computationFormula, numericMap)
            val msg = if (err is MathResult.Error) err.message else "Formula evaluation failed"
            return QrParseOutcome.Error("Computed '${failedVar.name}': $msg")
        }

        return QrParseOutcome.Success(numericMap, stringMap)
    }

    private fun parseNumber(str: String, allowNegative: Boolean): Double? {
        val trimmed = str.trim()
        if (trimmed.isEmpty()) return null
        if (!allowNegative && trimmed.startsWith("-")) return null

        return runCatching {
            trimmed.toDouble()
        }.getOrNull()
    }

    /**
     * Checks if a scanned QR code contains an embedded configuration profile.
     * Supported formats:
     * - "ARQR:PROFILE:<json>"
     * - Pure JSON containing "parsingRules" and "formula"
     */
    fun detectEmbeddedProfile(rawQrText: String): com.example.model.QrProfile? {
        val trimmed = rawQrText.trim()
        val jsonPayload = when {
            trimmed.startsWith("ARQR:PROFILE:") -> trimmed.removePrefix("ARQR:PROFILE:")
            trimmed.startsWith("{") && trimmed.contains("\"parsingRules\"") -> trimmed
            else -> null
        } ?: return null

        return runCatching {
            val json = org.json.JSONObject(jsonPayload)
            com.example.model.QrProfile.fromJson(json)
        }.getOrNull()
    }

    /**
     * Formats a profile into an embeddable QR code string.
     */
    fun encodeProfileToQrString(profile: com.example.model.QrProfile): String {
        return "ARQR:PROFILE:${profile.toJson()}"
    }

    /**
     * Finds a matching profile based on embedded trigger signals or tags in the QR text.
     */
    fun findMatchingProfileBySignal(
        rawQrText: String,
        profiles: List<com.example.model.QrProfile>
    ): com.example.model.QrProfile? {
        val text = rawQrText.trim()
        if (text.isEmpty()) return null

        // 1. Direct signal matching
        for (profile in profiles) {
            val signal = profile.triggerSignal.trim()
            if (signal.isNotEmpty()) {
                if (text.startsWith(signal, ignoreCase = true) || text.contains(signal, ignoreCase = true)) {
                    return profile
                }
            }
        }

        // 2. Structured profile tags: [PROFILE:name_or_id] or [P:name_or_id]
        val profileTagRegex = Regex("\\[(?:PROFILE|P):([^\\]]+)\\]", RegexOption.IGNORE_CASE)
        val match = profileTagRegex.find(text)
        if (match != null) {
            val targetKey = match.groupValues[1].trim()
            val matched = profiles.find {
                it.name.equals(targetKey, ignoreCase = true) || it.id.equals(targetKey, ignoreCase = true)
            }
            if (matched != null) return matched
        }

        return null
    }
}
