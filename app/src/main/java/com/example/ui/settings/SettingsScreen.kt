package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ProfileRepository
import com.example.model.QrProfile

enum class SettingsTab(val title: String, val icon: ImageVector) {
    PARSING("Parsing", Icons.Default.QrCodeScanner),
    FORMULA("Formula", Icons.Default.Calculate),
    TABLE("Table", Icons.Default.TableChart),
    OVERLAY("Style", Icons.Default.Layers),
    FORMAT("Format", Icons.Default.FormatListNumbered),
    TRACKING("Tracking", Icons.Default.Tune),
    PROFILES("Profiles", Icons.Default.FolderSpecial)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileRepository: ProfileRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Handle Android system back press
    BackHandler {
        onNavigateBack()
    }

    val activeProfile by profileRepository.activeProfile.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.PARSING) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Active: ${activeProfile.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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
                                contentDescription = "Back to Scanner"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    },
                    indicator = { tabPositions ->
                        if (selectedTab.ordinal < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                                color = MaterialTheme.colorScheme.primary,
                                height = 3.dp
                            )
                        }
                    }
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                SettingsTab.PARSING -> {
                    ParsingSettingsSection(
                        rules = activeProfile.parsingRules,
                        onRulesChanged = { updatedRules ->
                            profileRepository.saveProfile(activeProfile.copy(parsingRules = updatedRules))
                        },
                        triggerSignal = activeProfile.triggerSignal,
                        onTriggerSignalChanged = { updatedSignal ->
                            profileRepository.saveProfile(activeProfile.copy(triggerSignal = updatedSignal))
                        }
                    )
                }
                SettingsTab.FORMULA -> {
                    FormulaSettingsSection(
                        formula = activeProfile.formula,
                        rules = activeProfile.parsingRules,
                        onFormulaChanged = { updatedFormula ->
                            profileRepository.saveProfile(activeProfile.copy(formula = updatedFormula))
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
                SettingsTab.FORMAT -> {
                    FormattingSettingsSection(
                        formatting = activeProfile.formatting,
                        onFormattingChanged = { updatedFormat ->
                            profileRepository.saveProfile(activeProfile.copy(formatting = updatedFormat))
                        }
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
                        profileRepository = profileRepository
                    )
                }
            }
        }
    }
}
