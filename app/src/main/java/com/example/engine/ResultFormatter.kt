package com.example.engine

import com.example.model.AppRoundingMode
import com.example.model.FieldDataType
import com.example.model.ResultFormatting
import com.example.model.VariableDefinition
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

object ResultFormatter {

    fun format(value: Double, formatting: ResultFormatting): String {
        val precision = when (formatting.numericType) {
            "Integer" -> 0
            else -> formatting.decimalPrecision.coerceIn(0, 10)
        }
        return formatCore(
            value = value,
            precision = precision,
            roundingMode = formatting.roundingMode,
            thousandsSeparator = formatting.thousandsSeparator,
            decimalSeparator = formatting.decimalSeparator,
            prefix = formatting.prefix,
            postfix = formatting.postfix,
            negativeFormat = formatting.negativeFormat,
            showLeadingZeros = formatting.showLeadingZeros,
            minIntegerDigits = formatting.minIntegerDigits
        )
    }

    /**
     * Formats a variable's numeric value taking into account variable-specific settings
     * with fallback to profile-level defaults.
     */
    fun formatVariable(
        value: Double,
        varDef: VariableDefinition,
        profileFormatting: ResultFormatting? = null,
        includePrefixPostfix: Boolean = true
    ): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "+Infinity" else "-Infinity"

        // 1. Precision (Decimal Places)
        val precision = when {
            varDef.dataType == FieldDataType.INTEGER -> 0
            varDef.decimalPlaces != null -> varDef.decimalPlaces.coerceIn(0, 10)
            profileFormatting != null && profileFormatting.numericType == "Integer" -> 0
            profileFormatting != null -> profileFormatting.decimalPrecision.coerceIn(0, 10)
            else -> if (value % 1.0 == 0.0) 0 else 2
        }

        // 2. Rounding Mode
        val roundingMode = varDef.roundingMode ?: profileFormatting?.roundingMode ?: AppRoundingMode.ROUND_HALF_UP

        // 3. Thousands Separator
        val thousandsSep = when {
            varDef.thousandsSeparator != "inherit" -> varDef.thousandsSeparator
            profileFormatting != null -> profileFormatting.thousandsSeparator
            else -> "none"
        }

        // 4. Decimal Separator
        val decimalSep = when {
            varDef.decimalSeparator != "inherit" -> varDef.decimalSeparator
            profileFormatting != null -> profileFormatting.decimalSeparator
            else -> "."
        }

        // 5. Negative Format
        val negFormat = when {
            varDef.negativeFormat != "inherit" -> varDef.negativeFormat
            profileFormatting != null -> profileFormatting.negativeFormat
            else -> "-x"
        }

        // 6. Prefix & Postfix
        val effPrefix = if (includePrefixPostfix) varDef.prefix else ""
        var effPostfix = if (includePrefixPostfix) varDef.postfix else ""
        if (varDef.isPercentage && !effPostfix.contains("%")) {
            effPostfix = if (effPostfix.isEmpty()) "%" else "$effPostfix%"
        }

        return formatCore(
            value = value,
            precision = precision,
            roundingMode = roundingMode,
            thousandsSeparator = thousandsSep,
            decimalSeparator = decimalSep,
            prefix = effPrefix,
            postfix = effPostfix,
            negativeFormat = negFormat,
            showLeadingZeros = profileFormatting?.showLeadingZeros ?: false,
            minIntegerDigits = profileFormatting?.minIntegerDigits ?: 1
        )
    }

    fun formatCore(
        value: Double,
        precision: Int,
        roundingMode: AppRoundingMode,
        thousandsSeparator: String,
        decimalSeparator: String,
        prefix: String,
        postfix: String,
        negativeFormat: String,
        showLeadingZeros: Boolean = false,
        minIntegerDigits: Int = 1
    ): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "+Infinity" else "-Infinity"

        val isNegative = value < 0
        val absValue = abs(value)

        val roundedBigDecimal: BigDecimal = try {
            val bd = BigDecimal.valueOf(absValue)
            when (roundingMode) {
                AppRoundingMode.NO_ROUNDING -> bd.setScale(precision, RoundingMode.HALF_UP)
                AppRoundingMode.ROUND_HALF_UP -> bd.setScale(precision, RoundingMode.HALF_UP)
                AppRoundingMode.ROUND_HALF_DOWN -> bd.setScale(precision, RoundingMode.HALF_DOWN)
                AppRoundingMode.ROUND_HALF_EVEN -> bd.setScale(precision, RoundingMode.HALF_EVEN)
                AppRoundingMode.CEILING -> bd.setScale(precision, RoundingMode.CEILING)
                AppRoundingMode.FLOOR -> bd.setScale(precision, RoundingMode.FLOOR)
                AppRoundingMode.TRUNCATE -> bd.setScale(precision, RoundingMode.DOWN)
            }
        } catch (_: Exception) {
            BigDecimal.valueOf(absValue).setScale(precision, RoundingMode.HALF_UP)
        }

        val plainStr = roundedBigDecimal.toPlainString()
        val parts = plainStr.split('.')
        var integerPart = parts[0]
        val fractionalPart = if (parts.size > 1) parts[1] else ""

        // Min integer digits (leading zeros)
        if (showLeadingZeros && integerPart.length < minIntegerDigits) {
            integerPart = integerPart.padStart(minIntegerDigits, '0')
        }

        // Thousands separator
        val groupedIntegerPart = when (thousandsSeparator) {
            "," -> groupDigits(integerPart, ',')
            "." -> groupDigits(integerPart, '.')
            " " -> groupDigits(integerPart, ' ')
            "'" -> groupDigits(integerPart, '\'')
            else -> integerPart
        }

        // Decimal representation
        val formattedNumber = if (precision > 0 && fractionalPart.isNotEmpty()) {
            "$groupedIntegerPart$decimalSeparator$fractionalPart"
        } else {
            groupedIntegerPart
        }

        // Negative sign & Prefix/Postfix
        return if (isNegative && absValue > 1e-12) {
            when (negativeFormat) {
                "(x)" -> "($prefix$formattedNumber$postfix)"
                "-prefix" -> "-$prefix$formattedNumber$postfix"
                "-x" -> "$prefix-$formattedNumber$postfix"
                else -> "-$prefix$formattedNumber$postfix"
            }
        } else {
            "$prefix$formattedNumber$postfix"
        }
    }

    private fun groupDigits(intStr: String, separator: Char): String {
        val sb = StringBuilder()
        val len = intStr.length
        for (i in 0 until len) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append(separator)
            }
            sb.append(intStr[i])
        }
        return sb.toString()
    }
}

