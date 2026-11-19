package com.example.engine

import com.example.model.*
import java.util.Locale
import java.util.UUID

enum class WizardFormulaPreset(val title: String, val subtitle: String) {
    NONE("Hesaplama Yok (Sadece Alanları Göster)", "Depo, kütüphane, envanter vb. düz etiket okuma"),
    SUM("Toplam (Hepsini Topla)", "Sayısal alanların toplamını hesaplayan değişken ekler"),
    MULTIPLY_FIRST_TWO("Çarpım (1. × 2. Alan)", "Örn: Litre × Fiyat, Gram × Kur veya Adet × Fiyat"),
    MARKUP_50("%50 Kâr Marjı (1. Alan × 1.5)", "Maliyet üzerinden satış fiyatı değişkeni ekler"),
    FIRST_VALUE("İlk Alanı Öne Çıkar", "Ek işlem yapmadan ilk alanı ana çıktı olarak sabitler")
}

/**
 * Pure domain logic powering the 3-step Smart Tag Setup Wizard.
 * Generalist-first: constructs user-owned variables (no hidden embedded formula!)
 * and supports formula-less profiles (warehouse, library, inventory) as well as computed profiles.
 */
object SmartTagWizardAnalyzer {

    val candidateDelimiters = listOf("-", "/", ";", ",", "|", ":", "_", "&", " ")

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
        val baseVariables = sampleTokens.mapIndexed { idx, rawTok ->
            val displayLabel = tokenLabels.getOrNull(idx)?.trim().orEmpty()
                .ifEmpty { "Alan ${idx + 1}" }
            var varName = sanitizeVarName(displayLabel, idx)
            var suffix = 2
            while (varName in usedNames) {
                varName = "${sanitizeVarName(displayLabel, idx)}_$suffix"
                suffix++
            }
            usedNames.add(varName)

            val isDate = QrParser.parseBarcodeDate(rawTok) != null && rawTok.length >= 6
            val isNumeric = !isDate && rawTok.replace(",", ".").toDoubleOrNull() != null
            val detectedType = when {
                isDate -> FieldDataType.DATE
                isNumeric -> FieldDataType.DOUBLE
                else -> FieldDataType.STRING
            }
            VariableDefinition(
                name = varName,
                position = idx,
                alias = displayLabel,
                dataType = detectedType,
                isPrimaryOutput = (formulaPreset == WizardFormulaPreset.FIRST_VALUE && idx == 0)
            )
        }

        val effectivePrefix = if (currencyPrefix == "Yok") "" else currencyPrefix
        val numericVars = baseVariables.filter { it.dataType == FieldDataType.DOUBLE }

        val computedExpr: String? = when (formulaPreset) {
            WizardFormulaPreset.NONE, WizardFormulaPreset.FIRST_VALUE -> null
            WizardFormulaPreset.SUM -> {
                if (numericVars.isNotEmpty()) numericVars.joinToString(" + ") { it.name }
                else baseVariables.firstOrNull()?.name
            }
            WizardFormulaPreset.MULTIPLY_FIRST_TWO -> {
                when {
                    numericVars.size >= 2 -> "${numericVars[0].name} * ${numericVars[1].name}"
                    numericVars.size == 1 -> numericVars[0].name
                    else -> null
                }
            }
            WizardFormulaPreset.MARKUP_50 -> {
                val base = numericVars.firstOrNull()?.name ?: baseVariables.firstOrNull()?.name
                base?.let { "$it * 1.5" }
            }
        }

        val allVariables = if (computedExpr != null) {
            baseVariables + VariableDefinition(
                name = "sonuc",
                alias = "Sonuç",
                dataType = FieldDataType.DOUBLE,
                isComputed = true,
                isPrimaryOutput = true,
                computationFormula = computedExpr,
                prefix = effectivePrefix
            )
        } else {
            baseVariables
        }

        val tableItems = allVariables.mapIndexed { idx, v ->
            ExcelTableCellItem(
                id = UUID.randomUUID().toString(),
                source = if (v.isComputed) TableItemSource.COMPUTED else TableItemSource.VARIABLE,
                variableName = v.name,
                label = v.alias.ifBlank { v.name },
                prefix = v.prefix,
                postfix = v.postfix,
                coordinate = "A${idx + 1}",
                isVisible = v.showPill,
                isEmphasized = v.isPrimaryOutput || v.isComputed
            )
        }

        return QrProfile(
            id = UUID.randomUUID().toString(),
            name = profileName.trim().ifEmpty { "Akıllı Etiket Profili" },
            description = if (computedExpr != null) {
                "Sihirbazla oluşturuldu (sonuc = $computedExpr)"
            } else {
                "Sihirbazla oluşturuldu (${allVariables.size} alan)"
            },
            triggerSignal = triggerSignal.trim(),
            parsingRules = QrParsingRules(
                delimiter = delimiter,
                variables = allVariables.ifEmpty { QrParsingRules().variables }
            ),
            formula = ArithmeticFormula(
                expression = "",
                description = ""
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
                    headerTitle = profileName.trim().uppercase(Locale("tr", "TR")).ifEmpty { "ETİKET KARTI" },
                    items = tableItems
                )
            )
        )
    }
}
