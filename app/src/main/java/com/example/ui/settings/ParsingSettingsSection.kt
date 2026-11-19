package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.*
import com.example.ui.theme.AppMonoFontFamily
import java.util.UUID

enum class VariableTypeSelection(val label: String) {
    TOKEN("Barkoddan Oku"),
    CONSTANT("Sabit Değer"),
    FORMULA("Hesaplama (fx)")
}

private fun buildSmartSampleBarcode(rules: QrParsingRules): String {
    val scanned = rules.variables.filter { !it.isComputed && !it.isManual }
    if (scanned.isEmpty()) return "A1-100-20"
    val delim = rules.effectiveDelimiter.ifEmpty { "-" }
    return when (rules.parsingMode) {
        QrParsingMode.KEY_VALUE -> {
            val sep = rules.keyValueSeparator.ifEmpty { "=" }
            scanned.joinToString(delim) { v ->
                val key = v.extractionKey.ifBlank { v.name.uppercase() }
                val sample = v.defaultValue.ifBlank {
                    when (v.dataType) {
                        FieldDataType.STRING -> v.name.uppercase()
                        FieldDataType.DATE -> "20261101"
                        FieldDataType.BOOLEAN -> "1"
                        else -> "10"
                    }
                }
                "$key$sep$sample"
            }
        }
        QrParsingMode.FIXED_WIDTH -> {
            "2800123014509"
        }
        QrParsingMode.REGEX_GROUPS, QrParsingMode.DELIMITER -> {
            scanned.sortedBy { it.position }.joinToString(delim) { v ->
                v.defaultValue.ifBlank {
                    when (v.dataType) {
                        FieldDataType.STRING -> v.name.uppercase()
                        FieldDataType.DATE -> "20261101"
                        FieldDataType.BOOLEAN -> "1"
                        else -> ((v.position + 1) * 10).toString()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsingSettingsSection(
    rules: QrParsingRules,
    onRulesChanged: (QrParsingRules) -> Unit,
    formatting: ResultFormatting,
    onFormattingChanged: (ResultFormatting) -> Unit,
    globalVariables: List<GlobalVariable> = emptyList(),
    onGlobalVariablesChanged: ((List<GlobalVariable>) -> Unit)? = null,
    triggerSignal: String = "",
    onTriggerSignalChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val delimiterPresets = listOf("-", ",", ";", "|", "/", "&", " ")

    var showAddVariableSheet by remember { mutableStateOf(false) }
    var sheetInitialType by remember { mutableStateOf(VariableTypeSelection.TOKEN) }
    var showGlobalFormatCard by remember { mutableStateOf(false) }
    var showParsingModeCard by remember { mutableStateOf(false) }

    // Smart real-time simulator text adapting to current profile mode & defaults
    var sampleQrText by remember(rules.parsingMode, rules.effectiveDelimiter, rules.variables.size) {
        mutableStateOf(buildSmartSampleBarcode(rules))
    }

    // Live real-time evaluation including shared @global variables
    val liveParseOutcome = remember(sampleQrText, rules, globalVariables) {
        if (sampleQrText.isNotBlank()) {
            QrParser.parse(sampleQrText, rules, globalVariables)
        } else {
            null
        }
    }
    val liveSuccess = liveParseOutcome as? QrParseOutcome.Success

    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 20.dp + navBottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. Live Barcode & Variable Simulator Card ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)),
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
                                text = "Canlı Barkod Simülatörü",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = rules.parsingMode.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = sampleQrText,
                        onValueChange = { sampleQrText = it },
                        label = { Text("Örnek Barkod / QR Metni") },
                        placeholder = { Text(rules.parsingMode.hint, fontSize = 12.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = AppMonoFontFamily, fontSize = 13.sp),
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

                    // Live Variable Outputs (clearly distinguishes Visible, Hidden, Starred, and Computed!)
                    when (liveParseOutcome) {
                        is QrParseOutcome.Success -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Anlık Değişken Çıktıları:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${rules.variables.count { it.showPill }}/${rules.variables.size} ekranda",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
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
                                    val isTextual = v.dataType == FieldDataType.DATE ||
                                        v.dataType == FieldDataType.STRING ||
                                        v.dataType == FieldDataType.BOOLEAN
                                    val displayVal = when {
                                        isTextual && strVal != null -> "${v.prefix}$strVal${v.postfix}"
                                        numVal != null -> ResultFormatter.formatVariable(numVal, v, formatting)
                                        strVal != null -> "${v.prefix}$strVal${v.postfix}"
                                        else -> "—"
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = when {
                                            v.isPrimaryOutput -> MaterialTheme.colorScheme.primary
                                            v.isComputed -> MaterialTheme.colorScheme.tertiaryContainer
                                            v.isManual -> MaterialTheme.colorScheme.secondaryContainer
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (v.isPrimaryOutput) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier.alpha(if (v.showPill) 1f else 0.45f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (v.isPrimaryOutput) {
                                                Icon(
                                                    Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                            } else if (!v.showPill) {
                                                Icon(
                                                    Icons.Default.VisibilityOff,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                            }
                                            Text(
                                                text = "${v.name}:",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = when {
                                                    v.isPrimaryOutput -> MaterialTheme.colorScheme.onPrimary
                                                    v.isComputed -> MaterialTheme.colorScheme.onTertiaryContainer
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = displayVal,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = AppMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = if (v.isPrimaryOutput) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is QrParseOutcome.Error -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
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
                                text = "Önizleme için yukarıya örnek bir barkod/QR metni yazın.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // --- 2. User-Owned Variables & Calculations Studio (Hero Section!) ---
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                val scannedCount = rules.variables.count { !it.isComputed && !it.isManual }
                val manualCount = rules.variables.count { it.isManual && !it.isComputed }
                val computedCount = rules.variables.count { it.isComputed }
                val visibleCount = rules.variables.count { it.showPill }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Değişkenler & Hesaplamalar",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "$scannedCount barkod · $manualCount sabit · $computedCount fx · $visibleCount görünür",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${rules.variables.size} Alan",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Her değişkeni kendiniz yaratın. Göz simgesiyle kamerada gizleyin/gösterin, yıldızla ana başlık yapın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Add 3-Way Buttons (48dp touch height)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            sheetInitialType = VariableTypeSelection.TOKEN
                            showAddVariableSheet = true
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("add_variable_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Barkod", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }

                    FilledTonalButton(
                        onClick = {
                            sheetInitialType = VariableTypeSelection.CONSTANT
                            showAddVariableSheet = true
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("add_manual_variable_button"),
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
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("add_computed_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Functions, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Formül (fx)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // --- 3. User Variables List ---
        if (rules.variables.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Henüz değişken eklenmedi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Yukarıdaki butonlarla barkod alanları, sabitler veya kendi formüllerinizi oluşturun.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            itemsIndexed(rules.variables, key = { _, v -> v.id }) { index, varDef ->
                VariableItemCard(
                    varDef = varDef,
                    index = index,
                    totalCount = rules.variables.size,
                    parsingMode = rules.parsingMode,
                    allVariables = rules.variables,
                    globalVariables = globalVariables,
                    liveNumericVars = liveSuccess?.numericVariables ?: emptyMap(),
                    liveStringVars = liveSuccess?.stringVariables ?: emptyMap(),
                    globalFormatting = formatting,
                    onRequestAddVariable = {
                        sheetInitialType = VariableTypeSelection.TOKEN
                        showAddVariableSheet = true
                    },
                    onToggleVisibility = {
                        val updatedList = rules.variables.toMutableList()
                        updatedList[index] = varDef.copy(showPill = !varDef.showPill)
                        onRulesChanged(rules.copy(variables = updatedList))
                    },
                    onTogglePrimary = {
                        val willBePrimary = !varDef.isPrimaryOutput
                        val updatedList = rules.variables.mapIndexed { idx, item ->
                            if (idx == index) item.copy(isPrimaryOutput = willBePrimary, showPill = if (willBePrimary) true else item.showPill)
                            else if (willBePrimary) item.copy(isPrimaryOutput = false)
                            else item
                        }
                        onRulesChanged(rules.copy(variables = updatedList))
                    },
                    onMove = { delta ->
                        val target = index + delta
                        if (target in rules.variables.indices) {
                            val mutable = rules.variables.toMutableList()
                            val moved = mutable.removeAt(index)
                            mutable.add(target, moved)
                            onRulesChanged(rules.copy(variables = mutable))
                        }
                    },
                    onUpdate = { updated ->
                        val oldName = varDef.name
                        val newList = rules.variables.toMutableList()
                        newList[index] = updated
                        var nextRules = rules.copy(variables = newList)
                        // Automatically keep dependent formulas intact on rename!
                        if (oldName != updated.name && oldName.isNotBlank() && updated.name.isNotBlank()) {
                            nextRules = QrParser.renameVariableReferences(nextRules, oldName, updated.name)
                        }
                        onRulesChanged(nextRules)
                    },
                    onDelete = {
                        val newList = rules.variables.toMutableList()
                        newList.removeAt(index)
                        onRulesChanged(rules.copy(variables = newList))
                    }
                )
            }
        }

        // --- 4. Shared @Global Constants Manager ---
        if (onGlobalVariablesChanged != null) {
            item {
                GlobalVariablesCard(
                    globalVariables = globalVariables,
                    onGlobalVariablesChanged = onGlobalVariablesChanged
                )
            }
        }

        // --- 5. Barcode Reading Mode (Delimiter / Key=Value / Fixed Width / Regex) & Auto-Trigger ---
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
                            .clickable { showParsingModeCard = !showParsingModeCard },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Barkod Okuma Modu & Tetikleyici",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (rules.parsingMode) {
                                        QrParsingMode.DELIMITER -> "Ayırıcı: '${if (rules.effectiveDelimiter == " ") "Boşluk" else rules.effectiveDelimiter}'"
                                        QrParsingMode.KEY_VALUE -> "Anahtar${rules.keyValueSeparator}Değer · Ayırıcı '${rules.effectiveDelimiter}'"
                                        QrParsingMode.FIXED_WIDTH -> "Sabit Karakter Dilimleme (örn. EAN-13)"
                                        QrParsingMode.REGEX_GROUPS -> "Regex Yakalama Grupları"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showParsingModeCard = !showParsingModeCard }) {
                            Icon(
                                imageVector = if (showParsingModeCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showParsingModeCard) "Daralt" else "Genişlet"
                            )
                        }
                    }

                    AnimatedVisibility(visible = showParsingModeCard) {
                        Column(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            Text("Ayrıştırma Yöntemi", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                QrParsingMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = rules.parsingMode == mode,
                                        onClick = { onRulesChanged(rules.copy(parsingMode = mode)) },
                                        label = { Text(mode.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                        modifier = Modifier.testTag("parsing_mode_${mode.name}")
                                    )
                                }
                            }
                            Text(
                                text = rules.parsingMode.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            when (rules.parsingMode) {
                                QrParsingMode.DELIMITER, QrParsingMode.KEY_VALUE -> {
                                    Text("Parça Ayırıcı Karakter", style = MaterialTheme.typography.labelMedium)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = rules.customDelimiter,
                                            onValueChange = { onRulesChanged(rules.copy(customDelimiter = it)) },
                                            label = { Text("Özel Ayırıcı") },
                                            placeholder = { Text("##, ::") },
                                            modifier = Modifier.weight(1f).testTag("custom_delim_input"),
                                            singleLine = true
                                        )
                                        if (rules.parsingMode == QrParsingMode.KEY_VALUE) {
                                            OutlinedTextField(
                                                value = rules.keyValueSeparator,
                                                onValueChange = { onRulesChanged(rules.copy(keyValueSeparator = it.take(2))) },
                                                label = { Text("Anahtar Ayırıcı") },
                                                placeholder = { Text("= veya :") },
                                                modifier = Modifier.weight(1f).testTag("kv_sep_input"),
                                                singleLine = true
                                            )
                                        }
                                    }
                                }

                                QrParsingMode.FIXED_WIDTH -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Sabit Dilim modunda her 'Barkoddan Oku' değişkeninin içinde Başlangıç ve Uzunluk (karakter sayısı) belirlenir. Örn: EAN-13 tartı barkodu için Başlangıç=7, Uzunluk=5.",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                QrParsingMode.REGEX_GROUPS -> {
                                    OutlinedTextField(
                                        value = rules.regexPattern,
                                        onValueChange = { onRulesChanged(rules.copy(regexPattern = it)) },
                                        label = { Text("Regex Deseni (Gruplar: 0, 1, 2...)") },
                                        placeholder = { Text("örn. ^([A-Z]+)-(\\d+\\.\\d+)/(\\d+)$") },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = AppMonoFontFamily, fontSize = 12.sp),
                                        modifier = Modifier.fillMaxWidth().testTag("regex_pattern_input"),
                                        singleLine = true
                                    )
                                }
                            }

                            if (onTriggerSignalChanged != null) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Otomatik Profil Tetikleyici", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedTextField(
                                    value = triggerSignal,
                                    onValueChange = onTriggerSignalChanged,
                                    label = { Text("Sinyal Etiketi / Ön Ek") },
                                    placeholder = { Text("örn. PLAKA=, ISBN-, SKU- veya [P:depo]") },
                                    modifier = Modifier.fillMaxWidth().testTag("trigger_signal_input"),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 6. Global Separators & Rounding Defaults ---
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
                                    text = "Sayı Biçimi & Yuvarlama",
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

                            Text("Binlik Ayırıcı", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

                            Text("Ondalık Ayırıcı", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

                            Text("Varsayılan Ondalık Basamak (${formatting.decimalPrecision})", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

                            Text("Yuvarlama Modu", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                        }
                    }
                }
            }
        }
    }

    // Sliding Bottom Sheet for configuring and adding a new Variable (with LIVE formula preview!)
    if (showAddVariableSheet) {
        AddVariableBottomSheet(
            initialType = sheetInitialType,
            parsingMode = rules.parsingMode,
            existingVariables = rules.variables,
            globalVariables = globalVariables,
            liveNumericVars = liveSuccess?.numericVariables ?: emptyMap(),
            liveStringVars = liveSuccess?.stringVariables ?: emptyMap(),
            formatting = formatting,
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
    totalCount: Int = 1,
    parsingMode: QrParsingMode = QrParsingMode.DELIMITER,
    allVariables: List<VariableDefinition> = emptyList(),
    globalVariables: List<GlobalVariable> = emptyList(),
    liveNumericVars: Map<String, Double> = emptyMap(),
    liveStringVars: Map<String, String> = emptyMap(),
    globalFormatting: ResultFormatting? = null,
    onRequestAddVariable: (() -> Unit)? = null,
    onToggleVisibility: () -> Unit = {},
    onTogglePrimary: () -> Unit = {},
    onMove: (Int) -> Unit = {},
    onUpdate: (VariableDefinition) -> Unit,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .alpha(if (varDef.showPill || isExpanded) 1f else 0.7f),
        colors = CardDefaults.cardColors(
            containerColor = if (varDef.isPrimaryOutput) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (varDef.isPrimaryOutput) 1.5.dp else 1.dp,
            color = if (varDef.isPrimaryOutput) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Type Badge, Name, Summary, Quick Star / Eye / Expand / Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { isExpanded = !isExpanded }
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
                        varDef.isComputed -> "fx"
                        varDef.isManual -> "Sabit"
                        parsingMode == QrParsingMode.KEY_VALUE -> varDef.extractionKey.ifBlank { varDef.name }
                        parsingMode == QrParsingMode.FIXED_WIDTH -> "${varDef.sliceStart}..${varDef.sliceStart + varDef.sliceLength}"
                        else -> "#${varDef.position}"
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeTextColor,
                            fontFamily = AppMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = varDef.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (!varDef.showPill) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "Gizli",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        val summaryDetail = when {
                            varDef.isComputed -> if (varDef.computationFormula.isNotBlank()) "= ${varDef.computationFormula}" else "Formül yazın..."
                            varDef.isManual -> "Değer: ${varDef.defaultValue}"
                            parsingMode == QrParsingMode.KEY_VALUE -> "Anahtar: ${varDef.extractionKey.ifBlank { varDef.name }}"
                            parsingMode == QrParsingMode.FIXED_WIDTH -> "Dilim: ${varDef.sliceStart} (uzunluk ${if (varDef.sliceLength > 0) varDef.sliceLength else "sona"})"
                            else -> "${varDef.alias.ifBlank { varDef.dataType.label }} · Sıra ${varDef.position}"
                        }
                        Text(
                            text = summaryDetail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (varDef.isComputed) AppMonoFontFamily else null,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                // 1-Tap Controls: Pin Primary (Star), Show/Hide (Eye), Expand, Delete
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePrimary,
                        modifier = Modifier.size(36.dp).testTag("var_star_${varDef.name}")
                    ) {
                        Icon(
                            imageVector = if (varDef.isPrimaryOutput) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (varDef.isPrimaryOutput) "Ana Başlıktan Kaldır" else "Ana Başlık Yap",
                            tint = if (varDef.isPrimaryOutput) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.size(36.dp).testTag("var_visibility_${varDef.name}")
                    ) {
                        Icon(
                            imageVector = if (varDef.showPill) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (varDef.showPill) "Kamerada Gizle" else "Kamerada Göster",
                            tint = if (varDef.showPill) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Daralt" else "Genişlet",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expanded Controls
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Reorder & Delete Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { onMove(-1) },
                                enabled = index > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Yukarı Taşı", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Yukarı", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { onMove(1) },
                                enabled = index < totalCount - 1,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Aşağı Taşı", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Aşağı", fontSize = 11.sp)
                            }
                        }

                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sil", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Source Type Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !varDef.isComputed && !varDef.isManual,
                            onClick = { onUpdate(varDef.copy(isComputed = false, isManual = false)) },
                            label = { Text("Barkoddan Oku", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isManual,
                            onClick = { onUpdate(varDef.copy(isComputed = false, isManual = true)) },
                            label = { Text("Sabit Değer", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isComputed,
                            onClick = { onUpdate(varDef.copy(isComputed = true, isManual = false)) },
                            label = { Text("Hesaplama (fx)", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Variable Name (Safe Auto-Rename) & Label
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varDef.name,
                            onValueChange = { next ->
                                val clean = next.filter { c -> c.isLetterOrDigit() || c == '_' }
                                onUpdate(varDef.copy(name = clean))
                            },
                            label = { Text("Değişken Adı") },
                            supportingText = { Text("Yeniden adlandırınca formüller güncellenir", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.alias,
                            onValueChange = { onUpdate(varDef.copy(alias = it)) },
                            label = { Text("Görünen Etiket") },
                            supportingText = { Text("Kamera kartı başlığı", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Data Type Selector
                    Text(
                        text = "Veri Tipi:",
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
                                globalVariables = globalVariables,
                                liveNumericVars = liveNumericVars,
                                liveStringVars = liveStringVars,
                                formatting = globalFormatting,
                                previewVariable = varDef,
                                label = "Formül (${varDef.name})",
                                placeholder = "örn: if(adet < min_stok, \"KRİTİK\", \"OK\") veya lt * @motorin_lt",
                                testTagPrefix = "formula_input_${varDef.name}",
                                onRequestAddVariable = onRequestAddVariable
                            )
                        }

                        varDef.isManual -> {
                            OutlinedTextField(
                                value = varDef.defaultValue,
                                onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                label = { Text("Sabit Değer") },
                                placeholder = { Text("örn. 14, 5, 1.20 veya 20261101") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        else -> {
                            when (parsingMode) {
                                QrParsingMode.KEY_VALUE -> {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = varDef.extractionKey,
                                            onValueChange = { onUpdate(varDef.copy(extractionKey = it)) },
                                            label = { Text("Anahtar (Key)", maxLines = 1, softWrap = false) },
                                            placeholder = { Text(varDef.name.uppercase()) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = varDef.defaultValue,
                                            onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                            label = { Text("Varsayılan", maxLines = 1, softWrap = false) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                    }
                                }
                                QrParsingMode.FIXED_WIDTH -> {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = varDef.sliceStart.toString(),
                                            onValueChange = { s ->
                                                onUpdate(varDef.copy(sliceStart = s.filter { it.isDigit() }.toIntOrNull() ?: 0))
                                            },
                                            label = { Text("Başlangıç (0..)", maxLines = 1, softWrap = false) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = varDef.sliceLength.toString(),
                                            onValueChange = { s ->
                                                onUpdate(varDef.copy(sliceLength = s.filter { it.isDigit() }.toIntOrNull() ?: 0))
                                            },
                                            label = { Text("Uzunluk (0=son)", maxLines = 1, softWrap = false) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                    }
                                }
                                QrParsingMode.DELIMITER, QrParsingMode.REGEX_GROUPS -> {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = varDef.position.toString(),
                                            onValueChange = { str ->
                                                val pos = str.filter { it.isDigit() }.toIntOrNull() ?: 0
                                                onUpdate(varDef.copy(position = pos))
                                            },
                                            label = { Text(if (parsingMode == QrParsingMode.REGEX_GROUPS) "Grup Sırası (0..)" else "Parça Sırası (0..)", maxLines = 1, softWrap = false) },
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
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Prefix and Postfix
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varDef.prefix,
                            onValueChange = { onUpdate(varDef.copy(prefix = it)) },
                            label = { Text("Ön Ek (₺, Raf )", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.postfix,
                            onValueChange = { onUpdate(varDef.copy(postfix = it)) },
                            label = { Text("Son Ek (Lt, ad, gün)", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    if (varDef.dataType != FieldDataType.STRING && varDef.dataType != FieldDataType.DATE && varDef.dataType != FieldDataType.BOOLEAN) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Ondalık Basamak:", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(null to "Oto", 0 to "0 (Tam)", 1 to "1", 2 to "2", 3 to "3", 4 to "4").forEach { (dec, lbl) ->
                                FilterChip(
                                    selected = varDef.decimalPlaces == dec,
                                    onClick = { onUpdate(varDef.copy(decimalPlaces = dec)) },
                                    label = { Text(lbl, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sliding Bottom Sheet for configuring and adding a new Scanned, Constant, or Formula variable —
 * now with LIVE preview directly inside the creation flow!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVariableBottomSheet(
    initialType: VariableTypeSelection,
    parsingMode: QrParsingMode = QrParsingMode.DELIMITER,
    existingVariables: List<VariableDefinition>,
    globalVariables: List<GlobalVariable> = emptyList(),
    liveNumericVars: Map<String, Double> = emptyMap(),
    liveStringVars: Map<String, String> = emptyMap(),
    formatting: ResultFormatting? = null,
    delimiter: String,
    onDismiss: () -> Unit,
    onAddVariable: (VariableDefinition) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }

    val nextTokenPos = remember(existingVariables) {
        existingVariables.count { !it.isComputed && !it.isManual }
    }
    val defaultTokenName = remember(existingVariables) {
        val count = existingVariables.count { !it.isComputed && !it.isManual }
        "alan${count + 1}"
    }
    val defaultConstantName = remember(existingVariables) {
        val count = existingVariables.count { it.isManual } + 1
        "sabit$count"
    }
    val defaultFormulaName = remember(existingVariables) {
        val count = existingVariables.count { it.isComputed } + 1
        "hesap$count"
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
    var extractionKeyStr by remember { mutableStateOf("") }
    var sliceStartStr by remember { mutableStateOf("0") }
    var sliceLengthStr by remember { mutableStateOf("4") }
    var defaultValueStr by remember { mutableStateOf("") }
    var constantValueStr by remember { mutableStateOf("1") }
    var formulaText by remember { mutableStateOf("") }
    var prefixStr by remember { mutableStateOf("") }
    var postfixStr by remember { mutableStateOf("") }
    var showOnCamera by remember { mutableStateOf(true) }
    var pinAsPrimary by remember { mutableStateOf(selectedType == VariableTypeSelection.FORMULA) }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Yeni Değişken / Hesaplama",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Barkod alanı, sabit değer veya kendi formülünüzü ekleyin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat")
                }
            }

            // 3-Way Source Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VariableTypeSelection.entries.forEach { type ->
                    val isSelected = selectedType == type
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedType = type }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp, vertical = 10.dp),
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

            // Name & Label
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = varName,
                    onValueChange = { varName = it.filter { c -> c.isLetterOrDigit() || c == '_' }.lowercase() },
                    label = { Text("Değişken Adı *") },
                    placeholder = { Text("raf, plaka, tutar, durum") },
                    modifier = Modifier.weight(1f).testTag("new_variable_name_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = varAlias,
                    onValueChange = { varAlias = it },
                    label = { Text("Görünen Etiket") },
                    placeholder = { Text("Raf Konumu") },
                    modifier = Modifier.weight(1f).testTag("new_variable_alias_input"),
                    singleLine = true
                )
            }

            // Data Type Selector
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Veri Tipi",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FieldDataType.entries.forEach { dt ->
                        FilterChip(
                            selected = selectedDataType == dt,
                            onClick = { selectedDataType = dt },
                            label = { Text(dt.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("new_variable_datatype_${dt.name}")
                        )
                    }
                }
            }

            // Mode-specific inputs
            when (selectedType) {
                VariableTypeSelection.TOKEN -> {
                    when (parsingMode) {
                        QrParsingMode.KEY_VALUE -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = extractionKeyStr,
                                    onValueChange = { extractionKeyStr = it },
                                    label = { Text("Anahtar (Key)") },
                                    placeholder = { Text(varName.uppercase()) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = defaultValueStr,
                                    onValueChange = { defaultValueStr = it },
                                    label = { Text("Varsayılan") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                        QrParsingMode.FIXED_WIDTH -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = sliceStartStr,
                                    onValueChange = { sliceStartStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Başlangıç İndeksi") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = sliceLengthStr,
                                    onValueChange = { sliceLengthStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Karakter Uzunluğu") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                        QrParsingMode.DELIMITER, QrParsingMode.REGEX_GROUPS -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = tokenPositionStr,
                                    onValueChange = { tokenPositionStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Parça Sırası (0..N)") },
                                    modifier = Modifier.weight(1f).testTag("new_variable_position_input"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = defaultValueStr,
                                    onValueChange = { defaultValueStr = it },
                                    label = { Text("Varsayılan") },
                                    placeholder = { Text("Boşsa zorunlu") },
                                    modifier = Modifier.weight(1f).testTag("new_variable_default_input"),
                                    singleLine = true
                                )
                            }
                            Text(
                                text = "'$delimiter' ile ayrılan barkodun ${tokenPositionStr.ifBlank { "0" }}. parçası '$varName' olur.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                VariableTypeSelection.CONSTANT -> {
                    OutlinedTextField(
                        value = constantValueStr,
                        onValueChange = { constantValueStr = it },
                        label = { Text("Sabit Değer *") },
                        placeholder = { Text("örn. 14, 20, 44.50, 20261101") },
                        modifier = Modifier.fillMaxWidth().testTag("new_constant_value_input"),
                        singleLine = true
                    )
                }

                VariableTypeSelection.FORMULA -> {
                    val previewDef = VariableDefinition(
                        name = varName.ifBlank { "hesap" },
                        dataType = selectedDataType,
                        isComputed = true,
                        prefix = prefixStr,
                        postfix = postfixStr
                    )
                    FormulaBuilderPanel(
                        expression = formulaText,
                        onExpressionChanged = { formulaText = it },
                        variables = existingVariables,
                        globalVariables = globalVariables,
                        liveNumericVars = liveNumericVars,
                        liveStringVars = liveStringVars,
                        formatting = formatting,
                        previewVariable = previewDef,
                        label = "Hesaplama Formülü *",
                        placeholder = "örn: if(adet < 5, \"KRİTİK\", \"OK\") veya lt * @motorin_lt",
                        testTagPrefix = "new_formula_expression"
                    )
                }
            }

            // Prefix / Postfix
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prefixStr,
                    onValueChange = { prefixStr = it },
                    label = { Text("Ön Ek (₺, Raf )") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = postfixStr,
                    onValueChange = { postfixStr = it },
                    label = { Text("Son Ek (Lt, ad, gün)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            // Visibility & Primary Pin Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = showOnCamera,
                    onClick = { showOnCamera = !showOnCamera },
                    leadingIcon = {
                        Icon(
                            if (showOnCamera) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(if (showOnCamera) "Kamerada Göster" else "Kamerada Gizle", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = pinAsPrimary,
                    onClick = { pinAsPrimary = !pinAsPrimary },
                    leadingIcon = {
                        Icon(
                            if (pinAsPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Ana Başlık Yap", fontSize = 11.sp) }
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.4f)
                ) {
                    Text("İptal")
                }

                Button(
                    onClick = {
                        val trimmedName = varName.trim().ifBlank { defaultTokenName }
                        val constructed = when (selectedType) {
                            VariableTypeSelection.TOKEN -> VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                position = tokenPositionStr.toIntOrNull() ?: nextTokenPos,
                                name = trimmedName,
                                alias = varAlias.trim(),
                                isComputed = false,
                                isManual = false,
                                showPill = showOnCamera,
                                isPrimaryOutput = pinAsPrimary,
                                extractionKey = extractionKeyStr.trim(),
                                sliceStart = sliceStartStr.toIntOrNull() ?: 0,
                                sliceLength = sliceLengthStr.toIntOrNull() ?: 0,
                                defaultValue = defaultValueStr.ifBlank { if (selectedDataType == FieldDataType.DATE) "20261101" else "0" },
                                prefix = prefixStr,
                                postfix = postfixStr,
                                dataType = selectedDataType
                            )
                            VariableTypeSelection.CONSTANT -> VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                position = -1,
                                name = trimmedName,
                                alias = varAlias.trim(),
                                isComputed = false,
                                isManual = true,
                                showPill = showOnCamera,
                                isPrimaryOutput = pinAsPrimary,
                                defaultValue = constantValueStr.ifBlank { "1" },
                                prefix = prefixStr,
                                postfix = postfixStr,
                                dataType = selectedDataType
                            )
                            VariableTypeSelection.FORMULA -> VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                name = trimmedName,
                                alias = varAlias.trim(),
                                isComputed = true,
                                isManual = false,
                                showPill = showOnCamera,
                                isPrimaryOutput = pinAsPrimary,
                                computationFormula = formulaText.trim(),
                                prefix = prefixStr,
                                postfix = postfixStr,
                                dataType = selectedDataType
                            )
                        }
                        onAddVariable(constructed)
                    },
                    modifier = Modifier.weight(0.6f).testTag("confirm_add_variable_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Profile Ekle", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
