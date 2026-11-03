package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ResultFormatter
import com.example.model.AppRoundingMode
import com.example.model.ResultFormatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormattingSettingsSection(
    formatting: ResultFormatting,
    onFormattingChanged: (ResultFormatting) -> Unit,
    modifier: Modifier = Modifier
) {
    // Live preview of formatting with sample value 12345.678
    val samplePreviewPositive = remember(formatting) {
        ResultFormatter.format(12345.678, formatting)
    }
    val samplePreviewNegative = remember(formatting) {
        ResultFormatter.format(-12345.678, formatting)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Sample Preview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Formatted Output Preview",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Positive: $samplePreviewPositive",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Negative: $samplePreviewNegative",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Numeric Type
        item {
            Text(text = "Numeric Type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Integer", "Float", "Double").forEach { type ->
                    FilterChip(
                        selected = formatting.numericType == type,
                        onClick = { onFormattingChanged(formatting.copy(numericType = type)) },
                        label = { Text(type) }
                    )
                }
            }
        }

        // Decimal Precision (0-6)
        item {
            Text(text = "Decimal Precision (${formatting.decimalPrecision} digits)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
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
                        label = { Text("$digits") }
                    )
                }
            }
        }

        // Rounding Mode
        item {
            Text(text = "Rounding Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
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
                        label = { Text(cleanLabel, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Prefix and Postfix
        item {
            Text(text = "Prefix & Postfix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formatting.prefix,
                    onValueChange = { onFormattingChanged(formatting.copy(prefix = it)) },
                    label = { Text("Prefix (e.g. $, €, ₺)") },
                    modifier = Modifier.weight(1f).testTag("prefix_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = formatting.postfix,
                    onValueChange = { onFormattingChanged(formatting.copy(postfix = it)) },
                    label = { Text("Postfix (e.g. TL, kg, %)") },
                    modifier = Modifier.weight(1f).testTag("postfix_input"),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("$", "€", "₺", "£", "¥").forEach { p ->
                    AssistChip(
                        onClick = { onFormattingChanged(formatting.copy(prefix = p)) },
                        label = { Text("Prefix $p") }
                    )
                }
                listOf(" TL", " kg", " m²", " L", " %").forEach { s ->
                    AssistChip(
                        onClick = { onFormattingChanged(formatting.copy(postfix = s)) },
                        label = { Text("Postfix$s") }
                    )
                }
            }
        }

        // Thousands and Decimal Separators
        item {
            Text(text = "Separators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

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
                        label = { Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                    label = { Text("Dot (.)") }
                )
                FilterChip(
                    selected = formatting.decimalSeparator == ",",
                    onClick = { onFormattingChanged(formatting.copy(decimalSeparator = ",")) },
                    label = { Text("Comma (,)") }
                )
            }
        }

        // Negative format and leading zeros
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "Negative Number Format", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
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
                                label = { Text(example) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Leading Zeros")
                        Switch(
                            checked = formatting.showLeadingZeros,
                            onCheckedChange = { onFormattingChanged(formatting.copy(showLeadingZeros = it)) }
                        )
                    }

                    if (formatting.showLeadingZeros) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Min Integer Digits: ${formatting.minIntegerDigits}")
                            Slider(
                                value = formatting.minIntegerDigits.toFloat(),
                                onValueChange = { onFormattingChanged(formatting.copy(minIntegerDigits = it.toInt())) },
                                valueRange = 1f..6f,
                                steps = 4,
                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
