package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                SettingsTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 11.sp) },
                        modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                    )
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
