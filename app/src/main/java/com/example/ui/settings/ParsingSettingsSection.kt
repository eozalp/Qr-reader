package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FieldDataType
import com.example.model.QrParsingRules
import com.example.model.VariableDefinition
import java.util.UUID

@Composable
fun ParsingSettingsSection(
    rules: QrParsingRules,
    onRulesChanged: (QrParsingRules) -> Unit,
    modifier: Modifier = Modifier
) {
    val delimiterPresets = listOf("-", ",", ";", "|", "/", " ")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "QR Token Delimiter",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
                        label = { Text(if (delim == " ") "Space" else "'$delim'") },
                        modifier = Modifier.testTag("delim_chip_$delim")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = rules.customDelimiter,
                onValueChange = { onRulesChanged(rules.copy(customDelimiter = it)) },
                label = { Text("Custom Delimiter") },
                placeholder = { Text("e.g. ## or ::") },
                modifier = Modifier.fillMaxWidth().testTag("custom_delim_input"),
                singleLine = true
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Parsing Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Trim Whitespace")
                        Switch(
                            checked = rules.trimWhitespace,
                            onCheckedChange = { onRulesChanged(rules.copy(trimWhitespace = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Allow Negative Numbers")
                        Switch(
                            checked = rules.allowNegative,
                            onCheckedChange = { onRulesChanged(rules.copy(allowNegative = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Decimal Separator in QR", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = rules.decimalSeparator == ".",
                            onClick = { onRulesChanged(rules.copy(decimalSeparator = ".")) },
                            label = { Text("Dot (.)") }
                        )
                        FilterChip(
                            selected = rules.decimalSeparator == ",",
                            onClick = { onRulesChanged(rules.copy(decimalSeparator = ",")) },
                            label = { Text("Comma (,)") }
                        )
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Variable Definitions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${rules.variables.size} defined (${rules.variables.count { !it.isComputed }} scanned, ${rules.variables.count { it.isComputed }} computed)",
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val tokenCount = rules.variables.count { !it.isComputed }
                            val nextName = if (tokenCount < 26) ('a'.code + tokenCount).toChar().toString() else "var$tokenCount"
                            val newVar = VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                position = tokenCount,
                                name = nextName,
                                isComputed = false,
                                dataType = FieldDataType.DOUBLE
                            )
                            onRulesChanged(rules.copy(variables = rules.variables + newVar))
                        },
                        modifier = Modifier.testTag("add_variable_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Token")
                    }

                    FilledTonalButton(
                        onClick = {
                            val computedCount = rules.variables.count { it.isComputed } + 1
                            val newVar = VariableDefinition(
                                id = UUID.randomUUID().toString(),
                                name = "calc$computedCount",
                                isComputed = true,
                                computationFormula = "",
                                dataType = FieldDataType.DOUBLE
                            )
                            onRulesChanged(rules.copy(variables = rules.variables + newVar))
                        },
                        modifier = Modifier.testTag("add_computed_button")
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Computed")
                    }
                }
            }
        }

        itemsIndexed(rules.variables) { index, varDef ->
            VariableItemCard(
                varDef = varDef,
                index = index,
                allVariables = rules.variables,
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
    onUpdate: (VariableDefinition) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Type badge & Name & Delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (varDef.isComputed) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (varDef.isComputed) "fx Computed" else "Pos ${varDef.position}",
                            color = if (varDef.isComputed) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Variable: ${varDef.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Variable",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Source Type Selector: Token vs Computed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !varDef.isComputed,
                    onClick = { onUpdate(varDef.copy(isComputed = false)) },
                    label = { Text("Scanned Token") }
                )
                FilterChip(
                    selected = varDef.isComputed,
                    onClick = { onUpdate(varDef.copy(isComputed = true)) },
                    label = { Text("Computed Variable (Formula)") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Name and Alias
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
                    label = { Text("Alias (Optional)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Variable Prefix and Postfix
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
                    label = { Text("Postfix (e.g. g, /g, TL)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // If Computed: show formula editor and variable picker chips
            if (varDef.isComputed) {
                OutlinedTextField(
                    value = varDef.computationFormula,
                    onValueChange = { onUpdate(varDef.copy(computationFormula = it)) },
                    label = { Text("Computation Formula (e.g. price / grams)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                val otherVariables = allVariables.filter { it.name != varDef.name }
                if (otherVariables.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Insert variable into formula:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        otherVariables.forEach { other ->
                            AssistChip(
                                onClick = {
                                    val current = varDef.computationFormula
                                    val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("+") && !current.endsWith("-") && !current.endsWith("*") && !current.endsWith("/")) " " else ""
                                    onUpdate(varDef.copy(computationFormula = "$current$sep${other.name}"))
                                },
                                label = { Text("+ ${other.name}") }
                            )
                        }
                    }
                }
            } else {
                // If Token-based: show default value, optional, and override settings
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = varDef.defaultValue,
                        onValueChange = { onUpdate(varDef.copy(defaultValue = it)) },
                        label = { Text("Default Value") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = varDef.isOptional,
                            onCheckedChange = { onUpdate(varDef.copy(isOptional = it)) }
                        )
                        Text("Optional", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Override Barcode Value",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Always use default value instead of scanned QR token",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = varDef.overrideBarcode,
                        onCheckedChange = { onUpdate(varDef.copy(overrideBarcode = it)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Show Pill in Overlay Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Show Pill in Overlay",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Display an individual pill badge for this variable on the AR overlay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = varDef.showPill,
                    onCheckedChange = { onUpdate(varDef.copy(showPill = it)) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Data Type", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FieldDataType.entries.forEach { type ->
                    FilterChip(
                        selected = varDef.dataType == type,
                        onClick = { onUpdate(varDef.copy(dataType = type)) },
                        label = { Text(type.name, fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}
