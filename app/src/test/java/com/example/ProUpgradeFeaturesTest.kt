package com.example

import android.graphics.RectF
import com.example.bluetooth.OneEuroFilter
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.camera.TargetReticleFilter
import com.example.data.ProfileRepository
import com.example.engine.QrParser
import com.example.engine.ScanCartAggregator
import com.example.engine.SmartTagWizardAnalyzer
import com.example.engine.WizardFormulaPreset
import com.example.model.ArithmeticFormula
import com.example.model.CalculationStatus
import com.example.model.FieldDataType
import com.example.model.GlobalVariable
import com.example.model.QrParsingMode
import com.example.model.QrParsingRules
import com.example.model.QrProfile
import com.example.model.ScanHistoryItem
import com.example.model.VariableDefinition
import com.example.ui.resolveTableRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProUpgradeFeaturesTest {

    @Test
    fun oneEuroFilter_dampensLowSpeedJitterAndTracksFastMotion() {
        val filter = OneEuroFilter(minCutoffHz = 2.0f, beta = 0.08f, dCutoffHz = 1.0f)

        // Feed alternating +-0.08 rad/s hand tremor at 100Hz
        filter.filter(0f, 0.01f)
        var maxJitterOutput = 0f
        for (i in 1..20) {
            val jitter = if (i % 2 == 0) 0.08f else -0.08f
            val out = filter.filter(jitter, 0.01f)
            maxJitterOutput = maxOf(maxJitterOutput, abs(out))
        }
        assertTrue("1€ filter should strongly dampen micro-jitter ($maxJitterOutput)", maxJitterOutput < 0.04f)

        // Now reset and feed a fast intentional 4.0 rad/s wrist flick
        filter.reset()
        filter.filter(0f, 0.01f)
        var fastOut = 0f
        repeat(8) {
            fastOut = filter.filter(4.0f, 0.01f)
        }
        assertTrue("1€ filter should track fast flicks with minimal lag ($fastOut)", fastOut > 3.2f)
    }

    private fun rectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    @Test
    fun targetReticleFilter_selectsCenterTargetAndIgnoresPeripheralShelfTags() {
        val w = 1000f
        val h = 2000f
        val reticle = TargetReticleFilter.computeReticleBounds(w, h)
        val cx = reticle.centerX
        val cy = reticle.centerY

        val farTopLeft = RawDetectedCode("TAG-FAR-1", rectF(10f, 10f, 90f, 90f))
        val nearCenter = RawDetectedCode("TAG-CENTER", rectF(cx - 25f, cy - 25f, cx + 25f, cy + 25f))
        val edgeInside = RawDetectedCode("TAG-EDGE", rectF(reticle.left + 5f, cy - 20f, reticle.left + 65f, cy + 20f))
        val farBottom = RawDetectedCode("TAG-FAR-2", rectF(850f, 1800f, 950f, 1900f))

        val selected = TargetReticleFilter.selectSingleTarget(
            detectedCodes = listOf(farTopLeft, edgeInside, nearCenter, farBottom),
            viewWidthPx = w,
            viewHeightPx = h
        )
        assertEquals(1, selected.size)
        assertEquals("TAG-CENTER", selected.first().rawValue)

        // When only peripheral tags are visible, returns empty list
        val noneSelected = TargetReticleFilter.selectSingleTarget(
            detectedCodes = listOf(farTopLeft, farBottom),
            viewWidthPx = w,
            viewHeightPx = h
        )
        assertTrue(noneSelected.isEmpty())
    }

    @Test
    fun scanCartAggregator_groupsQuantitiesCalculatesTotalAndBuildsExcelColumns() {
        val items = listOf(
            ScanHistoryItem(rawText = "A-100", formatName = "QR_CODE", profileName = "Standart", resultText = "₺100,00", numericValue = 100.0, timestamp = 1000L),
            ScanHistoryItem(rawText = "B-250", formatName = "QR_CODE", profileName = "Standart", resultText = "₺250,00", numericValue = 250.0, timestamp = 2000L),
            ScanHistoryItem(rawText = "A-100", formatName = "QR_CODE", profileName = "Standart", resultText = "₺100,00", numericValue = 100.0, timestamp = 3000L)
        )

        val cart = ScanCartAggregator.aggregate(items)
        assertEquals(2, cart.size)
        assertEquals("A-100", cart[0].rawText) // most recent first
        assertEquals(2, cart[0].quantity)
        assertEquals(200.0, cart[0].lineTotal!!, 0.001)

        assertEquals(3, ScanCartAggregator.totalUnits(cart))
        assertEquals(450.0, ScanCartAggregator.grandTotal(cart), 0.001)

        val cols = ScanCartAggregator.buildExcelColumns(cart[0])
        assertEquals(listOf("A-100", "2", "200.00"), cols)

        // Removal and quantity override
        val adjusted = ScanCartAggregator.aggregate(
            historyItems = items,
            quantityOverrides = mapOf("A-100" to 5),
            removedKeys = setOf("B-250")
        )
        assertEquals(1, adjusted.size)
        assertEquals(500.0, ScanCartAggregator.grandTotal(adjusted), 0.001)
    }

    @Test
    fun smartTagWizardAnalyzer_detectsDelimiterAndBuildsEvaluableProfile() {
        assertEquals("/", SmartTagWizardAnalyzer.detectDelimiter("TESBIH/450/2.5"))
        assertEquals("-", SmartTagWizardAnalyzer.detectDelimiter("120-2.5-15"))
        assertEquals(";", SmartTagWizardAnalyzer.detectDelimiter("10;20;30"))

        val sample = "TESBIH/450/2"
        val delim = SmartTagWizardAnalyzer.detectDelimiter(sample)
        val tokens = SmartTagWizardAnalyzer.splitSampleTokens(sample, delim)

        val profile = SmartTagWizardAnalyzer.buildProfileFromWizard(
            profileName = "Tesbih Satış",
            delimiter = delim,
            tokenLabels = listOf("Ürün Kodu", "Birim Fiyat", "Adet"),
            sampleTokens = tokens,
            formulaPreset = WizardFormulaPreset.MULTIPLY_FIRST_TWO,
            currencyPrefix = "₺"
        )

        val tracker = QrTracker()
        val parsed = tracker.processQr("TESBIH/450/2", profile)
        assertTrue("Wizard profile should evaluate cleanly", parsed.status is CalculationStatus.Success)
        val success = parsed.status as CalculationStatus.Success
        assertEquals(900.0, success.resultNumber, 0.001)
        assertTrue(success.formattedText.startsWith("₺"))
        assertEquals("", profile.formula.expression)

        // Formula-less wizard profile (pure variable extraction)
        val pureDataProfile = SmartTagWizardAnalyzer.buildProfileFromWizard(
            profileName = "Raf Kartı",
            delimiter = "-",
            tokenLabels = listOf("SKU", "Raf"),
            sampleTokens = listOf("KOLI01", "A12"),
            formulaPreset = WizardFormulaPreset.NONE,
            currencyPrefix = ""
        )
        assertEquals("", pureDataProfile.formula.expression)
        val pureParsed = tracker.processQr("KOLI01-A12", pureDataProfile)
        assertTrue("Formula-less profile should succeed cleanly", pureParsed.status is CalculationStatus.Success)
        assertEquals("KOLI01", (pureParsed.status as CalculationStatus.Success).formattedText)
    }

    @Test
    fun warehousePreset_evaluatesStringDecisionAndHonorsVariableVisibility() {
        val warehouse = ProfileRepository.createDefaultPresets().first { it.id == "preset_warehouse_stock" }
        val tracker = QrTracker()

        val critical = tracker.processQr("KOLI01-A12-3", warehouse)
        assertTrue(critical.status is CalculationStatus.Success)
        assertEquals("KRİTİK STOK", (critical.status as CalculationStatus.Success).formattedText)

        val sufficient = tracker.processQr("KOLI01-A12-12", warehouse)
        assertTrue(sufficient.status is CalculationStatus.Success)
        assertEquals("YETERLİ", (sufficient.status as CalculationStatus.Success).formattedText)

        // min_stok has showPill = false so it must not appear in resolved rows
        val rows = resolveTableRows(
            tableConfig = warehouse.overlay.tableConfig,
            result = critical,
            variableDefs = warehouse.parsingRules.variables
        )
        assertFalse(rows.any { it.first.variableName == "min_stok" })

        // Hiding 'raf' via showPill = false immediately removes it from table rows
        val hiddenRafVars = warehouse.parsingRules.variables.map {
            if (it.name == "raf") it.copy(showPill = false) else it
        }
        val filteredRows = resolveTableRows(
            tableConfig = warehouse.overlay.tableConfig,
            result = critical,
            variableDefs = hiddenRafVars
        )
        assertFalse(filteredRows.any { it.first.variableName == "raf" })
        assertTrue(filteredRows.any { it.first.variableName == "sku" })
    }

    @Test
    fun fuelStationPreset_parsesKeyValueWithGlobalsAndSwitch() {
        val fuelProfile = ProfileRepository.createDefaultPresets().first { it.id == "preset_fuel_station" }
        val globals = listOf(
            GlobalVariable(name = "benzin_lt", description = "Benzin", value = "44.00"),
            GlobalVariable(name = "motorin_lt", description = "Motorin", value = "45.00"),
            GlobalVariable(name = "lpg_lt", description = "LPG", value = "25.00")
        )
        val tracker = QrTracker()

        val motorin = tracker.processQr("PLAKA=34ABC12&YAKIT=MOTORIN&LT=10", fuelProfile, globalVariables = globals)
        assertTrue("Motorin should calculate cleanly: ${motorin.status}", motorin.status is CalculationStatus.Success)
        assertEquals(450.0, (motorin.status as CalculationStatus.Success).resultNumber, 0.001)
        assertEquals("34ABC12", motorin.extractedStrings["plaka"])

        val benzin = tracker.processQr("PLAKA=06XYZ99&YAKIT=BENZIN&LT=20", fuelProfile, globalVariables = globals)
        assertTrue(benzin.status is CalculationStatus.Success)
        assertEquals(880.0, (benzin.status as CalculationStatus.Success).resultNumber, 0.001)
    }

    @Test
    fun libraryPreset_computesDueDateAndOverdueText() {
        val libraryProfile = ProfileRepository.createDefaultPresets().first { it.id == "preset_library_loan" }
        val tracker = QrTracker()

        // Very old loan date -> guaranteed overdue
        val overdue = tracker.processQr("978975-B04-20200101", libraryProfile)
        assertTrue("Library overdue should succeed: ${overdue.status}", overdue.status is CalculationStatus.Success)
        assertEquals("15.01.2020", overdue.extractedStrings["iade_tarihi"])
        val overdueHeadline = (overdue.status as CalculationStatus.Success).formattedText
        assertTrue("Expected overdue text, got: $overdueHeadline", overdueHeadline.startsWith("GECİKMİŞ ("))

        // Far future loan date -> guaranteed on time
        val onTime = tracker.processQr("978975-B04-20990101", libraryProfile)
        assertTrue(onTime.status is CalculationStatus.Success)
        assertEquals("15.01.2099", onTime.extractedStrings["iade_tarihi"])
        assertEquals("ZAMANINDA", (onTime.status as CalculationStatus.Success).formattedText)

        // Auto table renders date string (dd.MM.yyyy), never raw epoch days
        val rows = resolveTableRows(
            tableConfig = libraryProfile.overlay.tableConfig,
            result = onTime,
            variableDefs = libraryProfile.parsingRules.variables
        )
        val dueRow = rows.firstOrNull { it.first.variableName == "iade_tarihi" }
        assertEquals("15.01.2099", dueRow?.second)
        assertFalse(rows.any { it.first.variableName == "sure" })
        assertFalse(rows.any { it.first.variableName == "gecikme" })
    }

    @Test
    fun fixedWidthAndRegexGroupModes_extractSlicesAndGroups() {
        // EAN-13 scale barcode: 28 (prefix) + 00123 (PLU) + 01450 (grams) + 9 (check)
        val fixedRules = QrParsingRules(
            parsingMode = QrParsingMode.FIXED_WIDTH,
            variables = listOf(
                VariableDefinition(name = "plu", dataType = FieldDataType.STRING, sliceStart = 2, sliceLength = 5),
                VariableDefinition(name = "gram", dataType = FieldDataType.INTEGER, sliceStart = 7, sliceLength = 5),
                VariableDefinition(name = "kg", dataType = FieldDataType.DOUBLE, isComputed = true, isPrimaryOutput = true, computationFormula = "gram / 1000")
            )
        )
        val fixedProfile = QrProfile(name = "Terazi EAN13", parsingRules = fixedRules, formula = ArithmeticFormula("", ""))
        val tracker = QrTracker()
        val fixedResult = tracker.processQr("2800123014509", fixedProfile)
        assertTrue(fixedResult.status is CalculationStatus.Success)
        assertEquals("00123", fixedResult.extractedStrings["plu"])
        assertEquals(1.45, (fixedResult.status as CalculationStatus.Success).resultNumber, 0.001)

        // Regex capture groups
        val regexRules = QrParsingRules(
            parsingMode = QrParsingMode.REGEX_GROUPS,
            regexPattern = "LOT:([A-Z0-9]+)\\s+QTY:(\\d+)",
            variables = listOf(
                VariableDefinition(position = 0, name = "lot", dataType = FieldDataType.STRING),
                VariableDefinition(position = 1, name = "qty", dataType = FieldDataType.INTEGER, isPrimaryOutput = true)
            )
        )
        val regexProfile = QrProfile(name = "Regex Lot", parsingRules = regexRules, formula = ArithmeticFormula("", ""))
        val regexResult = tracker.processQr("LOT:B42X QTY:75", regexProfile)
        assertTrue(regexResult.status is CalculationStatus.Success)
        assertEquals("B42X", regexResult.extractedStrings["lot"])
        assertEquals(75.0, (regexResult.status as CalculationStatus.Success).resultNumber, 0.001)
    }

    @Test
    fun renameVariableReferences_updatesWholeWordsWithoutCorruptingLongerNames() {
        val rules = QrParsingRules(
            variables = listOf(
                VariableDefinition(position = 0, name = "fiyat", dataType = FieldDataType.DOUBLE),
                VariableDefinition(position = 1, name = "fiyat_kdv", isComputed = true, computationFormula = "fiyat * 1.2"),
                VariableDefinition(position = 2, name = "toplam", isComputed = true, computationFormula = "fiyat_kdv + fiyat")
            )
        )
        val updated = QrParser.renameVariableReferences(rules, "fiyat", "birim")
        assertEquals("birim * 1.2", updated.variables[1].computationFormula)
        assertEquals("fiyat_kdv + birim", updated.variables[2].computationFormula)
    }

    @Test
    fun styleBuilderOverlayAppearance_jsonRoundTripAndStudioPresetsPreserveItems() {
        val customOverlay = com.example.model.OverlayAppearance(
            fontFamily = com.example.model.OverlayFontFamily.CONDENSED,
            fontWeightLevel = 800,
            useMonoForValues = false,
            accentColorHex = "#FACC15",
            labelColorHex = "#A1A1AA",
            headerBgColorHex = "#27272A",
            useCustomTableColors = true,
            gradientAngleDeg = 45f,
            cornerStyle = com.example.model.OverlayCornerStyle.CUT_CORNER,
            cornerRadiusDp = 14f,
            borderStyle = com.example.model.BorderStyle.DOUBLE,
            borderWidthDp = 3.5f,
            shadowElevationDp = 18f,
            rowPaddingDp = 8f,
            rowStripeOpacity = 0.22f
        )

        val restored = com.example.model.OverlayAppearance.fromJson(customOverlay.toJson())
        assertEquals(com.example.model.OverlayFontFamily.CONDENSED, restored.fontFamily)
        assertEquals(800, restored.fontWeightLevel)
        assertFalse(restored.useMonoForValues)
        assertEquals("#FACC15", restored.accentColorHex)
        assertEquals("#A1A1AA", restored.labelColorHex)
        assertEquals("#27272A", restored.headerBgColorHex)
        assertTrue(restored.useCustomTableColors)
        assertEquals(45f, restored.gradientAngleDeg, 0.001f)
        assertEquals(com.example.model.OverlayCornerStyle.CUT_CORNER, restored.cornerStyle)
        assertEquals(com.example.model.BorderStyle.DOUBLE, restored.borderStyle)
        assertEquals(18f, restored.shadowElevationDp, 0.001f)
        assertEquals(8f, restored.rowPaddingDp, 0.001f)
        assertEquals(0.22f, restored.rowStripeOpacity, 0.001f)

        // All 8 curated Studio Presets must preserve user table items & callout config
        val warehouseOverlay = ProfileRepository.createDefaultPresets().first { it.id == "preset_warehouse_stock" }.overlay
        com.example.ui.settings.STYLE_STUDIO_PRESETS.forEach { preset ->
            val styled = preset.apply(warehouseOverlay)
            assertEquals("Preset ${preset.id} must keep table items", warehouseOverlay.tableConfig.items.size, styled.tableConfig.items.size)
        }
    }
}
