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
            reloadFromDisk()
        }
    }

    suspend fun reloadFromDisk() {
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
            name = name.ifBlank { "Yeni Profil" },
            description = description,
            parsingRules = QrParsingRules(),
            formula = ArithmeticFormula("a + b", "a ve b toplamı"),
            formatting = ResultFormatting(prefix = "₺", postfix = " TL", decimalSeparator = ",", thousandsSeparator = "."),
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
            name = "${source.name} (Kopya)"
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

    fun exportSingleProfileJson(profileId: String): String {
        val profile = _profiles.value.find { it.id == profileId } ?: _activeProfile.value
        return profile.toJson().toString(2)
    }

    fun importSingleProfile(profile: QrProfile, activate: Boolean = true): QrProfile {
        val existingIds = _profiles.value.map { it.id }.toSet()
        val targetProfile = if (profile.id in existingIds) {
            profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} (Imported)")
        } else {
            profile
        }
        val currentList = _profiles.value.toMutableList()
        currentList.add(targetProfile)
        _profiles.value = currentList
        if (activate) {
            _activeProfile.value = targetProfile
            persistProfiles(currentList, targetProfile.id)
        } else {
            persistProfiles(currentList)
        }
        return targetProfile
    }

    fun saveAllProfiles(list: List<QrProfile>) {
        if (list.isEmpty()) return
        _profiles.value = list
        val currentActiveId = _activeProfile.value.id
        val newActive = list.find { it.id == currentActiveId } ?: list.first()
        _activeProfile.value = newActive
        persistProfiles(list, newActive.id)
    }

    fun findProfileBySignal(rawText: String): QrProfile? {
        return com.example.engine.QrParser.findMatchingProfileBySignal(rawText, _profiles.value)
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
                // 1. Varsayılan Birincil Profil: İndirim & Net Fiyat (₺ TL)
                QrProfile(
                    id = "preset_turkish_discount",
                    name = "İndirim & Net Fiyat (₺ TL)",
                    description = "Format: 'Fiyat-İndirim%' (örn: 250-20), indirimli net tutarı hesaplar",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "fiyat", alias = "a", prefix = "₺"),
                            VariableDefinition(position = 1, name = "indirim", alias = "b", postfix = "%")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "fiyat * (1 - indirim / 100)",
                        description = "İndirimli net ödenecek tutar"
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
                            headerTitle = "İNDİRİM TABLOSU",
                            headerSubtitle = "₺ FİYATLANDIRMA",
                            gridTheme = ExcelGridTheme.AMBER_GOLD,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "fiyat", label = "Liste Fiyatı", prefix = "₺", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "indirim", label = "İndirim Oranı", postfix = "%", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "NET TUTAR", prefix = "₺", postfix = " TL", coordinate = "A3", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 2. Hızlı Toplam (a-b-c-d)
                QrProfile(
                    id = "preset_default_sum",
                    name = "Hızlı Toplam (a-b-c-d)",
                    description = "'-' ile ayrılmış 4 değeri toplar (a+b+c+d)",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "a"),
                            VariableDefinition(position = 1, name = "b"),
                            VariableDefinition(position = 2, name = "c"),
                            VariableDefinition(position = 3, name = "d")
                        )
                    ),
                    formula = ArithmeticFormula(expression = "a + b + c + d", description = "Dört değerin toplamı"),
                    formatting = ResultFormatting(
                        decimalPrecision = 2,
                        prefix = "",
                        postfix = " adet",
                        decimalSeparator = ",",
                        thousandsSeparator = ".",
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
                            headerTitle = "HIZLI TOPLAM",
                            headerSubtitle = "VERİ TABLOSU",
                            gridTheme = ExcelGridTheme.NEON_CYAN,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "a", label = "Değer A", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "b", label = "Değer B", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "c", label = "Değer C", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "d", label = "Değer D", coordinate = "A4"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "TOPLAM", postfix = " adet", coordinate = "A5", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 3. Fiyat, KDV ve Adet Hesabı
                QrProfile(
                    id = "preset_price_tax_qty",
                    name = "Fiyat, KDV ve Adet Hesabı",
                    description = "Format: 'Fiyat,KDV,Adet' (örn: 50,20,2), KDV dahil toplam tutarı bulur",
                    parsingRules = QrParsingRules(
                        delimiter = ",",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "fiyat", alias = "f", prefix = "₺"),
                            VariableDefinition(position = 1, name = "kdv", alias = "k", postfix = "%", defaultValue = "20"),
                            VariableDefinition(position = 2, name = "adet", alias = "q", postfix = " adet", defaultValue = "1", isOptional = true)
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "(fiyat * adet) * (1 + kdv / 100)",
                        description = "KDV ve adet dahil genel toplam"
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
                        textColorHex = "#10B981",
                        borderColorHex = "#10B981",
                        backgroundColorHex = "#022C22",
                        gradientColorEndHex = "#064E3B",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "tag",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "FİYAT & KDV TABLOSU",
                            headerSubtitle = "FATURA HESAPLAMA",
                            gridTheme = ExcelGridTheme.EXCEL_CLASSIC,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "fiyat", label = "Birim Fiyat", prefix = "₺", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "adet", label = "Miktar", postfix = " adet", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "kdv", label = "KDV Oranı", postfix = "%", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "GENEL TOPLAM", prefix = "₺", postfix = " TL", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 4. Kuyumcu & Has Altın Hesabı
                QrProfile(
                    id = "preset_gold_calc",
                    name = "Kuyumcu & Has Altın Hesabı",
                    description = "Format: 'Gram-Ayar' (örn: 15.5-22), 24 ayar has altın karşılığını hesaplar",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "gram", alias = "g", postfix = " gr"),
                            VariableDefinition(position = 1, name = "ayar", alias = "k", postfix = " K", defaultValue = "22")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "gram * (ayar / 24)",
                        description = "24K Has Altın Gramajı"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 3,
                        prefix = "",
                        postfix = " gr (Has 24K)",
                        decimalSeparator = ",",
                        thousandsSeparator = ".",
                        roundingMode = AppRoundingMode.ROUND_HALF_UP
                    ),
                    overlay = OverlayAppearance(
                        textColorHex = "#FBBF24",
                        borderColorHex = "#F59E0B",
                        backgroundColorHex = "#351502",
                        gradientColorEndHex = "#78350F",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "sparkle",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "HAS ALTIN HESAPLAMA",
                            headerSubtitle = "KUYUMCU HESAP TABLOSU",
                            gridTheme = ExcelGridTheme.AMBER_GOLD,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "gram", label = "Tartılan Gram", postfix = " gr", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "ayar", label = "Bilezik/Takı Ayarı", postfix = " K", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "HAS KARŞILIĞI", postfix = " gr", coordinate = "A3", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 5. Koli & Paket Hacmi (E-B-Y cm -> Litre)
                QrProfile(
                    id = "preset_package_volume",
                    name = "Koli & Paket Hacmi (E-B-Y)",
                    description = "Format: 'Boy-En-Yükseklik' (cm cinsinden), Litre hacmini hesaplar",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "boy", postfix = " cm"),
                            VariableDefinition(position = 1, name = "en", postfix = " cm"),
                            VariableDefinition(position = 2, name = "yukseklik", postfix = " cm")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "(boy * en * yukseklik) / 1000",
                        description = "Litre cinsinden kübik koli hacmi"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 1,
                        postfix = " Litre",
                        decimalSeparator = ",",
                        thousandsSeparator = ".",
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
                            headerTitle = "KOLİ HACİM TABLOSU",
                            headerSubtitle = "METRİK HESAPLAMA",
                            gridTheme = ExcelGridTheme.MODERN_SLATE,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "boy", label = "Boy", postfix = " cm", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "en", label = "En", postfix = " cm", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "yukseklik", label = "Yükseklik", postfix = " cm", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "HACİM (LİTRE)", postfix = " L", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 6. Birim Fiyat & Gramaj (Fiyat/Gram)
                QrProfile(
                    id = "preset_price_per_gram",
                    name = "Birim Fiyat & Gramaj (Fiyat/Gram)",
                    description = "Format: 'Fiyat-Gram' (örn: 100-250), gram başına birim fiyatı bulur",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "fiyat", prefix = "₺", defaultValue = "100"),
                            VariableDefinition(position = 1, name = "gram", postfix = " gr", defaultValue = "250"),
                            VariableDefinition(
                                name = "b_fiyat",
                                isComputed = true,
                                computationFormula = "fiyat / gram",
                                prefix = "₺",
                                postfix = "/gr"
                            )
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "fiyat / gram",
                        description = "Gram başına düşen birim fiyat"
                    ),
                    formatting = ResultFormatting(
                        decimalPrecision = 3,
                        prefix = "₺",
                        postfix = " / gr",
                        decimalSeparator = ",",
                        thousandsSeparator = ".",
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
                            headerTitle = "BİRİM FİYAT TABLOSU",
                            headerSubtitle = "FİYAT / GRAM",
                            gridTheme = ExcelGridTheme.EXCEL_CLASSIC,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "fiyat", label = "Paket Fiyatı", prefix = "₺", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "gram", label = "Net Ağırlık", postfix = " gr", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.COMPUTED, variableName = "b_fiyat", label = "Birim Fiyat", prefix = "₺", postfix = "/gr", coordinate = "A3", isEmphasized = true),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "BİRİM ORAN", prefix = "₺", postfix = "/gr", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 7. Enflasyon & Tarihli Maliyet (Müşteri Fiyat Ekranı)
                QrProfile(
                    id = "preset_inflation_date",
                    name = "Enflasyon & Tarihli Fiyat",
                    description = "Format: 'SKU-Maliyet-Tarih' (örn: SKU01-1000-20250115), aylık enflasyonla güncel satış fiyatını bulur",
                    triggerSignal = "SKU",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "sku", alias = "kod", dataType = FieldDataType.STRING, defaultValue = "SKU01"),
                            VariableDefinition(position = 1, name = "maliyet", alias = "cost", dataType = FieldDataType.DOUBLE, prefix = "₺", defaultValue = "1000"),
                            VariableDefinition(position = 2, name = "tarih", alias = "date", dataType = FieldDataType.DATE, defaultValue = "20250115"),
                            VariableDefinition(position = -1, name = "aylik_enf", dataType = FieldDataType.DOUBLE, isManual = true, defaultValue = "3.5", postfix = "%"),
                            VariableDefinition(position = -1, name = "kar", dataType = FieldDataType.DOUBLE, isManual = true, defaultValue = "20", postfix = "%")
                        )
                    ),
                    formula = ArithmeticFormula(
                        expression = "maliyet * (1 + aylik_enf / 100) ^ max(0, ay_farki(bugun, tarih)) * (1 + kar / 100)",
                        description = "Operatör ve tarih bileşenleri ile bileşik fiyat formülü"
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
                        textColorHex = "#00F5D4",
                        borderColorHex = "#00F5D4",
                        backgroundColorHex = "#071324",
                        gradientColorEndHex = "#0B2942",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "sparkle",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        customerPriceOnly = true,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "GÜNCEL SATIŞ FİYATI",
                            headerSubtitle = "ENFLASYON HESAPLAMA",
                            gridTheme = ExcelGridTheme.NEON_CYAN,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "sku", label = "Ürün Kodu", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "tarih", label = "Alış Tarihi", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "maliyet", label = "Baz Maliyet", prefix = "₺", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.FORMULA_RESULT, label = "GÜNCEL FİYAT", prefix = "₺", postfix = " TL", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    ),
                    bluetoothKeyboard = BluetoothKeyboardConfig(
                        sendTarget = KeyboardSendTarget.MULTIPLE_VARIABLES,
                        selectedVariables = listOf("sku", "maliyet"),
                        variableSeparator = "\t",
                        endingKey = KeyboardEndingKey.ENTER
                    )
                )
            )
        }
    }
}
