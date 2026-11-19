package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FieldDataType
import com.example.model.GlobalVariable
import com.example.ui.theme.AppMonoFontFamily
import java.util.UUID

/**
 * Collapsible manager for shared @global constants usable across ALL profiles
 * (e.g. @benzin_lt, @motorin_lt, @dolar, @kdv, @ceza_gun).
 * Changing a value here updates every profile referencing it immediately.
 */
@Composable
fun GlobalVariablesCard(
    globalVariables: List<GlobalVariable>,
    onGlobalVariablesChanged: (List<GlobalVariable>) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddRow by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(FieldDataType.DOUBLE) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag("global_vars_card_header"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Ortak @Global Sabitler",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${globalVariables.size} paylaşılan parametre · Tüm profillerde @isim ile kullanılır",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Daralt" else "Genişlet"
                    )
                }
            }

            if (!expanded && globalVariables.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    globalVariables.forEach { gv ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = "@${gv.name}=${gv.value}",
                                fontFamily = AppMonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    Text(
                        text = "Tek yerden güncellenen ortak değerler (yakıt fiyatı, döviz kuru, KDV, ceza tutarı vb.). Her profilde @isim şeklinde kullanabilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    globalVariables.forEachIndexed { idx, gv ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.weight(0.45f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "@${gv.name}",
                                        fontFamily = AppMonoFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        maxLines = 1
                                    )
                                    if (gv.description.isNotBlank()) {
                                        Text(
                                            text = gv.description,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = gv.value,
                                onValueChange = { updatedVal ->
                                    val copy = globalVariables.toMutableList()
                                    copy[idx] = gv.copy(value = updatedVal)
                                    onGlobalVariablesChanged(copy)
                                },
                                singleLine = true,
                                label = { Text("Değer", fontSize = 11.sp) },
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = AppMonoFontFamily,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier
                                    .weight(0.45f)
                                    .testTag("global_var_value_${gv.name}")
                            )

                            IconButton(
                                onClick = {
                                    onGlobalVariablesChanged(globalVariables.filterIndexed { i, _ -> i != idx })
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Global Sabiti Sil",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (showAddRow) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newName,
                                        onValueChange = { next ->
                                            newName = next.filter { it.isLetterOrDigit() || it == '_' }.lowercase()
                                        },
                                        label = { Text("Ad (@ olmadan)") },
                                        placeholder = { Text("örn. euro") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("new_global_name_input")
                                    )
                                    OutlinedTextField(
                                        value = newValue,
                                        onValueChange = { newValue = it },
                                        label = { Text("Değer") },
                                        placeholder = { Text("41.20") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("new_global_value_input")
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(FieldDataType.DOUBLE, FieldDataType.STRING, FieldDataType.DATE).forEach { dt ->
                                        FilterChip(
                                            selected = newType == dt,
                                            onClick = { newType = dt },
                                            label = { Text(dt.label, fontSize = 11.sp) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { showAddRow = false }) {
                                        Text("İptal")
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            val clean = newName.trim()
                                            if (clean.isNotEmpty()) {
                                                val created = GlobalVariable(
                                                    id = UUID.randomUUID().toString(),
                                                    name = clean,
                                                    value = newValue.ifBlank { "0" },
                                                    dataType = newType
                                                )
                                                onGlobalVariablesChanged(globalVariables + created)
                                                newName = ""
                                                newValue = ""
                                                showAddRow = false
                                            }
                                        },
                                        enabled = newName.isNotBlank(),
                                        modifier = Modifier.testTag("confirm_add_global_btn")
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ekle")
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showAddRow = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_global_var_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Yeni @Global Sabit Ekle")
                        }
                    }
                }
            }
        }
    }
}
