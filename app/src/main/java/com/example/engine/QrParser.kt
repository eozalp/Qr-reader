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

        for (varDef in rules.variables) {
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
}
