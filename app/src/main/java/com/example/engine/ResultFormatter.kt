package com.example.engine

import com.example.model.AppRoundingMode
import com.example.model.ResultFormatting
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

object ResultFormatter {

    fun format(value: Double, formatting: ResultFormatting): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "+Infinity" else "-Infinity"

        val isNegative = value < 0
        val absValue = abs(value)

        // Precision & Rounding
        val precision = when (formatting.numericType) {
            "Integer" -> 0
            else -> formatting.decimalPrecision.coerceIn(0, 10)
        }

        val roundedBigDecimal: BigDecimal = try {
            val bd = BigDecimal.valueOf(absValue)
            when (formatting.roundingMode) {
                AppRoundingMode.NO_ROUNDING -> bd.setScale(precision, RoundingMode.HALF_UP)
                AppRoundingMode.ROUND_HALF_UP -> bd.setScale(precision, RoundingMode.HALF_UP)
                AppRoundingMode.ROUND_HALF_DOWN -> bd.setScale(precision, RoundingMode.HALF_DOWN)
                AppRoundingMode.ROUND_HALF_EVEN -> bd.setScale(precision, RoundingMode.HALF_EVEN)
                AppRoundingMode.CEILING -> bd.setScale(precision, RoundingMode.CEILING)
                AppRoundingMode.FLOOR -> bd.setScale(precision, RoundingMode.FLOOR)
                AppRoundingMode.TRUNCATE -> bd.setScale(precision, RoundingMode.DOWN)
            }
        } catch (e: Exception) {
            BigDecimal.valueOf(absValue).setScale(precision, RoundingMode.HALF_UP)
        }

        val plainStr = roundedBigDecimal.toPlainString()
        val parts = plainStr.split('.')
        var integerPart = parts[0]
        val fractionalPart = if (parts.size > 1) parts[1] else ""

        // Min integer digits (leading zeros)
        if (formatting.showLeadingZeros && integerPart.length < formatting.minIntegerDigits) {
            integerPart = integerPart.padStart(formatting.minIntegerDigits, '0')
        }

        // Thousands separator
        val groupedIntegerPart = when (formatting.thousandsSeparator) {
            "," -> groupDigits(integerPart, ',')
            "." -> groupDigits(integerPart, '.')
            " " -> groupDigits(integerPart, ' ')
            "'" -> groupDigits(integerPart, '\'')
            else -> integerPart
        }

        // Decimal representation
        val formattedNumber = if (precision > 0 && fractionalPart.isNotEmpty()) {
            "$groupedIntegerPart${formatting.decimalSeparator}$fractionalPart"
        } else {
            groupedIntegerPart
        }

        // Negative sign & Prefix/Postfix
        val output = if (isNegative && absValue > 1e-12) {
            when (formatting.negativeFormat) {
                "(x)" -> "(${formatting.prefix}$formattedNumber${formatting.postfix})"
                "-prefix" -> "-${formatting.prefix}$formattedNumber${formatting.postfix}"
                else -> "${formatting.prefix}-$formattedNumber${formatting.postfix}"
            }
        } else {
            "${formatting.prefix}$formattedNumber${formatting.postfix}"
        }

        return output
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
