package com.example.engine

import android.util.Base64
import com.example.model.FieldDataType
import com.example.model.GlobalVariable
import com.example.model.QrParsingMode
import com.example.model.QrParsingRules
import com.example.model.VariableDefinition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
     * Parses the raw QR/barcode text using the defined parsing mode & rules.
     * Supports DELIMITER, KEY_VALUE, FIXED_WIDTH, and REGEX_GROUPS modes,
     * shared @global variables, and multi-type (Number/String/Date/Boolean) computed variables.
     */
    fun parse(
        rawText: String,
        rules: QrParsingRules,
        globalVariables: List<GlobalVariable> = emptyList()
    ): QrParseOutcome {
        val trimmed = if (rules.trimWhitespace) rawText.trim() else rawText
        if (trimmed.isEmpty()) {
            return QrParseOutcome.Error("Barkod / QR içeriği boş")
        }

        val delimiter = rules.effectiveDelimiter
        if (rules.parsingMode == QrParsingMode.DELIMITER && delimiter.isEmpty()) {
            return QrParseOutcome.Error("Ayırıcı karakter boş olamaz")
        }

        // Pre-extract tokens / key-value map / regex groups based on parsingMode
        val tokens: List<String> = when (rules.parsingMode) {
            QrParsingMode.DELIMITER -> {
                val rawTokens = if (delimiter == " ") trimmed.split("\\s+".toRegex())
                else trimmed.split(delimiter)
                rawTokens.map { if (rules.trimWhitespace) it.trim() else it }
            }

            QrParsingMode.KEY_VALUE -> emptyList()

            QrParsingMode.FIXED_WIDTH -> listOf(trimmed)

            QrParsingMode.REGEX_GROUPS -> {
                val pattern = rules.regexPattern.trim()
                if (pattern.isEmpty()) {
                    return QrParseOutcome.Error("Regex deseni boş")
                }
                val regex = runCatching { Regex(pattern) }.getOrElse {
                    return QrParseOutcome.Error("Geçersiz Regex deseni: ${it.message}")
                }
                val match = regex.find(trimmed)
                    ?: return QrParseOutcome.Error("Barkod Regex deseniyle eşleşmedi")
                match.groupValues.drop(1).map { if (rules.trimWhitespace) it.trim() else it }
            }
        }

        val keyValueMap: Map<String, String> = if (rules.parsingMode == QrParsingMode.KEY_VALUE) {
            parseKeyValuePairs(trimmed, delimiter, rules.keyValueSeparator.ifEmpty { "=" }, rules.trimWhitespace)
        } else {
            emptyMap()
        }

        val numericMap = mutableMapOf<String, Double>()
        val stringMap = mutableMapOf<String, String>()

        val today = LocalDate.now()
        val todayEpochDay = today.toEpochDay().toDouble()
        numericMap["bugun"] = todayEpochDay
        numericMap["today"] = todayEpochDay

        // 0. Inject shared @global variables (both "@name" and fallback plain "name")
        for (gv in globalVariables) {
            val cleanName = gv.name.trim().removePrefix("@")
            if (cleanName.isEmpty()) continue
            val valStr = gv.value.trim()
            val normalizedNum = if (rules.decimalSeparator == ",") valStr.replace(',', '.') else valStr
            when (gv.dataType) {
                FieldDataType.STRING -> {
                    stringMap["@$cleanName"] = valStr
                    stringMap[cleanName] = valStr
                }
                FieldDataType.BOOLEAN -> {
                    val b = parseBoolean(valStr) ?: false
                    val n = if (b) 1.0 else 0.0
                    val s = if (b) "Doğru" else "Yanlış"
                    numericMap["@$cleanName"] = n
                    numericMap[cleanName] = n
                    stringMap["@$cleanName"] = s
                    stringMap[cleanName] = s
                }
                FieldDataType.DATE -> {
                    val parsedDate = parseBarcodeDate(valStr) ?: today
                    val epochDay = parsedDate.toEpochDay().toDouble()
                    val formattedDate = parsedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    numericMap["@$cleanName"] = epochDay
                    numericMap[cleanName] = epochDay
                    stringMap["@$cleanName"] = formattedDate
                    stringMap[cleanName] = formattedDate
                }
                FieldDataType.INTEGER, FieldDataType.FLOAT, FieldDataType.DOUBLE -> {
                    val parsed = parseNumber(normalizedNum, allowNegative = true) ?: 0.0
                    val finalNum = when (gv.dataType) {
                        FieldDataType.INTEGER -> kotlin.math.truncate(parsed)
                        FieldDataType.FLOAT -> parsed.toFloat().toDouble()
                        else -> parsed
                    }
                    numericMap["@$cleanName"] = finalNum
                    numericMap[cleanName] = finalNum
                }
            }
        }

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
                FieldDataType.BOOLEAN -> {
                    val boolVal = parseBoolean(valueToUse) ?: false
                    val numBool = if (boolVal) 1.0 else 0.0
                    val strBool = if (boolVal) "Doğru" else "Yanlış"
                    numericMap[varDef.name] = numBool
                    stringMap[varDef.name] = strBool
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = numBool
                        stringMap[varDef.alias] = strBool
                    }
                }
                FieldDataType.DATE -> {
                    val parsedDate = parseBarcodeDate(valueToUse) ?: today
                    populateDateVariables(varDef, parsedDate, today, numericMap, stringMap)
                }
                FieldDataType.INTEGER -> {
                    val parsedNumber = parseNumber(normalizedNumStr, rules.allowNegative) ?: 0.0
                    val intVal = kotlin.math.truncate(parsedNumber)
                    numericMap[varDef.name] = intVal
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = intVal
                    }
                }
                FieldDataType.FLOAT -> {
                    val parsedNumber = parseNumber(normalizedNumStr, rules.allowNegative) ?: 0.0
                    val floatVal = parsedNumber.toFloat().toDouble()
                    numericMap[varDef.name] = floatVal
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = floatVal
                    }
                }
                FieldDataType.DOUBLE -> {
                    val parsedNumber = parseNumber(normalizedNumStr, rules.allowNegative) ?: 0.0
                    numericMap[varDef.name] = parsedNumber
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = parsedNumber
                    }
                }
            }
        }

        // 2. Process direct scanned variables according to parsingMode
        val tokenVars = rules.variables.filter { !it.isComputed && !it.isManual }
        for (varDef in tokenVars) {
            val rawToken: String? = when (rules.parsingMode) {
                QrParsingMode.DELIMITER, QrParsingMode.REGEX_GROUPS -> {
                    if (varDef.position in tokens.indices) tokens[varDef.position] else null
                }
                QrParsingMode.KEY_VALUE -> {
                    val primaryKey = varDef.extractionKey.trim().ifBlank { varDef.name }.lowercase()
                    val aliasKey = varDef.alias.trim().lowercase()
                    keyValueMap[primaryKey]
                        ?: keyValueMap[varDef.name.lowercase()]
                        ?: if (aliasKey.isNotEmpty()) keyValueMap[aliasKey] else null
                }
                QrParsingMode.FIXED_WIDTH -> {
                    val start = varDef.sliceStart.coerceAtLeast(0)
                    if (start >= trimmed.length) {
                        null
                    } else {
                        val end = if (varDef.sliceLength > 0) {
                            (start + varDef.sliceLength).coerceAtMost(trimmed.length)
                        } else {
                            trimmed.length
                        }
                        val sliced = trimmed.substring(start, end)
                        if (rules.trimWhitespace) sliced.trim() else sliced
                    }
                }
            }

            val tokenToUse = when {
                varDef.overrideBarcode && varDef.defaultValue.isNotBlank() -> varDef.defaultValue
                !rawToken.isNullOrEmpty() -> rawToken
                varDef.isOptional || varDef.defaultValue.isNotBlank() -> varDef.defaultValue
                else -> {
                    val locationHint = when (rules.parsingMode) {
                        QrParsingMode.KEY_VALUE -> "'${varDef.extractionKey.ifBlank { varDef.name }}' anahtarı"
                        QrParsingMode.FIXED_WIDTH -> "${varDef.sliceStart}..${varDef.sliceStart + varDef.sliceLength} karakter aralığı"
                        else -> "${varDef.position + 1}. sıra"
                    }
                    return QrParseOutcome.Error(
                        "Missing required field '${varDef.name}' ($locationHint)"
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
                FieldDataType.BOOLEAN -> {
                    val boolVal = parseBoolean(tokenToUse)
                        ?: return QrParseOutcome.Error(
                            "Geçersiz mantıksal değer '$tokenToUse' ('${varDef.name}' için 1/0 veya true/false kullanın)"
                        )
                    val numBool = if (boolVal) 1.0 else 0.0
                    val strBool = if (boolVal) "Doğru" else "Yanlış"
                    numericMap[varDef.name] = numBool
                    stringMap[varDef.name] = strBool
                    if (varDef.alias.isNotBlank()) {
                        numericMap[varDef.alias] = numBool
                        stringMap[varDef.alias] = strBool
                    }
                }
                FieldDataType.DATE -> {
                    val parsedDate = parseBarcodeDate(tokenToUse)
                        ?: return QrParseOutcome.Error(
                            "Geçersiz tarih '$tokenToUse' ('${varDef.name}' için yyyyMMdd veya gg.aa.yyyy kullanın)"
                        )
                    populateDateVariables(varDef, parsedDate, today, numericMap, stringMap)
                }
                FieldDataType.INTEGER, FieldDataType.FLOAT, FieldDataType.DOUBLE -> {
                    val rawParsed = parseNumber(normalizedNumStr, rules.allowNegative)
                        ?: return QrParseOutcome.Error(
                            "Invalid numeric value '$tokenToUse' for '${varDef.name}'"
                        )
                    val parsedNumber = when (varDef.dataType) {
                        FieldDataType.INTEGER -> kotlin.math.truncate(rawParsed)
                        FieldDataType.FLOAT -> rawParsed.toFloat().toDouble()
                        else -> rawParsed
                    }

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

        // 3. Process computed / derived variables (supports Number, String, Date, and Boolean outputs!)
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
                when (val res = mathEvaluator.evaluateValue(compVar.computationFormula, numericMap, stringMap)) {
                    is MathValueResult.SuccessText -> {
                        val textVal = res.text
                        if (compVar.dataType == FieldDataType.DATE) {
                            val parsedDate = parseBarcodeDate(textVal)
                            if (parsedDate != null) {
                                populateDateVariables(compVar, parsedDate, today, numericMap, stringMap)
                            } else {
                                stringMap[compVar.name] = textVal
                                if (compVar.alias.isNotBlank()) stringMap[compVar.alias] = textVal
                            }
                        } else {
                            stringMap[compVar.name] = textVal
                            if (compVar.alias.isNotBlank()) stringMap[compVar.alias] = textVal
                            textVal.replace(',', '.').trim().toDoubleOrNull()?.let { asNum ->
                                if (compVar.dataType != FieldDataType.STRING) {
                                    numericMap[compVar.name] = asNum
                                    if (compVar.alias.isNotBlank()) numericMap[compVar.alias] = asNum
                                }
                            }
                        }
                        iterator.remove()
                        progress = true
                    }
                    is MathValueResult.SuccessNum -> {
                        val finalVal = when (compVar.dataType) {
                            FieldDataType.INTEGER -> kotlin.math.truncate(res.value)
                            FieldDataType.FLOAT -> res.value.toFloat().toDouble()
                            FieldDataType.BOOLEAN -> if (kotlin.math.abs(res.value) > 1e-12) 1.0 else 0.0
                            else -> res.value
                        }
                        when (compVar.dataType) {
                            FieldDataType.STRING -> {
                                val strFormatted = if (finalVal % 1.0 == 0.0 && kotlin.math.abs(finalVal) < 1e15) {
                                    finalVal.toLong().toString()
                                } else {
                                    finalVal.toString()
                                }
                                stringMap[compVar.name] = strFormatted
                                numericMap[compVar.name] = finalVal
                                if (compVar.alias.isNotBlank()) {
                                    stringMap[compVar.alias] = strFormatted
                                    numericMap[compVar.alias] = finalVal
                                }
                            }
                            FieldDataType.DATE -> {
                                val computedDate = runCatching {
                                    LocalDate.ofEpochDay(finalVal.toLong())
                                }.getOrNull()
                                if (computedDate != null) {
                                    populateDateVariables(compVar, computedDate, today, numericMap, stringMap)
                                } else {
                                    numericMap[compVar.name] = finalVal
                                }
                            }
                            FieldDataType.BOOLEAN -> {
                                val boolLabel = if (finalVal >= 0.5) "Doğru" else "Yanlış"
                                numericMap[compVar.name] = finalVal
                                stringMap[compVar.name] = boolLabel
                                if (compVar.alias.isNotBlank()) {
                                    numericMap[compVar.alias] = finalVal
                                    stringMap[compVar.alias] = boolLabel
                                }
                            }
                            else -> {
                                numericMap[compVar.name] = finalVal
                                if (compVar.alias.isNotBlank()) {
                                    numericMap[compVar.alias] = finalVal
                                }
                            }
                        }
                        iterator.remove()
                        progress = true
                    }
                    is MathValueResult.Error -> {
                        // Might depend on another computed variable not yet evaluated in this pass
                    }
                }
            }
        }

        if (remainingComputed.isNotEmpty()) {
            val failedVar = remainingComputed.first()
            val err = mathEvaluator.evaluateValue(failedVar.computationFormula, numericMap, stringMap)
            val msg = if (err is MathValueResult.Error) err.message else "Formül değerlendirilemedi"
            return QrParseOutcome.Error("Hesaplama '${failedVar.name}': $msg")
        }

        return QrParseOutcome.Success(numericMap, stringMap)
    }

    /**
     * Parses KEY=VALUE pairs from strings like "PLAKA=34ABC&LT=45.5&YAKIT=MOTORIN" or "RAF:B12;SKU:998".
     */
    private fun parseKeyValuePairs(
        raw: String,
        delimiter: String,
        kvSep: String,
        trimWhitespace: Boolean
    ): Map<String, String> {
        val entries = when {
            delimiter.isNotEmpty() && raw.contains(delimiter) -> {
                if (delimiter == " ") raw.split("\\s+".toRegex()) else raw.split(delimiter)
            }
            raw.contains("&") -> raw.split("&")
            raw.contains(";") -> raw.split(";")
            raw.contains("|") -> raw.split("|")
            raw.contains("\n") -> raw.split("\n")
            raw.contains(",") -> raw.split(",")
            else -> listOf(raw)
        }
        val result = mutableMapOf<String, String>()
        for (entry in entries) {
            val sepIdx = entry.indexOf(kvSep).takeIf { it > 0 }
                ?: entry.indexOf('=').takeIf { it > 0 }
                ?: entry.indexOf(':').takeIf { it > 0 }
                ?: continue
            val k = entry.substring(0, sepIdx).trim().lowercase()
            val vRaw = entry.substring(sepIdx + 1)
            val v = if (trimWhitespace) vRaw.trim() else vRaw
            if (k.isNotEmpty()) {
                result[k] = v
            }
        }
        return result
    }

    /**
     * Safely renames references to [oldName] -> [newName] across all computed variable formulas
     * using word-boundary replacement so partial words are never corrupted.
     */
    fun renameVariableReferences(
        rules: QrParsingRules,
        oldName: String,
        newName: String
    ): QrParsingRules {
        val oldClean = oldName.trim()
        val newClean = newName.trim()
        if (oldClean.isEmpty() || newClean.isEmpty() || oldClean == newClean) return rules

        val regex = Regex("(?<![A-Za-z0-9_@])${Regex.escape(oldClean)}(?![A-Za-z0-9_])")
        val updatedVars = rules.variables.map { v ->
            if (v.isComputed && v.computationFormula.isNotBlank()) {
                v.copy(computationFormula = regex.replace(v.computationFormula, newClean))
            } else {
                v
            }
        }
        return rules.copy(variables = updatedVars)
    }

    private fun populateDateVariables(
        varDef: VariableDefinition,
        date: LocalDate,
        today: LocalDate,
        numericMap: MutableMap<String, Double>,
        stringMap: MutableMap<String, String>
    ) {
        val epochDay = date.toEpochDay().toDouble()
        val daysElapsed = (today.toEpochDay() - date.toEpochDay()).toDouble()
        val monthsElapsed = daysElapsed / 30.4375
        val yearsElapsed = daysElapsed / 365.25
        val formattedDate = date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

        fun assignForKey(key: String) {
            stringMap[key] = formattedDate
            numericMap[key] = epochDay
            numericMap["${key}_gun"] = daysElapsed
            numericMap["${key}_days"] = daysElapsed
            numericMap["${key}_ay"] = monthsElapsed
            numericMap["${key}_months"] = monthsElapsed
            numericMap["${key}_yil"] = yearsElapsed
            numericMap["${key}_years"] = yearsElapsed
        }

        assignForKey(varDef.name)
        if (varDef.alias.isNotBlank()) {
            assignForKey(varDef.alias)
        }
    }

    /**
     * Parses common barcode date representations:
     * - yyyyMMdd (e.g. 20240115)
     * - yyyy-MM-dd (e.g. 2024-01-15)
     * - dd.MM.yyyy (e.g. 15.01.2024)
     * - dd/MM/yyyy (e.g. 15/01/2024)
     * - yyyy/MM/dd (e.g. 2024/01/15)
     * - dd-MM-yyyy (e.g. 15-01-2024)
     * - yyyyMM (e.g. 202401 -> 1st of month)
     * - MM.yyyy / MM/yyyy
     */
    fun parseBarcodeDate(raw: String): LocalDate? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.equals("today", ignoreCase = true) || cleaned.equals("bugun", ignoreCase = true)) {
            return LocalDate.now()
        }

        val fullFormatters = listOf(
            DateTimeFormatter.BASIC_ISO_DATE, // yyyyMMdd
            DateTimeFormatter.ISO_LOCAL_DATE, // yyyy-MM-dd
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
        )
        for (fmt in fullFormatters) {
            val parsed = runCatching { LocalDate.parse(cleaned, fmt) }.getOrNull()
            if (parsed != null) return parsed
        }

        // Handle month-year formats like 202401, 01.2024, 01/2024
        val ymCompact = Regex("^(\\d{4})(\\d{2})$").matchEntire(cleaned)
        if (ymCompact != null) {
            val y = ymCompact.groupValues[1].toIntOrNull()
            val m = ymCompact.groupValues[2].toIntOrNull()
            if (y != null && m != null && m in 1..12) {
                return runCatching { LocalDate.of(y, m, 1) }.getOrNull()
            }
        }
        val myDelimited = Regex("^(\\d{1,2})[./-](\\d{4})$").matchEntire(cleaned)
        if (myDelimited != null) {
            val m = myDelimited.groupValues[1].toIntOrNull()
            val y = myDelimited.groupValues[2].toIntOrNull()
            if (y != null && m != null && m in 1..12) {
                return runCatching { LocalDate.of(y, m, 1) }.getOrNull()
            }
        }
        return null
    }

    private fun parseNumber(str: String, allowNegative: Boolean): Double? {
        val trimmed = str.trim()
        if (trimmed.isEmpty()) return null
        if (!allowNegative && trimmed.startsWith("-")) return null

        return runCatching {
            trimmed.toDouble()
        }.getOrNull()
    }

    private fun parseBoolean(raw: String): Boolean? {
        return when (raw.trim().lowercase()) {
            "1", "true", "evet", "yes", "on", "var", "ok", "dogru", "doğru" -> true
            "0", "false", "hayir", "hayır", "no", "off", "yok", "yanlis", "yanlış" -> false
            else -> null
        }
    }

    /**
     * Checks if a scanned QR code contains an embedded configuration profile.
     * Supported formats:
     * - "ARQR:GZ:<base64>" (Ultra-compact GZIP compressed profile, recommended for fast scanning)
     * - "ARQR:PROFILE:<json>" (Uncompressed legacy format)
     * - Pure JSON containing "parsingRules" and "formula"
     */
    private fun base64EncodeUrlSafe(bytes: ByteArray): String {
        return runCatching {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }.getOrElse {
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        }
    }

    private fun base64DecodeUrlSafe(str: String): ByteArray {
        return runCatching {
            val padded = when (str.length % 4) {
                2 -> "$str=="
                3 -> "$str="
                else -> str
            }
            java.util.Base64.getUrlDecoder().decode(padded)
        }.getOrElse {
            runCatching {
                android.util.Base64.decode(str, android.util.Base64.URL_SAFE)
            }.getOrElse {
                android.util.Base64.decode(str, android.util.Base64.DEFAULT)
            }
        }
    }

    /**
     * Checks if a scanned QR code contains an embedded configuration profile.
     * Supported formats:
     * - "ARQR:GZ:<base64>" (Ultra-compact GZIP compressed profile, recommended for fast scanning)
     * - "ARQR:PROFILE:<json>" (Uncompressed legacy format)
     * - Pure JSON containing "parsingRules" and "formula"
     */
    fun detectEmbeddedProfile(rawQrText: String): com.example.model.QrProfile? {
        val trimmed = rawQrText.trim()
        val jsonPayload = when {
            trimmed.startsWith("ARQR:GZ:") -> {
                runCatching {
                    val b64 = trimmed.removePrefix("ARQR:GZ:")
                    val compressedBytes = base64DecodeUrlSafe(b64)
                    GZIPInputStream(ByteArrayInputStream(compressedBytes)).bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                }.getOrNull()
            }
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
     * Formats a profile into an ultra-compact GZIP-compressed QR code string.
     * Compresses the JSON payload by 70-85% so it fits in low-density QR codes
     * that scan instantly between phone screens.
     */
    fun encodeProfileToCompressedQrString(profile: com.example.model.QrProfile): String {
        return runCatching {
            val jsonString = profile.toJson().toString()
            val byteOut = ByteArrayOutputStream()
            GZIPOutputStream(byteOut).use { gzip ->
                gzip.write(jsonString.toByteArray(Charsets.UTF_8))
            }
            val compressedBytes = byteOut.toByteArray()
            val base64 = base64EncodeUrlSafe(compressedBytes)
            "ARQR:GZ:$base64"
        }.getOrElse {
            "ARQR:PROFILE:${profile.toJson()}"
        }
    }

    fun encodeProfileToQrString(profile: com.example.model.QrProfile): String {
        return "ARQR:PROFILE:${profile.toJson()}"
    }

    /**
     * Returns the original JSON byte count and the GZIP-compressed byte count.
     */
    fun getProfileCompressionStats(profile: com.example.model.QrProfile): Pair<Int, Int> {
        val originalBytes = profile.toJson().toString().toByteArray(Charsets.UTF_8).size
        val compressedString = encodeProfileToCompressedQrString(profile)
        val compressedBytes = compressedString.toByteArray(Charsets.UTF_8).size
        return Pair(originalBytes, compressedBytes)
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
