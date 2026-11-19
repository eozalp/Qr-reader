package com.example.engine

import com.example.model.*
import java.util.Locale
import java.util.UUID

enum class WizardFormulaPreset(val title: String, val subtitle: String) {
    SUM("Toplam (Hepsini Topla)", "Sayısal alanların toplamını hesaplar"),
    MULTIPLY_FIRST_TWO("Çarpım (1. × 2. Alan)", "Örn: Gram × Birim Fiyat veya Adet × Fiyat"),
    MARKUP_50("%50 Kâr Marjı (1. Alan × 1.5)", "Maliyet üzerinden hızlı satış fiyatı"),
    FIRST_VALUE("Sadece İlk Sayısal Değer", "Ek işlem yapmadan doğrudan gösterir")
}

/**
 * Pure domain logic powering the 3-step Smart Tag Setup Wizard.
 * Auto-detects delimiters, sanitizes variable identifiers, and constructs
 * a complete, immediately usable [QrProfile] with Mini Excel table cells.
 */
object SmartTagWizardAnalyzer {

    val candidateDelimiters = listOf("-", "/", ";", ",", "|", ":", "_", " ")

    fun detectDelimiter(sampleTag: String): String {
        val trimmed = sampleTag.trim()
        if (trimmed.isEmpty()) return "-"

        var bestDelimiter = "-"
        var bestScore = 0
        for (delim in candidateDelimiters) {
            val parts = trimmed.split(delim).filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val numericCount = parts.count { it.trim().replace(",", ".").toDoubleOrNull() != null }
                val score = parts.size * 10 + numericCount * 5
                if (score > bestScore) {
                    bestScore = score
                    bestDelimiter = delim
                }
            }
        }
        return bestDelimiter
    }

    fun splitSampleTokens(sampleTag: String, delimiter: String): List<String> {
        val trimmed = sampleTag.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (delimiter.isEmpty()) return listOf(trimmed)
        return trimmed.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun sanitizeVarName(label: String, fallbackIndex: Int): String {
        val lowered = label.trim().lowercase(Locale.US)
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ı", "i")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        return when {
            lowered.isEmpty() -> ('a' + (fallbackIndex % 26)).toString()
            lowered.first().isDigit() -> "v_$lowered"
            else -> lowered
        }
    }

    fun buildProfileFromWizard(
        profileName: String,
        delimiter: String,
        tokenLabels: List<String>,
        sampleTokens: List<String>,
        formulaPreset: WizardFormulaPreset,
        currencyPrefix: String,
        triggerSignal: String = ""
    ): QrProfile {
        val usedNames = mutableSetOf<String>()
        val variables = sampleTokens.mapIndexed { idx, rawTok ->
            val displayLabel = tokenLabels.getOrNull(idx)?.trim().orEmpty()
                .ifEmpty { "Alan ${idx + 1}" }
            var varName = sanitizeVarName(displayLabel, idx)
            var suffix = 2
            while (varName in usedNames) {
                varName = "${sanitizeVarName(displayLabel, idx)}_$suffix"
                suffix++
            }
            usedNames.add(varName)

            val isNumeric = rawTok.replace(",", ".").toDoubleOrNull() != null
            VariableDefinition(
                name = varName,
                position = idx,
                alias = displayLabel,
                dataType = if (isNumeric) FieldDataType.DOUBLE else FieldDataType.STRING
            )
        }

        val numericVars = variables.filter { it.dataType == FieldDataType.DOUBLE }
        val formulaExpr = when (formulaPreset) {
            WizardFormulaPreset.SUM -> {
                if (numericVars.isNotEmpty()) numericVars.joinToString(" + ") { it.name }
                else variables.firstOrNull()?.name ?: "a"
            }
            WizardFormulaPreset.MULTIPLY_FIRST_TWO -> {
                when {
                    numericVars.size >= 2 -> "${numericVars[0].name} * ${numericVars[1].name}"
                    numericVars.size == 1 -> numericVars[0].name
                    else -> "a"
                }
            }
            WizardFormulaPreset.MARKUP_50 -> {
                val base = numericVars.firstOrNull()?.name ?: variables.firstOrNull()?.name ?: "a"
                "$base * 1.5"
            }
            WizardFormulaPreset.FIRST_VALUE -> {
                numericVars.firstOrNull()?.name ?: variables.firstOrNull()?.name ?: "a"
            }
        }

        val effectivePrefix = if (currencyPrefix == "Yok") "" else currencyPrefix
        val tableItems = variables.mapIndexed { idx, v ->
            ExcelTableCellItem(
                id = UUID.randomUUID().toString(),
                source = TableItemSource.VARIABLE,
                variableName = v.name,
                label = v.alias.ifBlank { v.name },
                coordinate = "A${idx + 1}",
                isVisible = true,
                isEmphasized = false
            )
        } + ExcelTableCellItem(
            id = UUID.randomUUID().toString(),
            source = TableItemSource.FORMULA_RESULT,
            label = "SONUÇ",
            prefix = effectivePrefix,
            coordinate = "A${variables.size + 1}",
            isVisible = true,
            isEmphasized = true
        )

        return QrProfile(
            id = UUID.randomUUID().toString(),
            name = profileName.trim().ifEmpty { "Akıllı Etiket Profili" },
            description = "Sihirbazla oluşturuldu ($formulaExpr)",
            triggerSignal = triggerSignal.trim(),
            parsingRules = QrParsingRules(
                delimiter = delimiter,
                variables = variables.ifEmpty { QrParsingRules().variables }
            ),
            formula = ArithmeticFormula(
                expression = formulaExpr,
                description = formulaExpr
            ),
            formatting = ResultFormatting(
                prefix = effectivePrefix,
                decimalPrecision = 2,
                thousandsSeparator = ".",
                decimalSeparator = ","
            ),
            overlay = OverlayAppearance(
                displayMode = OverlayDisplayMode.EXCEL_TABLE,
                tableConfig = ExcelTableConfig(
                    headerTitle = profileName.trim().uppercase(Locale("tr", "TR")).ifEmpty { "FİYAT TABLOSU" },
                    items = tableItems
                )
            )
        )
    }
}
