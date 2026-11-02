package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "qr_calculator_prefs")

class ProfileRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val KEY_PROFILES = stringPreferencesKey("saved_profiles_json")
    private val KEY_ACTIVE_ID = stringPreferencesKey("active_profile_id")

    private val _profiles = MutableStateFlow<List<QrProfile>>(emptyList())
    val profiles: StateFlow<List<QrProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(createDefaultPresets().first())
    val activeProfile: StateFlow<QrProfile> = _activeProfile.asStateFlow()

    init {
        scope.launch {
            loadInitialProfiles()
        }
    }

    private suspend fun loadInitialProfiles() {
        val prefs = context.dataStore.data.first()
        val jsonStr = prefs[KEY_PROFILES]
        val activeId = prefs[KEY_ACTIVE_ID]

        val loaded = if (!jsonStr.isNullOrBlank()) {
            parseProfilesJson(jsonStr)
        } else {
            createDefaultPresets()
        }

        val listToUse = if (loaded.isNotEmpty()) loaded else createDefaultPresets()
        _profiles.value = listToUse

        val active = listToUse.find { it.id == activeId } ?: listToUse.first()
        _activeProfile.value = active
    }

    fun selectProfile(id: String) {
        val target = _profiles.value.find { it.id == id } ?: return
        _activeProfile.value = target
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[KEY_ACTIVE_ID] = id
            }
        }
    }

    fun saveProfile(profile: QrProfile) {
        val currentList = _profiles.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            currentList[index] = profile
        } else {
            currentList.add(profile)
        }
        _profiles.value = currentList
        if (_activeProfile.value.id == profile.id) {
            _activeProfile.value = profile
        }
        persistProfiles(currentList)
    }

    fun createProfile(name: String, description: String = ""): QrProfile {
        val newProfile = QrProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "New Profile" },
            description = description,
            parsingRules = QrParsingRules(),
            formula = ArithmeticFormula("a + b", "Sum of a and b"),
            formatting = ResultFormatting(),
            overlay = OverlayAppearance(),
            tracking = TrackingSettings()
        )
        val updated = _profiles.value + newProfile
        _profiles.value = updated
        _activeProfile.value = newProfile
        persistProfiles(updated, newProfile.id)
        return newProfile
    }

    fun duplicateProfile(profileId: String): QrProfile? {
        val source = _profiles.value.find { it.id == profileId } ?: return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} (Copy)"
        )
        val updated = _profiles.value + copy
        _profiles.value = updated
        persistProfiles(updated)
        return copy
    }

    fun deleteProfile(profileId: String) {
        if (_profiles.value.size <= 1) return // Keep at least one profile
        val updated = _profiles.value.filterNot { it.id == profileId }
        _profiles.value = updated
        if (_activeProfile.value.id == profileId) {
            val nextActive = updated.first()
            _activeProfile.value = nextActive
            persistProfiles(updated, nextActive.id)
        } else {
            persistProfiles(updated)
        }
    }

    fun resetToDefaults() {
        val presets = createDefaultPresets()
        _profiles.value = presets
        _activeProfile.value = presets.first()
        persistProfiles(presets, presets.first().id)
    }

    fun exportProfilesJson(): String {
        val array = JSONArray()
        _profiles.value.forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    fun importProfilesJson(jsonStr: String): Result<Int> {
        return runCatching {
            val list = parseProfilesJson(jsonStr)
            if (list.isEmpty()) throw IllegalArgumentException("No valid profiles found in JSON")

            // Merge with existing
            val existingIds = _profiles.value.map { it.id }.toSet()
            val merged = _profiles.value.toMutableList()
            var importedCount = 0
            for (p in list) {
                if (p.id in existingIds) {
                    val newP = p.copy(id = UUID.randomUUID().toString(), name = "${p.name} (Imported)")
                    merged.add(newP)
                } else {
                    merged.add(p)
                }
                importedCount++
            }
            _profiles.value = merged
            persistProfiles(merged)
            importedCount
        }
    }

    private fun persistProfiles(list: List<QrProfile>, newActiveId: String? = null) {
        scope.launch {
            context.dataStore.edit { prefs ->
                val array = JSONArray()
                list.forEach { array.put(it.toJson()) }
                prefs[KEY_PROFILES] = array.toString()
                if (newActiveId != null) {
                    prefs[KEY_ACTIVE_ID] = newActiveId
                }
            }
        }
    }

    private fun parseProfilesJson(jsonStr: String): List<QrProfile> {
        val result = mutableListOf<QrProfile>()
        try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        result.add(QrProfile.fromJson(obj))
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                result.add(QrProfile.fromJson(obj))
            }
        } catch (_: Exception) {}
        return result
    }

    companion object {
        fun createDefaultPresets(): List<QrProfile> {
            return listOf(
                QrProfile(
                    id = "preset_default_sum",
                    name = "Quick Sum (a-b-c-d)",
                    description = "Splits by '-' and calculates a+b+c+d",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "a"),
                            VariableDefinition(position = 1, name = "b"),
                            VariableDefinition(position = 2, name = "c"),
                            VariableDefinition(position = 3, name = "d")
                        )
                    ),
                    formula = ArithmeticFormula(expression = "a + b + c + d", description = "Sum of four tokens"),
                    formatting = ResultFormatting(
                        decimalPrecision = 2,
                        prefix = "",
                        postfix = " pts",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#00F5D4",
                        borderColorHex = "#00F5D4",
                        backgroundColorHex = "#090D16",
                        fillMode = OverlayFillMode.SEMI_TRANSPARENT,
                        iconName = "calculator",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "QUICK SUM",
                            headerSubtitle = "DATA SHEET",
                            gridTheme = ExcelGridTheme.NEON_CYAN,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "a", label = "Token A", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "b", label = "Token B", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "c", label = "Token C", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "d", label = "Token D", coordinate = "A4"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "TOTAL", postfix = " pts", coordinate = "A5", isEmphasized = true)
                            )
                        )
                    )
                ),
                QrProfile(
                    id = "preset_price_tax_qty",
                    name = "Price & Tax Calculator",
                    description = "Format: 'Price,TaxRate,Quantity' (e.g., 49.99,18,2)",
                    parsingRules = QrParsingRules(
                        delimiter = ",",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "price", alias = "p", prefix = "$"),
                            VariableDefinition(position = 1, name = "tax", alias = "t", postfix = "%", defaultValue = "18"),
                            VariableDefinition(position = 2, name = "qty", alias = "q", postfix = " pcs", defaultValue = "1", isOptional = true)
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "(price * qty) * (1 + tax / 100)",
                        description = "Total cost including percentage tax"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 2,
                        prefix = "$",
                        postfix = "",
                        thousandsSeparator = ",",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#10B981",
                        borderColorHex = "#10B981",
                        backgroundColorHex = "#022C22",
                        gradientColorEndHex = "#064E3B",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "tag",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "PRICE & TAX SHEET",
                            headerSubtitle = "INVOICE CALC",
                            gridTheme = ExcelGridTheme.EXCEL_CLASSIC,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "price", label = "Unit Price", prefix = "$", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "qty", label = "Quantity", postfix = " pcs", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "tax", label = "Tax Rate", postfix = "%", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "TOTAL DUE", prefix = "$", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                QrProfile(
                    id = "preset_turkish_discount",
                    name = "Discount Calculator (₺ TL)",
                    description = "Format: 'Price-Discount%' (e.g., 250-20)",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "price", alias = "a", prefix = "₺"),
                            VariableDefinition(position = 1, name = "disc", alias = "b", postfix = "%")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "price * (1 - disc / 100)",
                        description = "Discounted final price"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 2,
                        prefix = "₺",
                        postfix = " TL",
                        decimalSeparator = ",",
                        thousandsSeparator = ".",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#F59E0B",
                        borderColorHex = "#F59E0B",
                        backgroundColorHex = "#451A03",
                        gradientColorEndHex = "#1E1B4B",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "sparkle",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "DISCOUNT BREAKDOWN",
                            headerSubtitle = "₺ PRICING",
                            gridTheme = ExcelGridTheme.AMBER_GOLD,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "price", label = "Original Price", prefix = "₺", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "disc", label = "Discount %", postfix = "%", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "FINAL PRICE", prefix = "₺", postfix = " TL", coordinate = "A3", isEmphasized = true)
                            )
                        )
                    )
                ),
                QrProfile(
                    id = "preset_package_volume",
                    name = "Volume & Dimensions (L-W-H)",
                    description = "Format: 'Length-Width-Height' in cm, calculates Liters",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "l", postfix = "cm"),
                            VariableDefinition(position = 1, name = "w", postfix = "cm"),
                            VariableDefinition(position = 2, name = "h", postfix = "cm")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "(l * w * h) / 1000",
                        description = "Cubic volume in Liters"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 1,
                        postfix = " L",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#A855F7",
                        borderColorHex = "#A855F7",
                        backgroundColorHex = "#2E1065",
                        fillMode = OverlayFillMode.SEMI_TRANSPARENT,
                        iconName = "qr",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "PACKAGE VOLUME",
                            headerSubtitle = "METRIC SPREADSHEET",
                            gridTheme = ExcelGridTheme.MODERN_SLATE,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "l", label = "Length", postfix = " cm", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "w", label = "Width", postfix = " cm", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "h", label = "Height", postfix = " cm", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "VOLUME (L)", postfix = " L", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                QrProfile(
                    id = "preset_price_per_gram",
                    name = "Price per Gram (Unit Price)",
                    description = "Format: 'Price-Weight' (e.g., 50-250) with computed price per gram",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "price", prefix = "$", defaultValue = "50"),
                            VariableDefinition(position = 1, name = "grams", postfix = "g", defaultValue = "250"),
                            VariableDefinition(
                                name = "ppg",
                                isComputed = true,
                                computationFormula = "price / grams",
                                prefix = "$",
                                postfix = "/g"
                            )
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "price / grams",
                        description = "Unit price per gram"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 4,
                        prefix = "$",
                        postfix = " / g",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#38BDF8",
                        borderColorHex = "#38BDF8",
                        backgroundColorHex = "#0C4A6E",
                        fillMode = OverlayFillMode.SEMI_TRANSPARENT,
                        iconName = "calculator",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "UNIT PRICE SHEET",
                            headerSubtitle = "EXCEL SPREADSHEET",
                            gridTheme = ExcelGridTheme.EXCEL_CLASSIC,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "price", label = "Total Price", prefix = "$", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "grams", label = "Weight", postfix = " g", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.COMPUTED, variableName = "ppg", label = "Price / Gram", prefix = "$", postfix = "/g", coordinate = "A3", isEmphasized = true),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "RATE", prefix = "$", postfix = "/g", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                )
            )
        }
    }
}
