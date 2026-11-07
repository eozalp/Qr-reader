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
import com.example.engine.QrCodeGenerator
import com.example.engine.QrParser
import com.example.model.QrAttachment
import com.example.model.QrProfile
import java.io.BufferedReader
import java.io.InputStreamReader

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
                Toast.makeText(context, "Exported file successfully!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "Exported ZIP archive successfully!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "Failed to save ZIP: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "No valid profiles found in file", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun shareProfileText(title: String, json: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, json)
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Profile via")
        context.startActivity(shareIntent)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Configuration Profiles & Presets",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Manage, export, import, or beam profiles between phones via QR codes or file sharing. Auto-switch triggers allow codes to pick their own profile.",
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
                        Text("Create New Profile", fontWeight = FontWeight.SemiBold)
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
                            Text("Export", maxLines = 1, softWrap = false)
                        }

                        FilledTonalButton(
                            onClick = { showImportOptionsDialog = true },
                            modifier = Modifier.weight(1f).testTag("import_profiles_btn"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import", maxLines = 1, softWrap = false)
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
                                            text = "ACTIVE",
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
                                Text("Activate")
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
                            label = { Text("Delim: '${profile.parsingRules.effectiveDelimiter}'", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("Formula: ${profile.formula.expression}", fontSize = 11.sp) }
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
                                    text = if (profile.triggerSignal.isNotBlank()) "Signal: '${profile.triggerSignal}'" else "+ Add Signal",
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
                                contentDescription = "Show QR Code",
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
                                contentDescription = "Export Profile",
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
                            Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
                        }

                        // Duplicate button
                        IconButton(
                            onClick = {
                                profileRepository.duplicateProfile(profile.id)
                                Toast.makeText(context, "Profile duplicated", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(20.dp))
                        }

                        // Delete button
                        if (profiles.size > 1) {
                            IconButton(
                                onClick = {
                                    profileRepository.deleteProfile(profile.id)
                                    Toast.makeText(context, "Profile deleted", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
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
                    Toast.makeText(context, "Reset to default profiles", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset All Profiles to Defaults")
            }
        }
    }

    // 1. Create Profile Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newProfileDesc,
                        onValueChange = { newProfileDesc = it },
                        label = { Text("Description (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProfileSignal,
                        onValueChange = { newProfileSignal = it },
                        label = { Text("Auto-Switch Signal (Optional)") },
                        placeholder = { Text("e.g. TESBIH-, GOLD, [P:amber]") },
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
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. Rename Profile Dialog
    showRenameDialog?.let { target ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Profile") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
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
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Edit Trigger Signal Dialog
    showSignalDialog?.let { target ->
        AlertDialog(
            onDismissRequest = { showSignalDialog = null },
            title = { Text("QR Auto-Switch Signal") },
            text = {
                Column {
                    Text(
                        text = "When a scanned QR code starts with or contains this text or tag, the camera automatically switches to '${target.name}'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = signalEditText,
                        onValueChange = { signalEditText = it },
                        label = { Text("Trigger Signal") },
                        placeholder = { Text("e.g. TESBIH-, GOLD, or [P:amber]") },
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
                    Text("Save Signal")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignalDialog = null }) {
                    Text("Cancel")
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
                        if (exportTargetProfile != null && selectedExportProfileIds.size == 1) "Export '${exportTargetProfile?.name}'" else "Export Profiles & Data"
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
                        text = "Customize your export package. Choose individual profiles and whether to include linked media files:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Profile Scope Selection
                    Text(
                        text = "1. SELECT PROFILES TO EXPORT (${targetExportProfiles.size}/${profiles.size})",
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
                            label = { Text("All (${profiles.size})", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedExportProfileIds.size == 1 && selectedExportProfileIds.contains(activeProfile.id),
                            onClick = {
                                exportTargetProfile = activeProfile
                                selectedExportProfileIds = setOf(activeProfile.id)
                            },
                            label = { Text("Active Only", fontSize = 11.sp) }
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
                                            text = "Formula: ${p.formula.expression.ifBlank { "a+b+c+d" }}${if (p.triggerSignal.isNotBlank()) " • Signal: ${p.triggerSignal}" else ""}",
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
                        text = "2. MEDIA INCLUSION CHOICE",
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
                                    Text("With Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (mediaCount > 0) "Includes $mediaCount attached photo(s)/video(s). Portable bundle." else "Full bundle with media support.",
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
                                    Text("Without Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Formulas, rules & settings only. Ultra-lightweight JSON (<20 KB).",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. Export Actions (Save File, Share, Copy)
                    Text(
                        text = "3. CHOOSE EXPORT DESTINATION",
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
                                    subject = "QR Live Calc Profiles Export ($mediaCount media files)"
                                )
                                context.startActivity(Intent.createChooser(shareIntent, "Share Backup Archive via"))
                            } else {
                                val json = generateExportPayload()
                                val title = if (targetExportProfiles.size == 1) {
                                    "Profile: ${targetExportProfiles.first().name}"
                                } else {
                                    "${targetExportProfiles.size} Profiles"
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
                            if (exportIncludeMedia && mediaCount > 0) "Share Single ZIP (Media + JSON)" else "Share via Apps",
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
                        Text("Save as ZIP Archive (.zip)", fontSize = 12.sp)
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
                        Text("Save as JSON File (.json)", fontSize = 12.sp)
                    }

                    // Copy JSON to clipboard
                    OutlinedButton(
                        onClick = {
                            val json = generateExportPayload()
                            clipboardManager.setText(AnnotatedString(json))
                            Toast.makeText(context, "Copied export JSON to clipboard!", Toast.LENGTH_SHORT).show()
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy JSON to Clipboard", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportOptionsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 5. Import Options Dialog (Open File, Paste, Scan)
    if (showImportOptionsDialog) {
        var isPastingMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showImportOptionsDialog = false },
            title = { Text(if (isPastingMode) "Paste Profile JSON" else "Import Profiles") },
            text = {
                if (isPastingMode) {
                    Column {
                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            placeholder = { Text("[{ \"name\": \"My Profile\", ... }]") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Import profiles from a saved file, clipboard, or phone-to-phone QR scan:",
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
                            Text("Open ZIP Archive or JSON File")
                        }

                        // Paste JSON
                        FilledTonalButton(
                            onClick = { isPastingMode = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Paste from Clipboard")
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
                                    text = "💡 Tip: You can also point your camera directly at another device's Profile QR code to import instantly!",
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
                                Toast.makeText(context, "Invalid JSON data or empty profile bundle.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Review & Import")
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
                    Text("Cancel")
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
                    Text("Import Review & Control")
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
                        text = "Found ${preview.profiles.size} Profile(s)${if (preview.attachments.isNotEmpty()) ", ${preview.attachments.size} Linked Data" else ""}${if (preview.hasMedia) " with embedded media (~${BackupTransferManager.formatBytes(preview.totalMediaBytesEstimate)})" else ""}. Select what to import:",
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
                                text = "SELECT PROFILES (${selectedImportProfileIds.size}/${preview.profiles.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { selectedImportProfileIds = preview.profiles.map { it.id }.toSet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Select All", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { selectedImportProfileIds = emptySet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("None", fontSize = 11.sp)
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
                                                text = "Delimiter: '${p.parsingRules.effectiveDelimiter}' • Formula: ${p.formula.expression.ifBlank { "a+b+c+d" }}",
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
                            text = "MEDIA IMPORT CHOICE",
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
                                        Text("With Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Restore photos & videos to device storage.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        Text("Without Media", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Rules & specs only. Skip saving media files.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            text = "Overwrite existing profile if ID or name matches",
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
                            "Successfully imported ${result.profilesCount} profile(s)${if (result.mediaFilesCount > 0) " & ${result.mediaFilesCount} media file(s)" else ""}!",
                            Toast.LENGTH_LONG
                        ).show()
                        pendingImportPreview = null
                    },
                    enabled = selectedImportProfileIds.isNotEmpty() || preview.attachments.isNotEmpty()
                ) {
                    Text("Import Selected (${selectedImportProfileIds.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportPreview = null }) {
                    Text("Cancel")
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
                            text = "Beam Profile via QR",
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
                                text = "GZIP Compressed: $comp B (${pct}% smaller) • Fast Scan",
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
                                contentDescription = "Compressed Profile QR Code for ${targetProfile.name}",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Text(
                            text = "Unable to generate QR code bitmap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Point another phone running QR Live Calc at this QR code. The camera will automatically detect, decompress, and offer to activate '${targetProfile.name}'.",
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
                            Toast.makeText(context, "Copied compressed QR payload to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Code", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            val json = profileRepository.exportSingleProfileJson(targetProfile.id)
                            shareProfileText("QR Live Calc Profile - ${targetProfile.name}", json)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share JSON", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrDisplayDialog = null }) {
                    Text("Close")
                }
            }
        )
    }
}
