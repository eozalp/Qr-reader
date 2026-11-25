package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.debug.AppDebugLogger
import com.example.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "qr_calculator_prefs")

class ProfileRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + AppDebugLogger.coroutineHandler("DATA-PROFILE"))
    private val ioMutex = Mutex()
    @Volatile
    private var initialLoadComplete = false

    private val KEY_PROFILES = stringPreferencesKey("saved_profiles_json")
    private val KEY_ACTIVE_ID = stringPreferencesKey("active_profile_id")
    private val KEY_GLOBAL_VARS = stringPreferencesKey("global_variables_json")

    private val _profiles = MutableStateFlow<List<QrProfile>>(emptyList())
    val profiles: StateFlow<List<QrProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(createDefaultPresets().first())
    val activeProfile: StateFlow<QrProfile> = _activeProfile.asStateFlow()

    private val _globalVariables = MutableStateFlow(createDefaultGlobalVariables())
    val globalVariables: StateFlow<List<GlobalVariable>> = _globalVariables.asStateFlow()

    private val initialLoadJob = scope.launch {
        reloadFromDisk()
    }

    suspend fun reloadFromDisk() {
        ioMutex.withLock {
            loadFromDiskLocked()
        }
    }

    private suspend fun loadFromDiskLocked() {
        runCatching {
            val prefs = context.dataStore.data.first()
            val jsonStr = prefs[KEY_PROFILES]
            val activeId = prefs[KEY_ACTIVE_ID]
            val globalsJson = prefs[KEY_GLOBAL_VARS]

            val loaded = if (!jsonStr.isNullOrBlank()) {
                parseProfilesJson(jsonStr)
            } else {
                createDefaultPresets()
            }

            val listToUse = if (loaded.isNotEmpty()) loaded else createDefaultPresets()
            _profiles.value = listToUse

            val active = listToUse.find { it.id == activeId } ?: listToUse.first()
            _activeProfile.value = active

            if (!globalsJson.isNullOrBlank()) {
                _globalVariables.value = parseGlobalsJson(globalsJson)
            }
            initialLoadComplete = true
        }.onFailure { err ->
            AppDebugLogger.e("DATA-PROFILE", "Profiller diskten yüklenemedi", err)
            if (_profiles.value.isEmpty()) {
                val defaults = createDefaultPresets()
                _profiles.value = defaults
                _activeProfile.value = defaults.first()
            }
            initialLoadComplete = true
        }
    }

    fun saveGlobalVariables(list: List<GlobalVariable>) {
        _globalVariables.value = list
        scope.launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    val array = JSONArray()
                    list.forEach { array.put(it.toJson()) }
                    prefs[KEY_GLOBAL_VARS] = array.toString()
                }
            }.onFailure { err ->
                AppDebugLogger.e("DATA-PROFILE", "Global değişkenler kaydedilemedi", err)
            }
        }
    }

    private fun parseGlobalsJson(jsonStr: String): List<GlobalVariable> {
        val result = mutableListOf<GlobalVariable>()
        runCatching {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result.add(GlobalVariable.fromJson(it)) }
            }
        }.onFailure { err ->
            AppDebugLogger.e("DATA-PROFILE", "Global değişken JSON hatası", err)
        }
        return result
    }

    fun selectProfile(id: String) {
        val target = _profiles.value.find { it.id == id } ?: return
        _activeProfile.value = target
        scope.launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    prefs[KEY_ACTIVE_ID] = id
                }
            }.onFailure { err ->
                AppDebugLogger.e("DATA-PROFILE", "Aktif profil ID kaydedilemedi", err)
            }
        }
    }

    fun saveProfile(profile: QrProfile) {
        if (!initialLoadComplete) {
            // Defer merge until initial disk load completes so saved profiles are never overwritten
            if (_activeProfile.value.id == profile.id) {
                _activeProfile.value = profile
            }
            scope.launch {
                initialLoadJob.join()
                ioMutex.withLock {
                    val currentList = _profiles.value.toMutableList()
                    val index = currentList.indexOfFirst { it.id == profile.id }
                    if (index != -1) currentList[index] = profile else currentList.add(profile)
                    _profiles.value = currentList
                    if (_activeProfile.value.id == profile.id) {
                        _activeProfile.value = profile
                    }
                    persistProfilesLocked(currentList, null)
                }
            }
            return
        }
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
            parsingRules = QrParsingRules(
                delimiter = "-",
                variables = listOf(
                    VariableDefinition(position = 0, name = "alan1", alias = "Alan 1", dataType = FieldDataType.STRING),
                    VariableDefinition(position = 1, name = "alan2", alias = "Alan 2", dataType = FieldDataType.DOUBLE)
                )
            ),
            formula = ArithmeticFormula("", ""),
            formatting = ResultFormatting(prefix = "", postfix = "", decimalSeparator = ",", thousandsSeparator = "."),
            overlay = OverlayAppearance(
                displayMode = OverlayDisplayMode.EXCEL_TABLE,
                tableConfig = ExcelTableConfig(
                    headerTitle = name.ifBlank { "YENİ PROFİL" }.uppercase(Locale("tr", "TR")),
                    items = emptyList() // Auto-synced directly from user's visible variables!
                )
            ),
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
            initialLoadJob.join()
            ioMutex.withLock {
                persistProfilesLocked(list, newActiveId)
            }
        }
    }

    private suspend fun persistProfilesLocked(list: List<QrProfile>, newActiveId: String?) {
        runCatching {
            context.dataStore.edit { prefs ->
                val array = JSONArray()
                list.forEach { array.put(it.toJson()) }
                prefs[KEY_PROFILES] = array.toString()
                if (newActiveId != null) {
                    prefs[KEY_ACTIVE_ID] = newActiveId
                }
            }
        }.onFailure { err ->
            AppDebugLogger.e("DATA-PROFILE", "Profiller diske kaydedilemedi", err)
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
        } catch (e: Exception) {
            AppDebugLogger.e("DATA-PROFILE", "Profil JSON ayrıştırma hatası", e)
        }
        return result
    }

    companion object {
        @Volatile
        private var instance: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
        }

        fun createDefaultGlobalVariables(): List<GlobalVariable> = listOf(
            GlobalVariable(id = "global_kdv", name = "kdv", value = "20", dataType = FieldDataType.DOUBLE, description = "Genel KDV Oranı (%)"),
            GlobalVariable(id = "global_benzin", name = "benzin_lt", value = "44.50", dataType = FieldDataType.DOUBLE, description = "Benzin Litre Fiyatı (₺)"),
            GlobalVariable(id = "global_motorin", name = "motorin_lt", value = "43.20", dataType = FieldDataType.DOUBLE, description = "Motorin Litre Fiyatı (₺)"),
            GlobalVariable(id = "global_lpg", name = "lpg_lt", value = "25.80", dataType = FieldDataType.DOUBLE, description = "LPG Litre Fiyatı (₺)"),
            GlobalVariable(id = "global_dolar", name = "dolar", value = "38.50", dataType = FieldDataType.DOUBLE, description = "Güncel USD Kuru (₺)")
        )

        fun createDefaultPresets(): List<QrProfile> {
            return listOf(
                // 1. Depo & Stok Sayım (Depocu / Lojistik — Metin Karar Çıktılı!)
                QrProfile(
                    id = "preset_warehouse_stock",
                    name = "Depo & Stok Sayım",
                    description = "Format: 'SKU-RAF-ADET' (örn: KOLI01-A12-4), kritik stok uyarısını otomatik üretir",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "sku", alias = "Ürün Kodu", dataType = FieldDataType.STRING, defaultValue = "KOLI01"),
                            VariableDefinition(position = 1, name = "raf", alias = "Raf", dataType = FieldDataType.STRING, defaultValue = "A12"),
                            VariableDefinition(position = 2, name = "adet", alias = "Stok Adedi", dataType = FieldDataType.INTEGER, postfix = " ad", defaultValue = "4"),
                            VariableDefinition(position = -1, name = "min_stok", alias = "Kritik Eşik", dataType = FieldDataType.INTEGER, isManual = true, defaultValue = "5", showPill = false),
                            VariableDefinition(
                                name = "durum",
                                alias = "Stok Durumu",
                                dataType = FieldDataType.STRING,
                                isComputed = true,
                                isPrimaryOutput = true,
                                computationFormula = "if(adet < min_stok, \"KRİTİK STOK\", \"YETERLİ\")"
                            )
                        )
                    ),
                    formula = ArithmeticFormula("", ""),
                    formatting = ResultFormatting(decimalPrecision = 0, decimalSeparator = ",", thousandsSeparator = "."),
                    overlay = OverlayAppearance(
                        textColorHex = "#00F5D4",
                        borderColorHex = "#00F5D4",
                        backgroundColorHex = "#071324",
                        gradientColorEndHex = "#0B2942",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "qr",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "DEPO STOK KARTI",
                            headerSubtitle = "LOJİSTİK & SAYIM",
                            gridTheme = ExcelGridTheme.NEON_CYAN,
                            items = listOf(
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "sku", label = "Ürün Kodu", coordinate = "A1"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "raf", label = "Raf", coordinate = "A2"),
                                ExcelTableCellItem(source = TableItemSource.VARIABLE, variableName = "adet", label = "Stok Adedi", postfix = " ad", coordinate = "A3"),
                                ExcelTableCellItem(source = TableItemSource.COMPUTED, variableName = "durum", label = "Durum", coordinate = "A4", isEmphasized = true)
                            )
                        )
                    )
                ),
                // 2. Akaryakıt & Pompa Hesabı (Benzinci — Anahtar=Değer & @Global Fiyatlar & switch!)
                QrProfile(
                    id = "preset_fuel_station",
                    name = "Akaryakıt & Pompa",
                    description = "Format: 'PLAKA=34ABC12&YAKIT=MOTORIN&LT=45.5', güncel @global pompa fiyatıyla tutarı hesaplar",
                    parsingRules = QrParsingRules(
                        parsingMode = QrParsingMode.KEY_VALUE,
                        delimiter = "&",
                        keyValueSeparator = "=",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "plaka", alias = "Araç Plaka", extractionKey = "PLAKA", dataType = FieldDataType.STRING, defaultValue = "34ABC12"),
                            VariableDefinition(position = 1, name = "yakit", alias = "Yakıt Tipi", extractionKey = "YAKIT", dataType = FieldDataType.STRING, defaultValue = "MOTORIN"),
                            VariableDefinition(position = 2, name = "lt", alias = "Miktar", extractionKey = "LT", dataType = FieldDataType.DOUBLE, postfix = " Lt", defaultValue = "45.5"),
                            VariableDefinition(
                                name = "birim_fiyat",
                                alias = "Litre Fiyatı",
                                dataType = FieldDataType.DOUBLE,
                                isComputed = true,
                                prefix = "₺",
                                computationFormula = "switch(yakit, \"BENZIN\", @benzin_lt, \"MOTORIN\", @motorin_lt, \"LPG\", @lpg_lt, @motorin_lt)"
                            ),
                            VariableDefinition(
                                name = "tutar",
                                alias = "Ödenecek Tutar",
                                dataType = FieldDataType.DOUBLE,
                                isComputed = true,
                                isPrimaryOutput = true,
                                prefix = "₺",
                                postfix = " TL",
                                computationFormula = "lt * birim_fiyat"
                            )
                        )
                    ),
                    formula = ArithmeticFormula("", ""),
                    formatting = ResultFormatting(decimalPrecision = 2, prefix = "₺", postfix = " TL", decimalSeparator = ",", thousandsSeparator = "."),
                    overlay = OverlayAppearance(
                        textColorHex = "#F59E0B",
                        borderColorHex = "#F59E0B",
                        backgroundColorHex = "#351502",
                        gradientColorEndHex = "#1E1B4B",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "sparkle",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "AKARYAKIT FİŞİ",
                            headerSubtitle = "POMPA HESABI",
                            gridTheme = ExcelGridTheme.AMBER_GOLD,
                            items = emptyList()
                        )
                    )
                ),
                // 3. Kütüphane & İade Takip (Kütüphaneci — Tarih + Gün Ekleme + Gecikme Durumu!)
                QrProfile(
                    id = "preset_library_loan",
                    name = "Kütüphane & İade Takip",
                    description = "Format: 'ISBN-RAF-ODUNC_TARIHI' (örn: 978975-B04-20261101), iade tarihini ve gecikmeyi hesaplar",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "isbn", alias = "ISBN", dataType = FieldDataType.STRING, defaultValue = "978975"),
                            VariableDefinition(position = 1, name = "raf", alias = "Raf Konumu", dataType = FieldDataType.STRING, defaultValue = "B04"),
                            VariableDefinition(position = 2, name = "odunc", alias = "Ödünç Tarihi", dataType = FieldDataType.DATE, defaultValue = "20261101"),
                            VariableDefinition(position = -1, name = "sure", alias = "Ödünç Süresi", dataType = FieldDataType.INTEGER, isManual = true, defaultValue = "14", postfix = " gün", showPill = false),
                            VariableDefinition(
                                name = "iade_tarihi",
                                alias = "Son İade",
                                dataType = FieldDataType.DATE,
                                isComputed = true,
                                computationFormula = "gun_ekle(odunc, sure)"
                            ),
                            VariableDefinition(
                                name = "gecikme",
                                alias = "Gecikme",
                                dataType = FieldDataType.INTEGER,
                                isComputed = true,
                                showPill = false,
                                computationFormula = "max(0, gun_farki(bugun, iade_tarihi))"
                            ),
                            VariableDefinition(
                                name = "durum",
                                alias = "İade Durumu",
                                dataType = FieldDataType.STRING,
                                isComputed = true,
                                isPrimaryOutput = true,
                                computationFormula = "if(gecikme > 0, \"GECİKMİŞ (\" + round(gecikme, 0) + \" gün)\", \"ZAMANINDA\")"
                            )
                        )
                    ),
                    formula = ArithmeticFormula("", ""),
                    formatting = ResultFormatting(decimalPrecision = 0, decimalSeparator = ",", thousandsSeparator = "."),
                    overlay = OverlayAppearance(
                        textColorHex = "#38BDF8",
                        borderColorHex = "#38BDF8",
                        backgroundColorHex = "#0C4A6E",
                        gradientColorEndHex = "#082F49",
                        fillMode = OverlayFillMode.FILLED_GRADIENT,
                        iconName = "tag",
                        displayMode = OverlayDisplayMode.EXCEL_TABLE,
                        tableConfig = ExcelTableConfig(
                            headerTitle = "KÜTÜPHANE KAYDI",
                            headerSubtitle = "ÖDÜNÇ & İADE",
                            gridTheme = ExcelGridTheme.MODERN_SLATE,
                            items = emptyList()
                        )
                    )
                ),
                // 4. İndirim & Net Fiyat (₺ TL) — Tamamen Kullanıcı Değişkeniyle
                QrProfile(
                    id = "preset_turkish_discount",
                    name = "İndirim & Net Fiyat (₺ TL)",
                    description = "Format: 'Fiyat-İndirim%' (örn: 250-20), indirimli net tutarı hesaplar",
                    parsingRules = QrParsingRules(
                        delimiter = "-",
                        variables = listOf(
                            VariableDefinition(position = 0, name = "fiyat", alias = "Liste Fiyatı", prefix = "₺"),
                            VariableDefinition(position = 1, name = "indirim", alias = "İndirim Oranı", postfix = "%"),
                            VariableDefinition(
                                name = "net_tutar",
                                alias = "Net Tutar",
                                isComputed = true,
                                isPrimaryOutput = true,
                                prefix = "₺",
                                postfix = " TL",
                                computationFormula = "fiyat * (1 - indirim / 100)"
                            )
                        )
                    ),
                    formula = ArithmeticFormula("", ""),
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
                            items = emptyList()
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
