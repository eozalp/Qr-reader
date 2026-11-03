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
import com.example.data.ProfileRepository
import com.example.engine.QrCodeGenerator
import com.example.engine.QrParser
import com.example.model.QrProfile
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSection(
    profileRepository: ProfileRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val activeProfile by profileRepository.activeProfile.collectAsState()
    val profiles by profileRepository.profiles.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<QrProfile?>(null) }
    var showSignalDialog by remember { mutableStateOf<QrProfile?>(null) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var showImportOptionsDialog by remember { mutableStateOf(false) }
    var showQrDisplayDialog by remember { mutableStateOf<QrProfile?>(null) }

    var newProfileName by remember { mutableStateOf("") }
    var newProfileDesc by remember { mutableStateOf("") }
    var newProfileSignal by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var signalEditText by remember { mutableStateOf("") }
    var importJsonText by remember { mutableStateOf("") }

    // State for pending file export payload
    var pendingExportJson by remember { mutableStateOf("") }
    var pendingExportFileName by remember { mutableStateOf("qr_profiles.json") }

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

    // SAF OpenDocument for importing JSON file
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            stringBuilder.append(line).append("\n")
                            line = reader.readLine()
                        }
                    }
                }
                val content = stringBuilder.toString()
                val result = profileRepository.importProfilesJson(content)
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 1
                    Toast.makeText(context, "Imported $count profile(s) from file!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid JSON: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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
                            onClick = { showExportOptionsDialog = true },
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

                        // Share Profile button
                        IconButton(
                            onClick = {
                                val json = profileRepository.exportSingleProfileJson(profile.id)
                                shareProfileText("QR Live Calc Profile - ${profile.name}", json)
                            }
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share Profile",
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

    // 4. Export Options Dialog (File, Share App, Clipboard)
    if (showExportOptionsDialog) {
        val allProfilesJson = remember { profileRepository.exportProfilesJson() }
        val activeProfileJson = remember { profileRepository.exportSingleProfileJson(activeProfile.id) }

        AlertDialog(
            onDismissRequest = { showExportOptionsDialog = false },
            title = { Text("Export Profiles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose how you want to export your profiles:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Export all to file
                    FilledTonalButton(
                        onClick = {
                            pendingExportJson = allProfilesJson
                            pendingExportFileName = "all_qr_profiles.json"
                            exportFileLauncher.launch("all_qr_profiles.json")
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_all_file_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save All Profiles (.json)", maxLines = 1, softWrap = false)
                    }

                    // Save active profile to file
                    FilledTonalButton(
                        onClick = {
                            pendingExportJson = activeProfileJson
                            val safeName = activeProfile.name.lowercase().replace("\\s+".toRegex(), "_")
                            pendingExportFileName = "profile_$safeName.json"
                            exportFileLauncher.launch("profile_$safeName.json")
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        val displayName = if (activeProfile.name.length > 16) "${activeProfile.name.take(14)}…" else activeProfile.name
                        Text("Save Active '$displayName' (.json)", maxLines = 1, softWrap = false)
                    }

                    // Share via Share Sheet (other apps)
                    Button(
                        onClick = {
                            shareProfileText("QR Live Calc Profiles", allProfilesJson)
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("share_profiles_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share via Apps (WhatsApp, Mail)", maxLines = 1, softWrap = false)
                    }

                    // Copy to clipboard
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(allProfilesJson))
                            Toast.makeText(context, "All profiles copied to clipboard", Toast.LENGTH_SHORT).show()
                            showExportOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy JSON to Clipboard", maxLines = 1, softWrap = false)
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

                        // Open Document from device
                        Button(
                            onClick = {
                                importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                showImportOptionsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().testTag("import_file_btn")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open JSON File from Storage")
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
                            val result = profileRepository.importProfilesJson(importJsonText)
                            if (result.isSuccess) {
                                val count = result.getOrNull() ?: 1
                                Toast.makeText(context, "Imported $count profile(s) successfully!", Toast.LENGTH_SHORT).show()
                                showImportOptionsDialog = false
                            } else {
                                Toast.makeText(context, "Import failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text("Import")
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

    // 6. QR Code Display Dialog (Phone-to-Phone Beam)
    showQrDisplayDialog?.let { targetProfile ->
        val qrString = remember(targetProfile.id) {
            QrParser.encodeProfileToQrString(targetProfile)
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
                    Text(
                        text = "Scan to Import Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
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
                                contentDescription = "Profile QR Code for ${targetProfile.name}",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Text(
                            text = "Profile is too large to fit in a single QR code. Use File Export or Share Sheet below instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Open QR Live Calc on any other phone and point its camera at this QR code to beam and activate this profile instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        val json = profileRepository.exportSingleProfileJson(targetProfile.id)
                        shareProfileText("QR Live Calc Profile - ${targetProfile.name}", json)
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Profile")
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
