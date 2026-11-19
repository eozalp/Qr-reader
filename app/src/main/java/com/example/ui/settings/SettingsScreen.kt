package com.example.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.data.ProfileRepository
import com.example.debug.AppDebugLogger
import com.example.debug.DebugLogLevel

enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
    val isAdvanced: Boolean = false
) {
    PROFILES("Profiller", Icons.Default.FolderSpecial, isAdvanced = false),
    VARIABLES("Değişkenler & Formül", Icons.Default.QrCodeScanner, isAdvanced = false),
    KEYBOARD("Klavye & PC", Icons.Default.Keyboard, isAdvanced = false),
    TABLE("Veri Tablosu", Icons.Default.TableChart, isAdvanced = true),
    OVERLAY("Stil & Görünüm", Icons.Default.Layers, isAdvanced = true),
    TRACKING("Kamera & Barkod", Icons.Default.Tune, isAdvanced = true),
    DEBUG("Debug Konsolu", Icons.Default.BugReport, isAdvanced = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileRepository: ProfileRepository,
    attachmentRepository: com.example.data.AttachmentRepository? = null,
    hidManager: BluetoothHidManager? = null,
    onRequestBluetoothPermission: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    onOpenRemoteControl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val activeProfile by profileRepository.activeProfile.collectAsState()
    val debugLogs by AppDebugLogger.logs.collectAsState()
    val lastCrashReport by AppDebugLogger.lastCrashReport.collectAsState()
    val debugErrorCount = remember(debugLogs, lastCrashReport) {
        debugLogs.count { it.level.severity >= DebugLogLevel.ERROR.severity } +
            if (!lastCrashReport.isNullOrBlank()) 1 else 0
    }

    // Progressive disclosure: Basic mode shows 3 everyday tabs; Expert reveals all 7
    var showExpertTabs by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.VARIABLES) }
    var showWizardDialog by remember { mutableStateOf(false) }

    val visibleTabs = remember(showExpertTabs) {
        if (showExpertTabs) SettingsTab.entries else SettingsTab.entries.filter { !it.isAdvanced }
    }

    LaunchedEffect(showExpertTabs) {
        if (!showExpertTabs && selectedTab.isAdvanced) {
            selectedTab = SettingsTab.VARIABLES
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .fillMaxWidth()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Ayarlar",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = activeProfile.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kameraya Dön"
                            )
                        }
                    },
                    actions = {
                        // 1-Tap Smart Tag Wizard Button
                        FilledTonalButton(
                            onClick = { showWizardDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("open_smart_wizard_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sihirbaz", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Temel / Uzman Progressive Disclosure Toggle
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (showExpertTabs) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    showExpertTabs -> MaterialTheme.colorScheme.primary
                                    debugErrorCount > 0 -> Color(0xFFEF4444).copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { showExpertTabs = !showExpertTabs }
                                .testTag("settings_expert_toggle_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = if (showExpertTabs) "Uzman ✓" else "Uzman",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showExpertTabs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!showExpertTabs && debugErrorCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Expressive Segmented Capsule Tab Control (48dp min touch target)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleTabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        val showDebugBadge = tab == SettingsTab.DEBUG && debugErrorCount > 0
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    showDebugBadge -> Color(0xFFEF4444).copy(alpha = 0.55f)
                                    else -> Color.Transparent
                                }
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { selectedTab = tab }
                                .testTag("tab_${tab.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (showDebugBadge) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                SettingsTab.VARIABLES -> {
                    ParsingSettingsSection(
                        rules = activeProfile.parsingRules,
                        onRulesChanged = { updatedRules ->
                            profileRepository.saveProfile(activeProfile.copy(parsingRules = updatedRules))
                        },
                        formula = activeProfile.formula,
                        onFormulaChanged = { updatedFormula ->
                            profileRepository.saveProfile(activeProfile.copy(formula = updatedFormula))
                        },
                        formatting = activeProfile.formatting,
                        onFormattingChanged = { updatedFormat ->
                            profileRepository.saveProfile(activeProfile.copy(formatting = updatedFormat))
                        },
                        triggerSignal = activeProfile.triggerSignal,
                        onTriggerSignalChanged = { updatedSignal ->
                            profileRepository.saveProfile(activeProfile.copy(triggerSignal = updatedSignal))
                        }
                    )
                }
                SettingsTab.TABLE -> {
                    TableSettingsSection(
                        profile = activeProfile,
                        onProfileChanged = { updatedProfile ->
                            profileRepository.saveProfile(updatedProfile)
                        }
                    )
                }
                SettingsTab.KEYBOARD -> {
                    BluetoothKeyboardSection(
                        config = activeProfile.bluetoothKeyboard,
                        onConfigChanged = { updatedKb ->
                            profileRepository.saveProfile(activeProfile.copy(bluetoothKeyboard = updatedKb))
                        },
                        availableVariables = activeProfile.parsingRules.variables,
                        hidManager = hidManager,
                        onRequestBluetoothPermission = onRequestBluetoothPermission,
                        onOpenRemoteControl = onOpenRemoteControl
                    )
                }
                SettingsTab.OVERLAY -> {
                    OverlayAppearanceSection(
                        overlay = activeProfile.overlay,
                        definedVariables = activeProfile.parsingRules.variables,
                        onOverlayChanged = { updatedOverlay ->
                            profileRepository.saveProfile(activeProfile.copy(overlay = updatedOverlay))
                        }
                    )
                }
                SettingsTab.TRACKING -> {
                    TrackingCameraSection(
                        tracking = activeProfile.tracking,
                        onTrackingChanged = { updatedTracking ->
                            profileRepository.saveProfile(activeProfile.copy(tracking = updatedTracking))
                        }
                    )
                }
                SettingsTab.PROFILES -> {
                    ProfilesSection(
                        profileRepository = profileRepository,
                        attachmentRepository = attachmentRepository
                    )
                }
                SettingsTab.DEBUG -> {
                    DebugConsoleSection(hidManager = hidManager)
                }
            }
        }
    }

    if (showWizardDialog) {
        QuickProfileWizardDialog(
            onCreateProfile = { created ->
                profileRepository.importSingleProfile(created, activate = true)
                showWizardDialog = false
                selectedTab = SettingsTab.VARIABLES
                Toast.makeText(
                    context,
                    "'${created.name}' oluşturuldu ve aktifleştirildi!",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showWizardDialog = false }
        )
    }
}
