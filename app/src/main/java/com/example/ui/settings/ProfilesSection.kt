package com.example.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.example.data.AttachmentRepository
import com.example.data.BackupTransferManager
import com.example.data.ImportPreviewData
import com.example.data.ProfileRepository
import com.example.debug.AppDebugLogger
import com.example.engine.QrCodeGenerator
import com.example.engine.QrParser
import com.example.model.QrAttachment
import com.example.model.QrProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSection(
    profileRepository: ProfileRepository,
    attachmentRepository: AttachmentRepository? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val activeProfile by profileRepository.activeProfile.collectAsState()
    val profiles by profileRepository.profiles.collectAsState()
    val attachments by (attachmentRepository?.attachments?.collectAsState() ?: remember { mutableStateOf(emptyList<QrAttachment>()) })

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<QrProfile?>(null) }
    var showSignalDialog by remember { mutableStateOf<QrProfile?>(null) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var showImportOptionsDialog by remember { mutableStateOf(false) }
    var showQrDisplayDialog by remember { mutableStateOf<QrProfile?>(null) }

    // Export control state
    var exportTargetProfile by remember { mutableStateOf<QrProfile?>(null) }
    var selectedExportProfileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportIncludeMedia by remember { mutableStateOf(true) }

    // Import control state
    var pendingImportPreview by remember { mutableStateOf<ImportPreviewData?>(null) }
    var selectedImportProfileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importIncludeMedia by remember { mutableStateOf(true) }
    var importOverwriteExisting by remember { mutableStateOf(false) }

    var newProfileName by remember { mutableStateOf("") }
    var newProfileDesc by remember { mutableStateOf("") }
    var newProfileSignal by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var signalEditText by remember { mutableStateOf("") }
    var importJsonText by remember { mutableStateOf("") }

    // State for pending file export payload
    var pendingExportJson by remember { mutableStateOf("") }
    var pendingExportFileName by remember { mutableStateOf("qr_profiles.json") }
    var pendingExportProfiles by remember { mutableStateOf<List<QrProfile>>(emptyList()) }
    var pendingExportAttachments by remember { mutableStateOf<List<QrAttachment>>(emptyList()) }

    // SAF CreateDocument for exporting JSON file
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(pendingExportJson.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Dosya başarıyla dışa aktarıldı!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                AppDebugLogger.e("PROFILE", "JSON dışa aktarım hatası", e)
                Toast.makeText(context, "Dosya kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF CreateDocument for exporting ZIP archive with media and JSON
    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    BackupTransferManager.writeExportZipToStream(
                        outputStream = outputStream,
                        profiles = pendingExportProfiles,
                        attachments = pendingExportAttachments,
                        includeMedia = exportIncludeMedia
                    )
                }
                Toast.makeText(context, "ZIP arşivi başarıyla dışa aktarıldı!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                AppDebugLogger.e("PROFILE", "ZIP dışa aktarım hatası", e)
                Toast.makeText(context, "ZIP kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF OpenDocument for importing either ZIP or JSON file
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val preview = BackupTransferManager.parseImportAuto(context, uri)
                if (preview.profiles.isNotEmpty() || preview.attachments.isNotEmpty()) {
                    pendingImportPreview = preview
                    selectedImportProfileIds = preview.profiles.map { it.id }.toSet()
                    importIncludeMedia = preview.hasMedia
                } else {
                    Toast.makeText(context, "Dosyada geçerli profil bulunamadı", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                AppDebugLogger.e("PROFILE", "Dosya içe aktarım hatası", e)
                Toast.makeText(context, "Dosya okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun shareProfileText(title: String, json: String) {
        runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, json)
            }
            val shareIntent = Intent.createChooser(sendIntent, "Profili Paylaş")
            context.startActivity(shareIntent)
        }.onFailure { e ->
            AppDebugLogger.e("SHARE", "Profil paylaşım intent hatası", e)
            clipboardManager.setText(AnnotatedString(json))
            Toast.makeText(context, "Paylaşım uygulaması bulunamadı, panoya kopyalandı", Toast.LENGTH_SHORT).show()
        }
    }

    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + navBottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Yapılandırma Profilleri & Ön Ayarlar",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Profilleri yönetin, dışa/içe aktarın veya QR kod ile başka telefonlara anında aktarın. Otomatik geçiş sinyalleri barkodun kendi profilini seçmesini sağlar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action Buttons: New Profile, Export, Import (clean layout preventing overflow)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Button(
                        onClick = {
                            newProfileName = ""
                            newProfileDesc = ""
                            newProfileSignal = ""
                            showCreateDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().testTag("create_profile_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yeni Profil Oluştur", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                exportTargetProfile = null
                                selectedExportProfileIds = profiles.map { it.id }.toSet()
                                showExportOptionsDialog = true
                            },
                            modifier = Modifier.weight(1f).testTag("export_profiles_btn"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Dışa Aktar", maxLines = 1, softWrap = false)
                        }

                        FilledTonalButton(
                            onClick = { showImportOptionsDialog = true },
                            modifier = Modifier.weight(1f).testTag("import_profiles_btn"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("İçe Aktar", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // List of Profiles
        items(profiles) { profile ->
            val isActive = profile.id == activeProfile.id

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isActive) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "AKTİF",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (profile.description.isNotBlank()) {
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isActive) {
                            FilledTonalButton(
                                onClick = { profileRepository.selectProfile(profile.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Seç")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Summary Details & Signal Chip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Ayırıcı: '${profile.parsingRules.effectiveDelimiter}'", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("Formül: ${profile.formula.expression}", fontSize = 11.sp) }
                        )

                        // Trigger Signal Chip (clickable to edit)
                        AssistChip(
                            onClick = {
                                signalEditText = profile.triggerSignal
                                showSignalDialog = profile
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (profile.triggerSignal.isNotBlank()) "Sinyal: '${profile.triggerSignal}'" else "+ Sinyal Ekle",
                                    fontSize = 11.sp,
                                    fontWeight = if (profile.triggerSignal.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row actions: QR Beam, Share, Rename, Duplicate, Delete
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // QR Beam button: shows profile as QR code
                        IconButton(
                            onClick = { showQrDisplayDialog = profile }
                        ) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = "QR Kodunu Göster",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Export/Share Profile button
                        IconButton(
                            onClick = {
                                exportTargetProfile = profile
                                selectedExportProfileIds = setOf(profile.id)
                                showExportOptionsDialog = true
                            }
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Profili Dışa Aktar",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Rename button
                        IconButton(
                            onClick = {
                                renameText = profile.name
                                showRenameDialog = profile
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Yeniden Adlandır", modifier = Modifier.size(20.dp))
                        }

                        // Duplicate button
                        IconButton(
                            onClick = {
                                profileRepository.duplicateProfile(profile.id)
                                Toast.makeText(context, "Profil kopyalandı", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala", modifier = Modifier.size(20.dp))
                        }

                        // Delete button
                        if (profiles.size > 1) {
                            IconButton(
                                onClick = {
                                    profileRepository.deleteProfile(profile.id)
                                    Toast.makeText(context, "Profil silindi", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Sil",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Reset to Defaults Button
        item {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    profileRepository.resetToDefaults()
                    Toast.makeText(context, "Varsayılan profillere sıfırlandı", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Tüm Profilleri Varsayılana Sıfırla")
            }
        }
    }

    // 1. Create Profile Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Yeni Profil Oluştur") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profil Adı") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newProfileDesc,
                        onValueChange = { newProfileDesc = it },
                        label = { Text("Açıklama (İsteğe Bağlı)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProfileSignal,
                        onValueChange = { newProfileSignal = it },
                        label = { Text("Otomatik Geçiş Sinyali (İsteğe Bağlı)") },
                        placeholder = { Text("örn. TESBIH-, GOLD, [P:amber]") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            val created = profileRepository.createProfile(newProfileName, newProfileDesc)
                            if (newProfileSignal.isNotBlank()) {
                                profileRepository.saveProfile(created.copy(triggerSignal = newProfileSignal.trim()))
                            }
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Oluştur")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }

    // 2. Rename Profile Dialog
    showRenameDialog?.let { target ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Profili Yeniden Adlandır") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Yeni Ad") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            profileRepository.saveProfile(target.copy(name = renameText))
                            showRenameDialog = null
                        }
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("İptal")
                }
            }
        )
    }

    // 3. Edit Trigger Signal Dialog
    showSignalDialog?.let { target ->
        AlertDialog(
            onDismissRequest = { showSignalDialog = null },
            title = { Text("QR Otomatik Geçiş Sinyali") },
            text = {
                Column {
                    Text(
                        text = "Taranan bir QR kodu bu metinle başladığında veya içerdiğinde kamera otomatik olarak '${target.name}' profiline geçer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = signalEditText,
                        onValueChange = { signalEditText = it },
                        label = { Text("Tetikleyici Sinyal") },
                        placeholder = { Text("örn. TESBIH-, GOLD veya [P:amber]") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        profileRepository.saveProfile(target.copy(triggerSignal = signalEditText.trim()))
                        showSignalDialog = null
                    }
                ) {
                    Text("Sinyali Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignalDialog = null }) {
                    Text("İptal")
                }
            }
        )
    }

    // 4. Export Options Dialog with granular choices: Individual profile(s), All profiles, With or Without Media
    if (showExportOptionsDialog) {
        val targetExportProfiles = if (selectedExportProfileIds.isEmpty()) {
            if (exportTargetProfile != null) listOf(exportTargetProfile!!) else profiles
        } else {
            profiles.filter { it.id in selectedExportProfileIds }
        }

        // Relevant attachments that match the chosen profiles
        val relevantAttachments = remember(targetExportProfiles, attachments) {
            val signals = targetExportProfiles.map { it.triggerSignal.trim().lowercase() }.filter { it.isNotBlank() }
            if (signals.isEmpty() || targetExportProfiles.size == profiles.size) {
                attachments
            } else {
                attachments.filter { att ->
                    val c = att.qrContent.trim().lowercase()
                    signals.any { s -> c.contains(s) }
                }
            }
        }

        val mediaCount = relevantAttachments.sumOf { it.allImages.size + it.allVideos.size }

        AlertDialog(
            onDismissRequest = { showExportOptionsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (exportTargetProfile != null && selectedExportProfileIds.size == 1) "'${exportTargetProfile?.name}' Dışa Aktar" else "Profilleri ve Verileri Dışa Aktar"
                    )
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
                    Text(
                        text = "Dışa aktarım paketini özelleştirin. Profilleri ve bağlı medya dosyalarının dahil edilip edilmeyeceğini seçin:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Profile Scope Selection
                    Text(
                        text = "1. DIŞA AKTARILACAK PROFİLLER (${targetExportProfiles.size}/${profiles.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedExportProfileIds.size == profiles.size,
                            onClick = {
                                exportTargetProfile = null
                                selectedExportProfileIds = profiles.map { it.id }.toSet()
                            },
                            label = { Text("Tümü (${profiles.size})", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedExportProfileIds.size == 1 && selectedExportProfileIds.contains(activeProfile.id),
                            onClick = {
                                exportTargetProfile = activeProfile
                                selectedExportProfileIds = setOf(activeProfile.id)
                            },
                            label = { Text("Yalnızca Aktif", fontSize = 11.sp) }
                        )
                    }

                    // Checklist of all individual profiles
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            profiles.forEach { p ->
                                val isChecked = p.id in selectedExportProfileIds || (selectedExportProfileIds.isEmpty() && (exportTargetProfile == null || exportTargetProfile?.id == p.id))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val current = if (selectedExportProfileIds.isEmpty()) {
                                                profiles.map { it.id }.toMutableSet()
                                            } else {
                                                selectedExportProfileIds.toMutableSet()
                                            }
                                            if (isChecked) {
                                                if (current.size > 1) current.remove(p.id)
                                            } else {
                                                current.add(p.id)
                                            }
                                            selectedExportProfileIds = current
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val current = if (selectedExportProfileIds.isEmpty()) {
                                                profiles.map { it.id }.toMutableSet()
                                            } else {
                                                selectedExportProfileIds.toMutableSet()
                                            }
                                            if (checked) current.add(p.id) else if (current.size > 1) current.remove(p.id)
                                            selectedExportProfileIds = current
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            text = "Formül: ${p.formula.expression.ifBlank { "a+b+c+d" }}${if (p.triggerSignal.isNotBlank()) " • Sinyal: ${p.triggerSignal}" else ""}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Media Inclusion Choice (With Media vs Without Media)
                    Text(
                        text = "2. MEDYA DAHİL ETME SEÇENEĞİ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (exportIncludeMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (exportIncludeMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { exportIncludeMedia = true }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = exportIncludeMedia,
                                        onClick = { exportIncludeMedia = true },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Medya Dahil", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (mediaCount > 0) "$mediaCount ekli fotoğraf/video dahil. Taşınabilir paket." else "Medya destekli tam paket.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!exportIncludeMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (!exportIncludeMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { exportIncludeMedia = false }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = !exportIncludeMedia,
                                        onClick = { exportIncludeMedia = false },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Medyasız", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Sadece formüller, kurallar ve ayarlar. Çok hafif JSON (<20 KB).",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. Export Actions (Save File, Share, Copy)
                    Text(
                        text = "3. DIŞA AKTARIM HEDEFİ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    fun generateExportPayload(): String {
                        return BackupTransferManager.createExportJson(
                            profiles = targetExportProfiles,
                            attachments = relevantAttachments,
                            includeMedia = exportIncludeMedia
                        )
                    }

                    val baseExportName = if (targetExportProfiles.size == 1) {
                        "profile_${targetExportProfiles.first().name.lowercase().replace("\\s+".toRegex(), "_")}"
                    } else {
                        "qr_profiles_export"
                    }

                    // Primary Action: Share via Apps using a single ZIP (containing media + JSON) or JSON text
                    Button(
                        onClick = {
                            if (exportIncludeMedia && mediaCount > 0) {
                                runCatching {
                                    val zipFile = BackupTransferManager.createExportZipFile(
                                        context = context,
                                        profiles = targetExportProfiles,
                                        attachments = relevantAttachments,
                                        includeMedia = true,
                                        filePrefix = baseExportName
                                    )
                                    val shareIntent = BackupTransferManager.getShareIntentForZip(
                                        context = context,
                                        zipFile = zipFile,
                                        subject = "ALVETAK QR Profil Yedeği ($mediaCount medya dosyası)"
                                    )
                                    context.startActivity(Intent.createChooser(shareIntent, "Yedek Arşivini Paylaş"))
                                }.onFailure { e ->
                                    AppDebugLogger.e("SHARE", "ZIP paylaşım hatası", e)
                                    Toast.makeText(context, "ZIP paylaşılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                val json = generateExportPayload()
                                val title = if (targetExportProfiles.size == 1) {
                                    "Profil: ${targetExportProfiles.first().name}"
                                } else {
                                    "${targetExportProfiles.size} Profil"
                                }
                                shareProfileText(title, json)
                            }
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("share_profiles_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (exportIncludeMedia && mediaCount > 0) "Tek ZIP Olarak Paylaş (Medya + JSON)" else "Uygulamalarla Paylaş",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Save as Single ZIP File
                    FilledTonalButton(
                        onClick = {
                            pendingExportProfiles = targetExportProfiles
                            pendingExportAttachments = relevantAttachments
                            val zipName = if (exportIncludeMedia && mediaCount > 0) "${baseExportName}_with_media.zip" else "${baseExportName}.zip"
                            exportZipLauncher.launch(zipName)
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_save_zip_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ZIP Arşivi Olarak Kaydet (.zip)", fontSize = 12.sp)
                    }

                    // Save as JSON File
                    OutlinedButton(
                        onClick = {
                            val json = generateExportPayload()
                            pendingExportJson = json
                            pendingExportFileName = if (exportIncludeMedia) "${baseExportName}_with_media.json" else "${baseExportName}_no_media.json"
                            exportFileLauncher.launch(pendingExportFileName)
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_save_file_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("JSON Dosyası Olarak Kaydet (.json)", fontSize = 12.sp)
                    }

                    // Copy JSON to clipboard
                    OutlinedButton(
                        onClick = {
                            val json = generateExportPayload()
                            clipboardManager.setText(AnnotatedString(json))
                            Toast.makeText(context, "Dışa aktarım JSON panoya kopyalandı!", Toast.LENGTH_SHORT).show()
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("JSON'u Panoya Kopyala", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportOptionsDialog = false }) {
                    Text("Kapat")
                }
            }
        )
    }

    // 5. Import Options Dialog (Open File, Paste, Scan)
    if (showImportOptionsDialog) {
        var isPastingMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showImportOptionsDialog = false },
            title = { Text(if (isPastingMode) "Profil JSON Yapıştır" else "Profilleri İçe Aktar") },
            text = {
                if (isPastingMode) {
                    Column {
                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            placeholder = { Text("[{ \"name\": \"Profilim\", ... }]") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Kayıtlı bir dosyadan, panodan veya başka bir telefondaki QR koddan profil içe aktarın:",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Open Document from device: supports ZIP and JSON
                        Button(
                            onClick = {
                                importFileLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/*", "*/*"))
                                showImportOptionsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().testTag("import_file_btn")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ZIP Arşivi veya JSON Dosyası Aç")
                        }

                        // Paste JSON
                        FilledTonalButton(
                            onClick = { isPastingMode = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Panodan Yapıştır")
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "İpucu: Anında içe aktarmak için kameranızı doğrudan başka bir cihazın Profil QR koduna da tutabilirsiniz.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isPastingMode) {
                    Button(
                        onClick = {
                            val preview = BackupTransferManager.parseImportPreview(importJsonText)
                            if (preview.profiles.isNotEmpty() || preview.attachments.isNotEmpty()) {
                                showImportOptionsDialog = false
                                pendingImportPreview = preview
                                selectedImportProfileIds = preview.profiles.map { it.id }.toSet()
                                importIncludeMedia = preview.hasMedia
                                importJsonText = ""
                            } else {
                                Toast.makeText(context, "Geçersiz JSON verisi veya boş profil paketi.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("İncele ve İçe Aktar")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isPastingMode) {
                            isPastingMode = false
                        } else {
                            showImportOptionsDialog = false
                        }
                    }
                ) {
                    Text("İptal")
                }
            }
        )
    }

    // 6. Interactive Import Review & Control Dialog: Select individual profiles, with or without media
    val preview = pendingImportPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { pendingImportPreview = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("İçe Aktarım İncelemesi")
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
                    Text(
                        text = "${preview.profiles.size} profil${if (preview.attachments.isNotEmpty()) ", ${preview.attachments.size} bağlı veri" else ""}${if (preview.hasMedia) " ve gömülü medya (~${BackupTransferManager.formatBytes(preview.totalMediaBytesEstimate)})" else ""} bulundu. İçe aktarılacakları seçin:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Profile Checklist
                    if (preview.profiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PROFİL SEÇİMİ (${selectedImportProfileIds.size}/${preview.profiles.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { selectedImportProfileIds = preview.profiles.map { it.id }.toSet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Tümünü Seç", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { selectedImportProfileIds = emptySet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Temizle", fontSize = 11.sp)
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                preview.profiles.forEach { p ->
                                    val isChecked = p.id in selectedImportProfileIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val next = selectedImportProfileIds.toMutableSet()
                                                if (isChecked) next.remove(p.id) else next.add(p.id)
                                                selectedImportProfileIds = next
                                            }
                                            .padding(vertical = 4.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                val next = selectedImportProfileIds.toMutableSet()
                                                if (checked) next.add(p.id) else next.remove(p.id)
                                                selectedImportProfileIds = next
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(
                                                text = "Ayırıcı: '${p.parsingRules.effectiveDelimiter}' • Formül: ${p.formula.expression.ifBlank { "a+b+c+d" }}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Media Choice (Import with media or without media)
                    if (preview.hasMedia || preview.attachments.isNotEmpty()) {
                        Text(
                            text = "MEDYA İÇE AKTARIM SEÇENEĞİ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (importIncludeMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (importIncludeMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { importIncludeMedia = true }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = importIncludeMedia,
                                            onClick = { importIncludeMedia = true },
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Medya Dahil", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Fotoğraf ve videoları cihaz depolamasına geri yükler.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!importIncludeMedia) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (!importIncludeMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { importIncludeMedia = false }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = !importIncludeMedia,
                                            onClick = { importIncludeMedia = false },
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Medyasız", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Yalnızca kurallar ve ayarlar; medya dosyaları atlanır.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // 3. Conflict Resolution
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { importOverwriteExisting = !importOverwriteExisting }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = importOverwriteExisting,
                            onCheckedChange = { importOverwriteExisting = it },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ID veya ad eşleşirse mevcut profilin üzerine yaz",
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = BackupTransferManager.executeImport(
                            preview = preview,
                            selectedProfileIds = selectedImportProfileIds,
                            selectedAttachmentIds = preview.attachments.map { it.id }.toSet(),
                            importMedia = importIncludeMedia,
                            overwriteExisting = importOverwriteExisting,
                            profileRepository = profileRepository,
                            attachmentRepository = attachmentRepository
                        )
                        Toast.makeText(
                            context,
                            "${result.profilesCount} profil${if (result.mediaFilesCount > 0) " ve ${result.mediaFilesCount} medya dosyası" else ""} içe aktarıldı!",
                            Toast.LENGTH_LONG
                        ).show()
                        pendingImportPreview = null
                    },
                    enabled = selectedImportProfileIds.isNotEmpty() || preview.attachments.isNotEmpty()
                ) {
                    Text("Seçilenleri Aktar (${selectedImportProfileIds.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportPreview = null }) {
                    Text("İptal")
                }
            }
        )
    }

    // 6. QR Code Display Dialog (Phone-to-Phone Compressed Beam)
    showQrDisplayDialog?.let { targetProfile ->
        val qrString = remember(targetProfile.id) {
            QrParser.encodeProfileToCompressedQrString(targetProfile)
        }
        val compressionStats = remember(targetProfile.id) {
            QrParser.getProfileCompressionStats(targetProfile)
        }
        val qrBitmap = remember(qrString) {
            QrCodeGenerator.generateQrBitmap(
                content = qrString,
                size = 640
            )
        }

        AlertDialog(
            onDismissRequest = { showQrDisplayDialog = null },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "QR ile Profil Aktar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = targetProfile.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Compression badge
                    val orig = compressionStats.first
                    val comp = compressionStats.second
                    val pct = if (orig > 0) ((1.0 - comp.toDouble() / orig.toDouble()) * 100).toInt() else 0

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GZIP Sıkıştırılmış: $comp B (%${pct} daha küçük) • Hızlı Tarama",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "${targetProfile.name} için sıkıştırılmış profil QR kodu",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Text(
                            text = "QR kod görseli oluşturulamadı.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "ALVETAK QR yüklü başka bir telefonu bu koda tutun. Kamera profili otomatik algılayıp açacak ve '${targetProfile.name}' profilini aktifleştirmeyi önerecektir.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(qrString))
                            Toast.makeText(context, "Sıkıştırılmış QR verisi panoya kopyalandı!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Kodu Kopyala", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            val json = profileRepository.exportSingleProfileJson(targetProfile.id)
                            shareProfileText("ALVETAK QR Profili - ${targetProfile.name}", json)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("JSON Paylaş", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrDisplayDialog = null }) {
                    Text("Kapat")
                }
            }
        )
    }
}
