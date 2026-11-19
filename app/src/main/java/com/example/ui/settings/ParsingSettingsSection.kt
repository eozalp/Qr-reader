package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.AppRoundingMode
import com.example.model.ArithmeticFormula
import com.example.model.FieldDataType
import com.example.model.QrParsingRules
import com.example.model.ResultFormatting
import com.example.model.VariableDefinition
import java.util.UUID

enum class VariableTypeSelection(val label: String) {
    TOKEN("Barkod Token"),
    CONSTANT("Sabit Değer"),
    FORMULA("Formül (fx)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsingSettingsSection(
    rules: QrParsingRules,
    onRulesChanged: (QrParsingRules) -> Unit,
    formatting: ResultFormatting,
    onFormattingChanged: (ResultFormatting) -> Unit,
    formula: ArithmeticFormula = ArithmeticFormula(),
    onFormulaChanged: ((ArithmeticFormula) -> Unit)? = null,
    triggerSignal: String = "",
    onTriggerSignalChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val delimiterPresets = listOf("-", ",", ";", "|", "/", " ")

    var showAddVariableSheet by remember { mutableStateOf(false) }
    var sheetInitialType by remember { mutableStateOf(VariableTypeSelection.TOKEN) }

    // Real-time test input initialized intelligently based on current delimiter & tokens
    var sampleQrText by remember {
        val delim = rules.effectiveDelimiter
        val initialText = if (rules.variables.count { !it.isComputed && !it.isManual } >= 2) {
            "150${delim}25${delim}3"
        } else {
            "100${delim}20"
        }
        mutableStateOf(initialText)
    }

    var showGlobalFormatCard by remember { mutableStateOf(false) }

    // Live Real-Time Evaluation
    val liveParseOutcome = remember(sampleQrText, rules) {
        if (sampleQrText.isNotBlank()) {
            QrParser.parse(sampleQrText, rules)
        } else {
            null
        }
    }

    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + navBottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Real-Time QR Live Simulator Card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Canlı QR Simülatörü",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "CANLI",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ayrıştırma, formül ve biçimlendirmeyi test etmek için örnek bir QR metni yazın:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = sampleQrText,
                        onValueChange = { sampleQrText = it },
                        label = { Text("Örnek QR Kodu Metni") },
                        placeholder = { Text("örn. 150-25-3 veya 49.95,2") },
                        modifier = Modifier.fillMaxWidth().testTag("sample_qr_input"),
                        singleLine = true,
                        trailingIcon = {
                            if (sampleQrText.isNotEmpty()) {
                                IconButton(onClick = { sampleQrText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Temizle")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Live Pill Outputs
                    when (liveParseOutcome) {
                        is QrParseOutcome.Success -> {
                            Text(
                                text = "Çıkarılan & Hesaplanan Değerler:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rules.variables.forEach { v ->
                                    val numVal = liveParseOutcome.numericVariables[v.name]
                                    val strVal = liveParseOutcome.stringVariables[v.name]
                                    val displayVal = when {
                                        numVal != null -> ResultFormatter.formatVariable(numVal, v, formatting)
                                        strVal != null -> "${v.prefix}$strVal${v.postfix}"
                                        else -> "—"
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (v.isComputed) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else if (v.isManual) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${v.name}:",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (v.isComputed) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = displayVal,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is QrParseOutcome.Error -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = liveParseOutcome.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        null -> {
                            Text(
                                text = "Önizleme için yukarıya örnek bir QR metni girin.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Quick Test Presets
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hazır:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        val delim = rules.effectiveDelimiter
                        listOf("100${delim}20", "50${delim}250", "12.5${delim}4${delim}1.25").forEach { preset ->
                            SuggestionChip(
                                onClick = { sampleQrText = preset },
                                label = { Text(preset, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // --- 1B. Main Calculation Builder (Ana Çıktı Hesaplama Oluşturucu) ---
        if (onFormulaChanged != null) {
            item {
                var expandMainBuilder by remember { mutableStateOf(true) }
                val computedVars = rules.variables.filter { it.isComputed }
                val liveSuccess = liveParseOutcome as? QrParseOutcome.Success

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandMainBuilder = !expandMainBuilder },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Calculate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Hesaplama Oluşturucu (Ana Formül)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (computedVars.isNotEmpty()) {
                                            "Not: '${computedVars.last().name}' hesaplanan değişkeni aktif; ana çıktı onu kullanır."
                                        } else {
                                            "Değişken, veri tipi, operatör ve fonksiyonlarla kendi formülünüzü kurun"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { expandMainBuilder = !expandMainBuilder }) {
                                Icon(
                                    imageVector = if (expandMainBuilder) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expandMainBuilder) "Daralt" else "Genişlet"
                                )
                            }
                        }

                        AnimatedVisibility(visible = expandMainBuilder) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                FormulaBuilderPanel(
                                    expression = formula.expression,
                                    onExpressionChanged = { newExpr ->
                                        onFormulaChanged(formula.copy(expression = newExpr))
                                    },
                                    variables = rules.variables,
                                    liveNumericVars = liveSuccess?.numericVariables ?: emptyMap(),
                                    liveStringVars = liveSuccess?.stringVariables ?: emptyMap(),
                                    formatting = formatting,
                                    label = "Ana Çıktı Formülü",
                                    placeholder = "örn: maliyet * (1 + oran / 100) veya if(adet > 5, fiyat * 0.9, fiyat)",
                                    testTagPrefix = "profile_main_formula",
                                    onRequestAddVariable = {
                                        sheetInitialType = VariableTypeSelection.TOKEN
                                        showAddVariableSheet = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 2. QR Token Delimiter & Auto-Switch Signal ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "QR Ayırıcı Karakter (Delimiter)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Barkod parçalarını bölen karakter (örn. '150-25' için '-'):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        delimiterPresets.forEach { delim ->
                            FilterChip(
                                selected = rules.delimiter == delim && rules.customDelimiter.isEmpty(),
                                onClick = {
                                    onRulesChanged(rules.copy(delimiter = delim, customDelimiter = ""))
                                },
                                label = { Text(if (delim == " ") "Boşluk" else "'$delim'", maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("delim_chip_$delim")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = rules.customDelimiter,
                        onValueChange = { onRulesChanged(rules.copy(customDelimiter = it)) },
                        label = { Text("Özel Ayırıcı (İsteğe Bağlı)") },
                        placeholder = { Text("örn. ##, :: veya /") },
                        modifier = Modifier.fillMaxWidth().testTag("custom_delim_input"),
                        singleLine = true
                    )

                    // Auto-Switch Signal Tag
                    if (onTriggerSignalChanged != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Otomatik Profil Tetikleyici", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Barkod bu etiketle başladığında bu profil otomatik açılır (örn. TESBIH- veya [P:amber])",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = triggerSignal,
                            onValueChange = onTriggerSignalChanged,
                            label = { Text("Sinyal Etiketi / Ön Ek") },
                            placeholder = { Text("örn. TESBIH-, GOLD- veya [P:amber]") },
                            modifier = Modifier.fillMaxWidth().testTag("trigger_signal_input"),
                            singleLine = true
                        )
                    }
                }
            }
        }

        // --- 3. Global Separators & Rounding Defaults (Consolidated from Format tab) ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showGlobalFormatCard = !showGlobalFormatCard },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Genel Ayırıcı ve Yuvarlama",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Binlik '${formatting.thousandsSeparator}', Ondalık '${formatting.decimalSeparator}', ${formatting.decimalPrecision} basamak",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = { showGlobalFormatCard = !showGlobalFormatCard }) {
                            Icon(
                                imageVector = if (showGlobalFormatCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showGlobalFormatCard) "Daralt" else "Genişlet"
                            )
                        }
                    }

                    AnimatedVisibility(visible = showGlobalFormatCard) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Thousands Separator
                            Text("Binlik Ayırıcı", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("none" to "Yok", "," to "Virgül (,)", "." to "Nokta (.)", " " to "Boşluk ( )", "'" to "Kesme (')").forEach { (code, label) ->
                                    FilterChip(
                                        selected = formatting.thousandsSeparator == code,
                                        onClick = { onFormattingChanged(formatting.copy(thousandsSeparator = code)) },
                                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Decimal Separator
                            Text("Ondalık Ayırıcı", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = formatting.decimalSeparator == ".",
                                    onClick = { onFormattingChanged(formatting.copy(decimalSeparator = ".")) },
                                    label = { Text("Nokta (.)", maxLines = 1, softWrap = false) }
                                )
                                FilterChip(
                                    selected = formatting.decimalSeparator == ",",
                                    onClick = { onFormattingChanged(formatting.copy(decimalSeparator = ",")) },
                                    label = { Text("Virgül (,)", maxLines = 1, softWrap = false) }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Default Decimal Precision
                            Text("Varsayılan Ondalık Basamak (${formatting.decimalPrecision})", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                (0..6).forEach { digits ->
                                    FilterChip(
                                        selected = formatting.decimalPrecision == digits,
                                        onClick = { onFormattingChanged(formatting.copy(decimalPrecision = digits)) },
                                        label = { Text("$digits", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Rounding Mode
                            Text("Yuvarlama Modu", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AppRoundingMode.entries.forEach { mode ->
                                    val cleanLabel = when (mode) {
                                        AppRoundingMode.NO_ROUNDING -> "Yuvarlama Yok"
                                        AppRoundingMode.ROUND_HALF_UP -> "Yukarı (0.5)"
                                        AppRoundingMode.ROUND_HALF_DOWN -> "Aşağı (0.5)"
                                        AppRoundingMode.ROUND_HALF_EVEN -> "Çift (Bankacı)"
                                        AppRoundingMode.CEILING -> "Tavan"
                                        AppRoundingMode.FLOOR -> "Taban"
                                        AppRoundingMode.TRUNCATE -> "Kesme"
                                    }
                                    FilterChip(
                                        selected = formatting.roundingMode == mode,
                                        onClick = { onFormattingChanged(formatting.copy(roundingMode = mode)) },
                                        label = { Text(cleanLabel, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Negative Number Format
                            Text("Negatif Sayı Biçimi", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("-x" to "-100", "(x)" to "(100)", "-prefix" to "-₺100").forEach { (fmt, example) ->
                                    FilterChip(
                                        selected = formatting.negativeFormat == fmt,
                                        onClick = { onFormattingChanged(formatting.copy(negativeFormat = fmt)) },
                                        label = { Text(example, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 4. Variables & Tokens Management Header & Quick Add ---
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                val scannedCount = rules.variables.count { !it.isComputed && !it.isManual }
                val manualCount = rules.variables.count { it.isManual && !it.isComputed }
                val computedCount = rules.variables.count { it.isComputed }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Değişkenler & Formüller",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$scannedCount barkod tokeni · $manualCount sabit · $computedCount formül",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${rules.variables.size} Adet",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Add 3-Way Buttons (Clean, never wrapping)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            sheetInitialType = VariableTypeSelection.TOKEN
                            showAddVariableSheet = true
                        },
                        modifier = Modifier.weight(1f).testTag("add_variable_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Token", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }

                    FilledTonalButton(
                        onClick = {
                            sheetInitialType = VariableTypeSelection.CONSTANT
                            showAddVariableSheet = true
                        },
                        modifier = Modifier.weight(1f).testTag("add_manual_variable_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sabit", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }

                    FilledTonalButton(
                        onClick = {
                            sheetInitialType = VariableTypeSelection.FORMULA
                            showAddVariableSheet = true
                        },
                        modifier = Modifier.weight(1f).testTag("add_computed_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Formül", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // --- 5. Variable Cards List ---
        val liveSuccessForCards = liveParseOutcome as? QrParseOutcome.Success
        itemsIndexed(rules.variables) { index, varDef ->
            VariableItemCard(
                varDef = varDef,
                index = index,
                allVariables = rules.variables,
                liveNumericVars = liveSuccessForCards?.numericVariables ?: emptyMap(),
                liveStringVars = liveSuccessForCards?.stringVariables ?: emptyMap(),
                globalFormatting = formatting,
                onRequestAddVariable = {
                    sheetInitialType = VariableTypeSelection.TOKEN
                    showAddVariableSheet = true
                },
                onUpdate = { updated ->
                    val newList = rules.variables.toMutableList()
                    newList[index] = updated
                    onRulesChanged(rules.copy(variables = newList))
                },
                onDelete = {
                    val newList = rules.variables.toMutableList()
                    newList.removeAt(index)
                    onRulesChanged(rules.copy(variables = newList))
                }
            )
        }
    }

    // Sliding Bottom Sheet for configuring and adding a new Token or Variable (Never silent adding)
    if (showAddVariableSheet) {
        AddVariableBottomSheet(
            initialType = sheetInitialType,
            existingVariables = rules.variables,
            delimiter = rules.effectiveDelimiter,
            onDismiss = { showAddVariableSheet = false },
            onAddVariable = { newVar ->
                onRulesChanged(rules.copy(variables = rules.variables + newVar))
                showAddVariableSheet = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariableItemCard(
    varDef: VariableDefinition,
    index: Int,
    allVariables: List<VariableDefinition> = emptyList(),
    liveNumericVars: Map<String, Double> = emptyMap(),
    liveStringVars: Map<String, String> = emptyMap(),
    globalFormatting: ResultFormatting? = null,
    onRequestAddVariable: (() -> Unit)? = null,
    onUpdate: (VariableDefinition) -> Unit,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Type Badge, Name, Summary Preview, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded }
                ) {
                    val badgeColor = when {
                        varDef.isComputed -> MaterialTheme.colorScheme.tertiaryContainer
                        varDef.isManual -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                    val badgeTextColor = when {
                        varDef.isComputed -> MaterialTheme.colorScheme.onTertiaryContainer
                        varDef.isManual -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    val badgeText = when {
                        varDef.isComputed -> "fx Formül"
                        varDef.isManual -> "📌 Sabit"
                        else -> "Sıra ${varDef.position}"
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeTextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = varDef.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val summaryDetail = when {
                            varDef.isComputed -> if (varDef.computationFormula.isNotBlank()) "= ${varDef.computationFormula}" else "Formül tanımlanmadı"
                            varDef.isManual -> "Sabit: ${varDef.defaultValue}"
                            else -> "Token ${varDef.position}${if (varDef.prefix.isNotBlank() || varDef.postfix.isNotBlank()) " (${varDef.prefix}…${varDef.postfix})" else ""}"
                        }
                        Text(
                            text = summaryDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Daralt" else "Genişlet",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Değişkeni Sil",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expanded Controls
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Source Type Selector: Token vs Constant vs Formula
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !varDef.isComputed && !varDef.isManual,
                            onClick = { onUpdate(varDef.copy(isComputed = false, isManual = false)) },
                            label = { Text("Barkod Token", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isManual,
                            onClick = { onUpdate(varDef.copy(isComputed = false, isManual = true)) },
                            label = { Text("Sabit Değer", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isComputed,
                            onClick = { onUpdate(varDef.copy(isComputed = true, isManual = false)) },
                            label = { Text("Formül (fx)", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Variable Name & Alias
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varDef.name,
                            onValueChange = { onUpdate(varDef.copy(name = it.filter { c -> c.isLetterOrDigit() || c == '_' })) },
                            label = { Text("Değişken Adı") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.alias,
                            onValueChange = { onUpdate(varDef.copy(alias = it.filter { c -> c.isLetterOrDigit() || c == '_' })) },
                            label = { Text("Etiket / Açıklama") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Veri Tipi (Data Type) Selector: Tam Sayı, Ondalıklı, Metin, Tarih (Date)
                    Text(
                        text = "Veri Tipi (Data Type):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FieldDataType.entries.forEach { dt ->
                            FilterChip(
                                selected = varDef.dataType == dt,
                                onClick = { onUpdate(varDef.copy(dataType = dt)) },
                                label = { Text(dt.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("datatype_chip_${varDef.name}_${dt.name}")
                            )
                        }
                    }

                    if (varDef.dataType == FieldDataType.DATE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📅 Tarih Formatları: 20240115, 15.01.2024, 2024-01-15, 15/01/2024. Formülde '${varDef.name}_gun', '${varDef.name}_ay', '${varDef.name}_yil', 'year(${varDef.name})', 'month(${varDef.name})', 'bugun - ${varDef.name}' kullanılabilir.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else if (varDef.dataType == FieldDataType.BOOLEAN) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚡ Mantıksal Tip: Barkodda 1/0, true/false, evet/hayır değerlerini okur. Formülde 'if(${varDef.name}, ... , ...)' veya '${varDef.name} ? a : b' ile kullanabilirsiniz.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Specific Type Controls
                    when {
                        varDef.isComputed -> {
                            FormulaBuilderPanel(
                                expression = varDef.computationFormula,
                                onExpressionChanged = { newFormula ->
                                    onUpdate(varDef.copy(computationFormula = newFormula))
                                },
                                variables = allVariables.filter { it.name != varDef.name },
                                liveNumericVars = liveNumericVars,
                                liveStringVars = liveStringVars,
                                formatting = globalFormatting,
                                previewVariable = varDef,
                                label = "Formül (${varDef.name})",
                                placeholder = "örn: a * b veya if(a > 100, a * 0.9, a)",
                                testTagPrefix = "formula_input_${varDef.name}",
                                onRequestAddVariable = onRequestAddVariable
                            )
                        }

                        varDef.isManual -> {
                            OutlinedTextField(
                                value = varDef.defaultValue,
                                onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                label = { Text("Sabit Değer") },
                                placeholder = { Text("örn. 18, 5.5 veya 0.20") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        else -> {
                            // Barcode Token Position and Default
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = varDef.position.toString(),
                                    onValueChange = { str ->
                                        val pos = str.toIntOrNull() ?: 0
                                        onUpdate(varDef.copy(position = pos))
                                    },
                                    label = { Text("Token Sırası", maxLines = 1, softWrap = false) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = varDef.defaultValue,
                                    onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                    label = { Text("Varsayılan Değer", maxLines = 1, softWrap = false) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Prefix and Postfix
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varDef.prefix,
                            onValueChange = { onUpdate(varDef.copy(prefix = it)) },
                            label = { Text("Ön Ek (₺, $)", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.postfix,
                            onValueChange = { onUpdate(varDef.copy(postfix = it)) },
                            label = { Text("Son Ek (/gr, ad)", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Number Formatting & Decimals
                    if (varDef.dataType != FieldDataType.STRING && varDef.dataType != FieldDataType.DATE && varDef.dataType != FieldDataType.BOOLEAN) {
                        Text("Ondalık Basamak:", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val decOptions = listOf(
                                null to "Oto",
                                0 to "0 (Tam)",
                                1 to "1",
                                2 to "2",
                                3 to "3",
                                4 to "4",
                                5 to "5"
                            )
                            decOptions.forEach { (dec, label) ->
                                FilterChip(
                                    selected = varDef.decimalPlaces == dec,
                                    onClick = { onUpdate(varDef.copy(decimalPlaces = dec)) },
                                    label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = varDef.isPercentage,
                                    onCheckedChange = { onUpdate(varDef.copy(isPercentage = it)) }
                                )
                                Text("Yüzde (%)", style = MaterialTheme.typography.bodySmall)
                            }

                            val previewSample = ResultFormatter.formatVariable(1234.5678, varDef, globalFormatting, true)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "Önizleme: $previewSample",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Show Pill Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kamera Kartında Göster", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = varDef.showPill,
                            onCheckedChange = { onUpdate(varDef.copy(showPill = it)) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sliding Bottom Sheet for configuring and adding a new Token, Constant, or Formula variable.
 * Provides user-guided input instead of silent variable addition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVariableBottomSheet(
    initialType: VariableTypeSelection,
    existingVariables: List<VariableDefinition>,
    delimiter: String,
    onDismiss: () -> Unit,
    onAddVariable: (VariableDefinition) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }

    val nextTokenPos = remember(existingVariables) {
        val tokenVars = existingVariables.filter { !it.isComputed && !it.isManual }
        tokenVars.size
    }
    val defaultTokenName = remember(existingVariables) {
        val tokenCount = existingVariables.count { !it.isComputed && !it.isManual }
        if (tokenCount < 26) ('a'.code + tokenCount).toChar().toString() else "var$tokenCount"
    }
    val defaultConstantName = remember(existingVariables) {
        val manualCount = existingVariables.count { it.isManual } + 1
        "m$manualCount"
    }
    val defaultFormulaName = remember(existingVariables) {
        val compCount = existingVariables.count { it.isComputed } + 1
        "calc$compCount"
    }

    var varName by remember(selectedType) {
        mutableStateOf(
            when (selectedType) {
                VariableTypeSelection.TOKEN -> defaultTokenName
                VariableTypeSelection.CONSTANT -> defaultConstantName
                VariableTypeSelection.FORMULA -> defaultFormulaName
            }
        )
    }
    var varAlias by remember { mutableStateOf("") }
    var selectedDataType by remember { mutableStateOf(FieldDataType.DOUBLE) }
    var tokenPositionStr by remember { mutableStateOf(nextTokenPos.toString()) }
    var defaultValueStr by remember { mutableStateOf("0") }
    var constantValueStr by remember { mutableStateOf("1") }
    var formulaText by remember { mutableStateOf("") }
    var prefixStr by remember { mutableStateOf("") }
    var postfixStr by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (selectedType) {
                                VariableTypeSelection.TOKEN -> Icons.Default.QrCodeScanner
                                VariableTypeSelection.CONSTANT -> Icons.Default.Pin
                                VariableTypeSelection.FORMULA -> Icons.Default.Calculate
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Değişken / Token Ekle",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Yeni belirteç, sabit veya formül tanımla",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat")
                }
            }

            // Type Segmented Pills (equal height, centered, clean padding)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VariableTypeSelection.entries.forEach { type ->
                    val isSelected = selectedType == type
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { selectedType = type }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Name and Alias
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = varName,
                    onValueChange = { varName = it.filter { c -> c.isLetterOrDigit() || c == '_' }.lowercase() },
                    label = { Text("Değişken Adı *") },
                    placeholder = { Text("örn. tarih, maliyet, fiyat") },
                    modifier = Modifier.weight(1f).testTag("new_variable_name_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = varAlias,
                    onValueChange = { varAlias = it },
                    label = { Text("Etiket / Açıklama") },
                    placeholder = { Text("örn. Alış Tarihi") },
                    modifier = Modifier.weight(1f).testTag("new_variable_alias_input"),
                    singleLine = true
                )
            }

            // Data Type Selector (Tam Sayı, Ondalıklı, Metin, Tarih)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Veri Tipi (Data Type)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FieldDataType.entries.forEach { dt ->
                        FilterChip(
                            selected = selectedDataType == dt,
                            onClick = {
                                selectedDataType = dt
                                if (dt == FieldDataType.DATE && defaultValueStr == "0") {
                                    defaultValueStr = "20240115"
                                }
                            },
                            label = { Text(dt.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("new_variable_datatype_${dt.name}")
                        )
                    }
                }
                if (selectedDataType == FieldDataType.DATE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tarih barkodu örnekleri: 20240115, 15.01.2024, 2024-01-15. Formülde '${varName}_ay', '${varName}_gun', 'year($varName)', 'bugun - $varName' kullanılabilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                } else if (selectedDataType == FieldDataType.BOOLEAN) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mantıksal değerler: 1/0, true/false, evet/hayır. Koşul ifadelerinde (if, ?, ==, &&, ||) doğrudan kullanılabilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
            }

            // Type-specific inputs
            when (selectedType) {
                VariableTypeSelection.TOKEN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tokenPositionStr,
                            onValueChange = { tokenPositionStr = it.filter { c -> c.isDigit() } },
                            label = { Text("Token Sırası (0..N)", maxLines = 1, softWrap = false) },
                            placeholder = { Text("0 = 1. parça, 1 = 2.") },
                            modifier = Modifier.weight(1f).testTag("new_variable_position_input"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = defaultValueStr,
                            onValueChange = { defaultValueStr = it },
                            label = { Text("Varsayılan Değer", maxLines = 1, softWrap = false) },
                            placeholder = { Text("0") },
                            modifier = Modifier.weight(1f).testTag("new_variable_default_input"),
                            singleLine = true
                        )
                    }
                    Text(
                        text = "'$delimiter' ile ayrılan barkodun ${tokenPositionStr.ifBlank { "0" }}. parçası '$varName' değişkenine atanır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VariableTypeSelection.CONSTANT -> {
                    OutlinedTextField(
                        value = constantValueStr,
                        onValueChange = { constantValueStr = it },
                        label = { Text("Sabit Değer *") },
                        placeholder = { Text("örn. 100, 1.18, 20240115 veya true") },
                        modifier = Modifier.fillMaxWidth().testTag("new_constant_value_input"),
                        singleLine = true
                    )
                    Text(
                        text = "Barkoddan okunmaz, tüm formüllerde sabit parametre olarak kullanılır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VariableTypeSelection.FORMULA -> {
                    FormulaBuilderPanel(
                        expression = formulaText,
                        onExpressionChanged = { formulaText = it },
                        variables = existingVariables,
                        label = "Hesaplanan Değişken Formülü *",
                        placeholder = "örn: maliyet * (1 + oran / 100) veya if(adet > 5, a, b)",
                        testTagPrefix = "new_formula_expression"
                    )
                }
            }

            // Prefix and Postfix (Short single-line labels so heights match identically)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = prefixStr,
                    onValueChange = { prefixStr = it },
                    label = { Text("Ön Ek (₺, $)", maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                OutlinedTextField(
                    value = postfixStr,
                    onValueChange = { postfixStr = it },
                    label = { Text("Son Ek (/gr, ad)", maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.45f)
                ) {
                    Text("İptal")
                }

                Button(
                    onClick = {
                        val trimmedName = varName.trim().ifBlank { defaultTokenName }
                        val constructed = when (selectedType) {
                            VariableTypeSelection.TOKEN -> {
                                val pos = tokenPositionStr.toIntOrNull() ?: nextTokenPos
                                VariableDefinition(
                                    id = UUID.randomUUID().toString(),
                                    position = pos,
                                    name = trimmedName,
                                    alias = varAlias.trim(),
                                    isComputed = false,
                                    isManual = false,
                                    defaultValue = defaultValueStr.ifBlank { if (selectedDataType == FieldDataType.DATE) "20240115" else "0" },
                                    prefix = prefixStr,
                                    postfix = postfixStr,
                                    dataType = selectedDataType
                                )
                            }
                            VariableTypeSelection.CONSTANT -> {
                                VariableDefinition(
                                    id = UUID.randomUUID().toString(),
                                    position = -1,
                                    name = trimmedName,
                                    alias = varAlias.trim(),
                                    isComputed = false,
                                    isManual = true,
                                    defaultValue = constantValueStr.ifBlank { "1" },
                                    prefix = prefixStr,
                                    postfix = postfixStr,
                                    dataType = selectedDataType
                                )
                            }
                            VariableTypeSelection.FORMULA -> {
                                VariableDefinition(
                                    id = UUID.randomUUID().toString(),
                                    name = trimmedName,
                                    alias = varAlias.trim(),
                                    isComputed = true,
                                    isManual = false,
                                    computationFormula = formulaText.trim(),
                                    prefix = prefixStr,
                                    postfix = postfixStr,
                                    dataType = selectedDataType
                                )
                            }
                        }
                        onAddVariable(constructed)
                    },
                    modifier = Modifier.weight(0.55f).testTag("confirm_add_variable_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Profile Ekle", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
