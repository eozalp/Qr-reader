package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ProfileRepository
import com.example.model.QrProfile

enum class SettingsTab(val title: String, val icon: ImageVector) {
    VARIABLES("Değişkenler & Formül", Icons.Default.QrCodeScanner),
    TABLE("Veri Tablosu", Icons.Default.TableChart),
    KEYBOARD("Klavye & PC", Icons.Default.Keyboard),
    OVERLAY("Stil & Görünüm", Icons.Default.Layers),
    TRACKING("Kamera & Barkod", Icons.Default.Tune),
    PROFILES("Profiller", Icons.Default.FolderSpecial)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileRepository: ProfileRepository,
    attachmentRepository: com.example.data.AttachmentRepository? = null,
    onNavigateBack: () -> Unit,
    onOpenRemoteControl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Handle Android system back press
    BackHandler {
        onNavigateBack()
    }

    val activeProfile by profileRepository.activeProfile.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.VARIABLES) }

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Ayarlar",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = activeProfile.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    maxLines = 1
                                )
                            }
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Expressive Segmented Capsule Tab Control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedTab = tab }
                                .testTag("tab_${tab.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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
            }
        }
    }
}
