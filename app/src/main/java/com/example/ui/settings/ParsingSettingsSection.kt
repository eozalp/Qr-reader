package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.engine.ResultFormatter
import com.example.model.AppRoundingMode
import com.example.model.FieldDataType
import com.example.model.QrParsingRules
import com.example.model.ResultFormatting
import com.example.model.VariableDefinition
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsingSettingsSection(
    rules: QrParsingRules,
    onRulesChanged: (QrParsingRules) -> Unit,
    formatting: ResultFormatting,
    onFormattingChanged: (ResultFormatting) -> Unit,
    triggerSignal: String = "",
    onTriggerSignalChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val delimiterPresets = listOf("-", ",", ";", "|", "/", " ")

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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
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
                                text = "Real-Time QR Simulator",
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
                                text = "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Type any sample QR code text to test parsing, formulas, and formatting immediately:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = sampleQrText,
                        onValueChange = { sampleQrText = it },
                        label = { Text("Sample QR Code Text") },
                        placeholder = { Text("e.g. 150-25-3 or 49.95,2") },
                        modifier = Modifier.fillMaxWidth().testTag("sample_qr_input"),
                        singleLine = true,
                        trailingIcon = {
                            if (sampleQrText.isNotEmpty()) {
                                IconButton(onClick = { sampleQrText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Live Pill Outputs
                    when (liveParseOutcome) {
                        is QrParseOutcome.Success -> {
                            Text(
                                text = "Extracted & Computed Values:",
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
                                text = "Enter QR sample text above to preview.",
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
                        Text("Presets:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
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

        // --- 2. QR Token Delimiter & Auto-Switch Signal ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "QR Token Delimiter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Character used to split barcode tokens (e.g. '-' in '150-25'):",
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
                                label = { Text(if (delim == " ") "Space" else "'$delim'", maxLines = 1, softWrap = false) },
                                modifier = Modifier.testTag("delim_chip_$delim")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = rules.customDelimiter,
                        onValueChange = { onRulesChanged(rules.copy(customDelimiter = it)) },
                        label = { Text("Custom Delimiter (Optional)") },
                        placeholder = { Text("e.g. ##, ::, or /") },
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
                            Text("Auto-Switch Signal Tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Auto-activate this profile when QR starts with this tag (e.g. TESBIH- or [P:amber])",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = triggerSignal,
                            onValueChange = onTriggerSignalChanged,
                            label = { Text("Signal Tag / Prefix") },
                            placeholder = { Text("e.g. TESBIH-, GOLD-, or [P:amber]") },
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
                                    text = "Global Separators & Rounding",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Thousands '${formatting.thousandsSeparator}', Decimal '${formatting.decimalSeparator}', ${formatting.decimalPrecision} decimals",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = { showGlobalFormatCard = !showGlobalFormatCard }) {
                            Icon(
                                imageVector = if (showGlobalFormatCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showGlobalFormatCard) "Collapse" else "Expand"
                            )
                        }
                    }

                    AnimatedVisibility(visible = showGlobalFormatCard) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Thousands Separator
                            Text("Thousands Separator", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("none" to "None", "," to "Comma (,)", "." to "Dot (.)", " " to "Space ( )", "'" to "Apostrophe (')").forEach { (code, label) ->
                                    FilterChip(
                                        selected = formatting.thousandsSeparator == code,
                                        onClick = { onFormattingChanged(formatting.copy(thousandsSeparator = code)) },
                                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Decimal Separator
                            Text("Decimal Separator", style = MaterialTheme.typography.labelMedium)
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
                                    label = { Text("Dot (.)", maxLines = 1, softWrap = false) }
                                )
                                FilterChip(
                                    selected = formatting.decimalSeparator == ",",
                                    onClick = { onFormattingChanged(formatting.copy(decimalSeparator = ",")) },
                                    label = { Text("Comma (,)", maxLines = 1, softWrap = false) }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Default Decimal Precision
                            Text("Default Decimal Places (${formatting.decimalPrecision})", style = MaterialTheme.typography.labelMedium)
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
                            Text("Rounding Mode", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AppRoundingMode.entries.forEach { mode ->
                                    val cleanLabel = mode.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                                    FilterChip(
                                        selected = formatting.roundingMode == mode,
                                        onClick = { onFormattingChanged(formatting.copy(roundingMode = mode)) },
                                        label = { Text(cleanLabel, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Negative Number Format
                            Text("Negative Number Format", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("-x" to "-100", "(x)" to "(100)", "-prefix" to "-$100").forEach { (fmt, example) ->
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
                            text = "Variables & Formulas",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$scannedCount barcode tokens · $manualCount constants · $computedCount formulas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${rules.variables.size} Total",
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
                            val tokenCount = rules.variables.count { !it.isComputed && !it.isManual }
                            val nextName = if (tokenCount < 26) ('a'.code + tokenCount).toChar().toString() else "var$tokenCount"
                            val newVar = VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                position = tokenCount,
                                name = nextName,
                                isComputed = false,
                                isManual = false,
                                dataType = FieldDataType.DOUBLE
                            )
                            onRulesChanged(rules.copy(variables = rules.variables + newVar))
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
                            val manualIndex = rules.variables.count { it.isManual } + 1
                            val newVar = VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                position = -1,
                                name = "m$manualIndex",
                                isComputed = false,
                                isManual = true,
                                defaultValue = "1",
                                dataType = FieldDataType.DOUBLE
                            )
                            onRulesChanged(rules.copy(variables = rules.variables + newVar))
                        },
                        modifier = Modifier.weight(1f).testTag("add_manual_variable_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Constant", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }

                    FilledTonalButton(
                        onClick = {
                            val compCount = rules.variables.count { it.isComputed } + 1
                            val newVar = VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                name = "calc$compCount",
                                isComputed = true,
                                isManual = false,
                                computationFormula = "",
                                dataType = FieldDataType.DOUBLE
                            )
                            onRulesChanged(rules.copy(variables = rules.variables + newVar))
                        },
                        modifier = Modifier.weight(1f).testTag("add_computed_button"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Formula", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // --- 5. Variable Cards List ---
        itemsIndexed(rules.variables) { index, varDef ->
            VariableItemCard(
                varDef = varDef,
                index = index,
                allVariables = rules.variables,
                globalFormatting = formatting,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariableItemCard(
    varDef: VariableDefinition,
    index: Int,
    allVariables: List<VariableDefinition> = emptyList(),
    globalFormatting: ResultFormatting? = null,
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
                        varDef.isComputed -> "fx Formula"
                        varDef.isManual -> "📌 Constant"
                        else -> "Pos ${varDef.position}"
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
                            varDef.isComputed -> if (varDef.computationFormula.isNotBlank()) "= ${varDef.computationFormula}" else "No formula set"
                            varDef.isManual -> "Constant: ${varDef.defaultValue}"
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
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Variable",
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
                            label = { Text("Barcode Token", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isManual,
                            onClick = { onUpdate(varDef.copy(isComputed = false, isManual = true)) },
                            label = { Text("Manual Constant", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                        FilterChip(
                            selected = varDef.isComputed,
                            onClick = { onUpdate(varDef.copy(isComputed = true, isManual = false)) },
                            label = { Text("Computed Formula", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Variable Name & Alias
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = varDef.name,
                            onValueChange = { onUpdate(varDef.copy(name = it.filter { c -> c.isLetterOrDigit() || c == '_' })) },
                            label = { Text("Name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.alias,
                            onValueChange = { onUpdate(varDef.copy(alias = it.filter { c -> c.isLetterOrDigit() || c == '_' })) },
                            label = { Text("Alias") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Specific Type Controls
                    when {
                        varDef.isComputed -> {
                            OutlinedTextField(
                                value = varDef.computationFormula,
                                onValueChange = { onUpdate(varDef.copy(computationFormula = it)) },
                                label = { Text("Formula (e.g. price / grams or a * b)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            // Quick operators & insert chips
                            val otherVariables = allVariables.filter { it.name != varDef.name }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Insert:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                otherVariables.forEach { other ->
                                    AssistChip(
                                        onClick = {
                                            val current = varDef.computationFormula
                                            val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("+") && !current.endsWith("-") && !current.endsWith("*") && !current.endsWith("/")) " " else ""
                                            onUpdate(varDef.copy(computationFormula = "$current$sep${other.name}"))
                                        },
                                        label = { Text("+ ${other.name}", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                                listOf("+", "-", "*", "/", "(", ")").forEach { op ->
                                    AssistChip(
                                        onClick = {
                                            val current = varDef.computationFormula
                                            onUpdate(varDef.copy(computationFormula = "$current $op "))
                                        },
                                        label = { Text(op, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }

                        varDef.isManual -> {
                            OutlinedTextField(
                                value = varDef.defaultValue,
                                onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                label = { Text("Constant Value") },
                                placeholder = { Text("e.g. 18, 5.5, or 0.20") },
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
                                    label = { Text("Token Position") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = varDef.defaultValue,
                                    onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                                    label = { Text("Default Fallback") },
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
                            label = { Text("Prefix (e.g. $, ₺)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = varDef.postfix,
                            onValueChange = { onUpdate(varDef.copy(postfix = it)) },
                            label = { Text("Postfix (e.g. /g, pcs)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Number Formatting & Decimals
                    if (varDef.dataType != FieldDataType.STRING) {
                        Text("Decimal Places:", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val decOptions = listOf(
                                null to "Auto",
                                0 to "0 (Int)",
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
                                Text("Percentage (%)", style = MaterialTheme.typography.bodySmall)
                            }

                            val previewSample = ResultFormatter.formatVariable(1234.5678, varDef, globalFormatting, true)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "Preview: $previewSample",
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
                        Text("Show Pill in AR Overlay", style = MaterialTheme.typography.bodyMedium)
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
