package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.SmartTagWizardAnalyzer
import com.example.engine.WizardFormulaPreset
import com.example.model.QrProfile
import com.example.ui.theme.AppMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickProfileWizardDialog(
    initialSampleTag: String = "120-2.5-15",
    onCreateProfile: (QrProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var sampleTag by remember { mutableStateOf(initialSampleTag) }
    var delimiter by remember { mutableStateOf(SmartTagWizardAnalyzer.detectDelimiter(initialSampleTag)) }
    var profileName by remember { mutableStateOf("Yeni Etiket Profili") }
    var triggerSignal by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(WizardFormulaPreset.NONE) }
    var selectedCurrency by remember { mutableStateOf("Yok") }

    val tokens = remember(sampleTag, delimiter) {
        SmartTagWizardAnalyzer.splitSampleTokens(sampleTag, delimiter)
    }
    var tokenLabels by remember(tokens.size) {
        mutableStateOf(
            List(tokens.size) { idx -> "Alan ${idx + 1}" }
        )
    }

    val quickFieldSuggestions = listOf("Kod", "Raf", "Adet", "Plaka", "Litre", "ISBN", "Tarih", "Fiyat", "Gram")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "Akıllı Etiket Sihirbazı",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Adım $step / 3 · ${
                            when (step) {
                                1 -> "Örnek Etiket & Ayırıcı"
                                2 -> "Alanları İsimlendir"
                                else -> "Hesaplama & Para Birimi"
                            }
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (step) {
                    1 -> {
                        Text(
                            text = "Bir ürün etiketindeki barkod metnini girin veya örneklerden seçin; ayırıcı otomatik bulunur.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = sampleTag,
                            onValueChange = { updated ->
                                sampleTag = updated
                                delimiter = SmartTagWizardAnalyzer.detectDelimiter(updated)
                            },
                            label = { Text("Örnek Barkod / QR Metni") },
                            placeholder = { Text("örn. 120-2.5-15 veya SKU/450/2") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_sample_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("KOLI01-A12-4", "978975-B04-20261101", "34ABC-45.5-43.2", "250-20").forEach { ex ->
                                AssistChip(
                                    onClick = {
                                        sampleTag = ex
                                        delimiter = SmartTagWizardAnalyzer.detectDelimiter(ex)
                                    },
                                    label = { Text(ex, fontFamily = AppMonoFontFamily, fontSize = 11.sp) }
                                )
                            }
                        }

                        Text(
                            text = "Algılanan Ayırıcı",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmartTagWizardAnalyzer.candidateDelimiters.forEach { d ->
                                val label = if (d == " ") "Boşluk" else d
                                FilterChip(
                                    selected = delimiter == d,
                                    onClick = { delimiter = d },
                                    label = { Text(label, fontFamily = AppMonoFontFamily) }
                                )
                            }
                        }

                        if (tokens.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${tokens.size} parça algılandı:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = tokens.mapIndexed { idx, t -> "[${idx + 1}] $t" }.joinToString("   "),
                                        fontFamily = AppMonoFontFamily,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        Text(
                            text = "Her parçaya anlamlı bir ad verin (hızlı etiketlere dokunabilirsiniz):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        tokens.forEachIndexed { idx, tok ->
                            val currentLabel = tokenLabels.getOrElse(idx) { "Alan ${idx + 1}" }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Parça #${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = tok,
                                            fontFamily = AppMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }

                                    OutlinedTextField(
                                        value = currentLabel,
                                        onValueChange = { next ->
                                            tokenLabels = tokenLabels.toMutableList().also { list ->
                                                if (idx < list.size) list[idx] = next
                                            }
                                        },
                                        singleLine = true,
                                        label = { Text("Alan Adı") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        quickFieldSuggestions.forEach { sug ->
                                            FilterChip(
                                                selected = currentLabel.equals(sug, ignoreCase = true),
                                                onClick = {
                                                    tokenLabels = tokenLabels.toMutableList().also { list ->
                                                        if (idx < list.size) list[idx] = sug
                                                    }
                                                },
                                                label = { Text(sug, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Profil Adı") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_profile_name_input")
                        )

                        OutlinedTextField(
                            value = triggerSignal,
                            onValueChange = { triggerSignal = it },
                            label = { Text("Otomatik Geçiş Sinyali (İsteğe Bağlı)") },
                            placeholder = { Text("örn. TESBIH/ veya ALTIN-") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Hesaplama Şablonu",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        WizardFormulaPreset.entries.forEach { preset ->
                            val isSelected = selectedPreset == preset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                ),
                                onClick = { selectedPreset = preset },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preset.title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Text(
                                            preset.subtitle,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Para Birimi / Ön Ek",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("₺", "$", "€", "Yok").forEach { curr ->
                                FilterChip(
                                    selected = selectedCurrency == curr,
                                    onClick = { selectedCurrency = curr },
                                    label = { Text(curr, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step < 3) {
                Button(
                    onClick = { step++ },
                    enabled = tokens.isNotEmpty(),
                    modifier = Modifier.testTag("wizard_next_btn")
                ) {
                    Text("İleri")
                }
            } else {
                Button(
                    onClick = {
                        val built = SmartTagWizardAnalyzer.buildProfileFromWizard(
                            profileName = profileName,
                            delimiter = delimiter,
                            tokenLabels = tokenLabels,
                            sampleTokens = tokens,
                            formulaPreset = selectedPreset,
                            currencyPrefix = selectedCurrency,
                            triggerSignal = triggerSignal
                        )
                        onCreateProfile(built)
                    },
                    modifier = Modifier.testTag("wizard_finish_btn")
                ) {
                    Text("Oluştur ve Aktifleştir")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (step > 1) step-- else onDismiss()
                }
            ) {
                Text(if (step > 1) "Geri" else "İptal")
            }
        }
    )
}
