package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.ExcelTableOverlayContent
import java.util.UUID

@Composable
fun TableSettingsSection(
    profile: QrProfile,
    onProfileChanged: (QrProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val overlay = profile.overlay
    val tableConfig = overlay.tableConfig
    var showAddDialog by remember { mutableStateOf(false) }

    // Sample mock result for real-time live preview
    val sampleResult = remember(profile.parsingRules, profile.formula) {
        val mockVars = mutableMapOf<String, Double>()
        profile.parsingRules.variables.forEachIndexed { idx, v ->
            val vName = v.name
            val baseVal = (idx + 1) * 10.0 + 5.0
            mockVars[vName] = v.defaultValue.toDoubleOrNull() ?: baseVal
        }
        val pillDisplay = mutableMapOf<String, String>()
        profile.parsingRules.variables.forEach { v ->
            val num = mockVars[v.name] ?: 0.0
            val clean = if (num % 1.0 == 0.0) num.toLong().toString() else "%.2f".format(num)
            pillDisplay[v.name] = "${v.prefix}$clean${v.postfix}"
        }
        ParsedQrResult(
            rawText = profile.parsingRules.variables.joinToString(profile.parsingRules.effectiveDelimiter) { v ->
                mockVars[v.name]?.toInt()?.toString() ?: "10"
            },
            extractedVariables = mockVars,
            status = CalculationStatus.Success(100.0, "${profile.formatting.prefix}100.00${profile.formatting.postfix}"),
            formulaExpression = profile.formula.expression,
            variablePillDisplay = pillDisplay
        )
    }

    // Helper to update tableConfig
    fun updateTable(newTable: ExcelTableConfig) {
        onProfileChanged(
            profile.copy(
                overlay = overlay.copy(tableConfig = newTable)
            )
        )
    }

    // Auto-populate table items from variables and formula
    fun autoPopulateItems(): List<ExcelTableCellItem> {
        val list = mutableListOf<ExcelTableCellItem>()
        var coordNum = 1
        // Token variables
        profile.parsingRules.variables.filter { !it.isComputed }.forEach { v ->
            list.add(
                ExcelTableCellItem(
                    id = UUID.randomUUID().toString(),
                    source = TableItemSource.VARIABLE,
                    variableName = v.name,
                    label = if (v.alias.isNotBlank()) v.alias else v.name.replaceFirstChar { it.uppercase() },
                    prefix = v.prefix,
                    postfix = v.postfix,
                    coordinate = "A$coordNum",
                    isVisible = true,
                    isEmphasized = false
                )
            )
            coordNum++
        }
        // Computed variables
        profile.parsingRules.variables.filter { it.isComputed }.forEach { v ->
            list.add(
                ExcelTableCellItem(
                    id = UUID.randomUUID().toString(),
                    source = TableItemSource.COMPUTED,
                    variableName = v.name,
                    label = if (v.alias.isNotBlank()) v.alias else v.name.replaceFirstChar { it.uppercase() },
                    prefix = v.prefix,
                    postfix = v.postfix,
                    coordinate = "A$coordNum",
                    isVisible = true,
                    isEmphasized = true // Emphasize computed results by default
                )
            )
            coordNum++
        }
        // Formula total result
        list.add(
            ExcelTableCellItem(
                id = UUID.randomUUID().toString(),
                source = TableItemSource.FORMULA_RESULT,
                label = "TOTAL",
                prefix = profile.formatting.prefix,
                postfix = profile.formatting.postfix,
                coordinate = "A$coordNum",
                isVisible = true,
                isEmphasized = true
            )
        )
        return list
    }

    val effectiveItems = if (tableConfig.items.isNotEmpty()) {
        tableConfig.items
    } else {
        autoPopulateItems()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Interactive Real-Time Spreadsheet Preview
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0x3310B981), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF107C41)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TableChart,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LIVE MINI EXCEL PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF86EFAC),
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AssistChip(
                            onClick = {
                                val nextMode = when (overlay.displayMode) {
                                    OverlayDisplayMode.EXCEL_TABLE -> OverlayDisplayMode.COMPACT_CARD
                                    OverlayDisplayMode.COMPACT_CARD -> OverlayDisplayMode.HYBRID
                                    OverlayDisplayMode.HYBRID -> OverlayDisplayMode.EXCEL_TABLE
                                }
                                onProfileChanged(profile.copy(overlay = overlay.copy(displayMode = nextMode)))
                            },
                            label = {
                                Text(
                                    text = when (overlay.displayMode) {
                                        OverlayDisplayMode.EXCEL_TABLE -> "Mode: Excel Grid"
                                        OverlayDisplayMode.COMPACT_CARD -> "Mode: Compact Card"
                                        OverlayDisplayMode.HYBRID -> "Mode: Hybrid"
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF020617))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ExcelTableOverlayContent(
                            result = sampleResult,
                            config = overlay.copy(
                                tableConfig = tableConfig.copy(items = effectiveItems)
                            )
                        )
                    }
                }
            }
        }

        // 2. Table Header & Style Configuration Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Table Header & Aesthetic Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Show Header Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Show Table Header", fontWeight = FontWeight.SemiBold)
                            Text("Header banner atop spreadsheet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = tableConfig.showHeader,
                            onCheckedChange = { updateTable(tableConfig.copy(showHeader = it, items = effectiveItems)) },
                            modifier = Modifier.testTag("toggle_table_header")
                        )
                    }

                    if (tableConfig.showHeader) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = tableConfig.headerTitle,
                            onValueChange = { updateTable(tableConfig.copy(headerTitle = it, items = effectiveItems)) },
                            label = { Text("Header Title (e.g. DATA SHEET)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tableConfig.headerSubtitle,
                            onValueChange = { updateTable(tableConfig.copy(headerSubtitle = it, items = effectiveItems)) },
                            label = { Text("Header Subtitle / Tag (e.g. SPREADSHEET)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(14.dp))

                    // Column Headers & Coordinates
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Show Column Headers", fontWeight = FontWeight.SemiBold)
                            Text("Displays [PARAM] & [VALUE] header row", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = tableConfig.showColumnHeaders,
                            onCheckedChange = { updateTable(tableConfig.copy(showColumnHeaders = it, items = effectiveItems)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Show Cell Coordinates", fontWeight = FontWeight.SemiBold)
                            Text("Displays A1, A2, A3... spreadsheet cell IDs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = tableConfig.showCoordinates,
                            onCheckedChange = { updateTable(tableConfig.copy(showCoordinates = it, items = effectiveItems)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Grid Theme", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExcelGridTheme.entries.forEach { theme ->
                            val isSelected = tableConfig.gridTheme == theme
                            FilterChip(
                                selected = isSelected,
                                onClick = { updateTable(tableConfig.copy(gridTheme = theme, items = effectiveItems)) },
                                label = {
                                    Text(
                                        when (theme) {
                                            ExcelGridTheme.EXCEL_CLASSIC -> "Excel Classic"
                                            ExcelGridTheme.NEON_CYAN -> "Neon Cyan"
                                            ExcelGridTheme.MODERN_SLATE -> "Modern Slate"
                                            ExcelGridTheme.AMBER_GOLD -> "Amber Gold"
                                        }
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // 3. Cells & Ordering Section Header
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Table Cells & Ordering",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${effectiveItems.count { it.isVisible }} visible · ${effectiveItems.count { it.isEmphasized }} emphasized",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${effectiveItems.size} Total",
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
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("add_table_cell_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Cell")
                    }

                    FilledTonalButton(
                        onClick = {
                            val reset = autoPopulateItems()
                            updateTable(tableConfig.copy(items = reset))
                        }
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Sync")
                    }
                }
            }
        }

        // 4. List of Table Cells (Reorderable, Hide/Show, Prefix/Postfix, Emphasize)
        itemsIndexed(effectiveItems, key = { _, item -> item.id }) { index, item ->
            TableCellRowCard(
                item = item,
                index = index,
                totalCount = effectiveItems.size,
                onMoveUp = {
                    if (index > 0) {
                        val reordered = effectiveItems.toMutableList()
                        val prev = reordered.removeAt(index - 1)
                        reordered.add(index, prev)
                        updateTable(tableConfig.copy(items = reordered))
                    }
                },
                onMoveDown = {
                    if (index < effectiveItems.size - 1) {
                        val reordered = effectiveItems.toMutableList()
                        val current = reordered.removeAt(index)
                        reordered.add(index + 1, current)
                        updateTable(tableConfig.copy(items = reordered))
                    }
                },
                onToggleVisibility = {
                    val updated = effectiveItems.toMutableList()
                    updated[index] = item.copy(isVisible = !item.isVisible)
                    updateTable(tableConfig.copy(items = updated))
                },
                onToggleEmphasis = {
                    val updated = effectiveItems.toMutableList()
                    updated[index] = item.copy(isEmphasized = !item.isEmphasized)
                    updateTable(tableConfig.copy(items = updated))
                },
                onUpdateItem = { newItem ->
                    val updated = effectiveItems.toMutableList()
                    updated[index] = newItem
                    updateTable(tableConfig.copy(items = updated))
                },
                onDelete = {
                    val updated = effectiveItems.toMutableList()
                    updated.removeAt(index)
                    updateTable(tableConfig.copy(items = updated))
                }
            )
        }
    }

    // Add Cell Dialog
    if (showAddDialog) {
        AddTableCellDialog(
            profile = profile,
            onDismiss = { showAddDialog = false },
            onAdd = { newItem ->
                val updated = effectiveItems + newItem
                updateTable(tableConfig.copy(items = updated))
                showAddDialog = false
            }
        )
    }
}

/**
 * Individual Card representing a cell / row in the Excel table.
 */
@Composable
fun TableCellRowCard(
    item: ExcelTableCellItem,
    index: Int,
    totalCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleEmphasis: () -> Unit,
    onUpdateItem: (ExcelTableCellItem) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (item.isEmphasized) {
        Color(0xFF10B981) // Radiant emerald highlight border
    } else if (!item.isVisible) {
        Color(0x22FFFFFF)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    val cardBg = if (item.isEmphasized) {
        Color(0xFF064E3B).copy(alpha = 0.25f)
    } else if (!item.isVisible) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(if (item.isEmphasized) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Row: Controls (Order Up/Down, Coordinate, Source, Emphasis, Visibility, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reorder controls & coordinate
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move Up",
                            tint = if (index > 0) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)
                        )
                    }

                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalCount - 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move Down",
                            tint = if (index < totalCount - 1) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Coordinate pill (A1, A2...)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (item.coordinate.isNotBlank()) item.coordinate else "A${index + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Source badge
                    val sourceBadge = when (item.source) {
                        TableItemSource.VARIABLE -> "Var: ${item.variableName}"
                        TableItemSource.COMPUTED -> "Fx: ${item.variableName}"
                        TableItemSource.FORMULA_RESULT -> "Result"
                        TableItemSource.RAW_QR -> "QR Text"
                        TableItemSource.CUSTOM_TEXT -> "Custom"
                    }
                    Text(
                        text = sourceBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Action buttons: Emphasize ⭐, Visibility 👁️, Delete 🗑️
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Emphasis toggle (Star)
                    IconButton(
                        onClick = onToggleEmphasis,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isEmphasized) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Toggle Emphasis",
                            tint = if (item.isEmphasized) Color(0xFFF59E0B) else Color.Gray
                        )
                    }

                    // Visibility toggle (Eye)
                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Visibility",
                            tint = if (item.isVisible) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Delete
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Cell",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Emphasized badge indicator if highlighted
            if (item.isEmphasized) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "EMPHASIZED SUMMARY CELL (HIGHLIGHTED)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Editable Display Label
            OutlinedTextField(
                value = item.label,
                onValueChange = { onUpdateItem(item.copy(label = it)) },
                label = { Text("Display Label / Header") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Prefix & Postfix Fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = item.prefix,
                    onValueChange = { onUpdateItem(item.copy(prefix = it)) },
                    label = { Text("Prefix (e.g. $)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = item.postfix,
                    onValueChange = { onUpdateItem(item.copy(postfix = it)) },
                    label = { Text("Postfix (e.g. /g)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // If custom text source
            if (item.source == TableItemSource.CUSTOM_TEXT) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = item.customValue,
                    onValueChange = { onUpdateItem(item.copy(customValue = it)) },
                    label = { Text("Custom Text / Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Dialog to add a new cell to the Excel table.
 */
@Composable
fun AddTableCellDialog(
    profile: QrProfile,
    onDismiss: () -> Unit,
    onAdd: (ExcelTableCellItem) -> Unit
) {
    var selectedSource by remember { mutableStateOf(TableItemSource.VARIABLE) }
    var selectedVarName by remember {
        mutableStateOf(profile.parsingRules.variables.firstOrNull()?.name ?: "a")
    }
    var labelText by remember { mutableStateOf(selectedVarName.replaceFirstChar { it.uppercase() }) }
    var prefixText by remember { mutableStateOf("") }
    var postfixText by remember { mutableStateOf("") }
    var isEmphasized by remember { mutableStateOf(false) }
    var customVal by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Table Cell", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Select Information Source:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TableItemSource.entries.forEach { src ->
                        FilterChip(
                            selected = selectedSource == src,
                            onClick = {
                                selectedSource = src
                                when (src) {
                                    TableItemSource.FORMULA_RESULT -> {
                                        labelText = "TOTAL"
                                        prefixText = profile.formatting.prefix
                                        postfixText = profile.formatting.postfix
                                        isEmphasized = true
                                    }
                                    TableItemSource.RAW_QR -> {
                                        labelText = "RAW QR"
                                    }
                                    TableItemSource.CUSTOM_TEXT -> {
                                        labelText = "NOTE"
                                    }
                                    else -> {
                                        val v = profile.parsingRules.variables.find { it.name == selectedVarName }
                                        labelText = v?.alias?.ifBlank { v.name }?.replaceFirstChar { it.uppercase() } ?: "Item"
                                        prefixText = v?.prefix ?: ""
                                        postfixText = v?.postfix ?: ""
                                    }
                                }
                            },
                            label = {
                                Text(
                                    when (src) {
                                        TableItemSource.VARIABLE -> "Token Variable"
                                        TableItemSource.COMPUTED -> "Computed Fx"
                                        TableItemSource.FORMULA_RESULT -> "Formula Result"
                                        TableItemSource.RAW_QR -> "Raw QR"
                                        TableItemSource.CUSTOM_TEXT -> "Custom Text"
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                // If Token or Computed, let user select which variable
                if (selectedSource == TableItemSource.VARIABLE || selectedSource == TableItemSource.COMPUTED) {
                    val candidateVars = if (selectedSource == TableItemSource.COMPUTED) {
                        profile.parsingRules.variables.filter { it.isComputed }
                    } else {
                        profile.parsingRules.variables.filter { !it.isComputed }
                    }

                    if (candidateVars.isNotEmpty()) {
                        Text("Pick Variable:", style = MaterialTheme.typography.labelSmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            candidateVars.forEach { v ->
                                FilterChip(
                                    selected = selectedVarName == v.name,
                                    onClick = {
                                        selectedVarName = v.name
                                        labelText = v.alias.ifBlank { v.name }.replaceFirstChar { it.uppercase() }
                                        prefixText = v.prefix
                                        postfixText = v.postfix
                                    },
                                    label = { Text(if (v.isManual) "${v.name} (manual)" else v.name) }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No ${selectedSource.name.lowercase()} variables defined. Configure them in Parsing settings first.",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Display Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = prefixText,
                        onValueChange = { prefixText = it },
                        label = { Text("Prefix") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = postfixText,
                        onValueChange = { postfixText = it },
                        label = { Text("Postfix") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedSource == TableItemSource.CUSTOM_TEXT) {
                    OutlinedTextField(
                        value = customVal,
                        onValueChange = { customVal = it },
                        label = { Text("Custom Text Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Emphasize / Highlight Cell", fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = isEmphasized,
                        onCheckedChange = { isEmphasized = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(
                        ExcelTableCellItem(
                            id = UUID.randomUUID().toString(),
                            source = selectedSource,
                            variableName = selectedVarName,
                            label = labelText.ifBlank { selectedVarName },
                            prefix = prefixText,
                            postfix = postfixText,
                            isEmphasized = isEmphasized,
                            customValue = customVal,
                            isVisible = true
                        )
                    )
                }
            ) {
                Text("Add to Table")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
