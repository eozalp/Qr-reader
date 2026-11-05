package com.example

import android.graphics.RectF
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.engine.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
class QrEngineComprehensiveTest {

    // --- QR Parsing Tests ---

    @Test
    fun testDefaultDelimiterParsing() {
        val rules = QrParsingRules(delimiter = "-")
        val outcome = QrParser.parse("10-20-30-40", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(10.0, vars["a"]!!, 1e-6)
        assertEquals(20.0, vars["b"]!!, 1e-6)
        assertEquals(30.0, vars["c"]!!, 1e-6)
        assertEquals(40.0, vars["d"]!!, 1e-6)
    }

    @Test
    fun testCustomCommaDelimiterAndAliases() {
        val rules = QrParsingRules(
            delimiter = ",",
            variables = listOf(
                VariableDefinition(position = 0, name = "price", alias = "p"),
                VariableDefinition(position = 1, name = "qty", alias = "q")
            )
        )
        val outcome = QrParser.parse("49.95,3", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(49.95, vars["price"]!!, 1e-6)
        assertEquals(49.95, vars["p"]!!, 1e-6)
        assertEquals(3.0, vars["qty"]!!, 1e-6)
        assertEquals(3.0, vars["q"]!!, 1e-6)
    }

    @Test
    fun testOptionalFieldWithDefault() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "a"),
                VariableDefinition(position = 1, name = "b", isOptional = true, defaultValue = "99")
            )
        )
        val outcome = QrParser.parse("42", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(42.0, vars["a"]!!, 1e-6)
        assertEquals(99.0, vars["b"]!!, 1e-6)
    }

    @Test
    fun testDefaultValueOverridesBarcodeValueWhenConfigured() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "a"),
                VariableDefinition(position = 1, name = "b", defaultValue = "999", overrideBarcode = true),
                VariableDefinition(position = 2, name = "c"),
                VariableDefinition(position = 3, name = "d")
            )
        )
        // Barcode contains "10-20-30-40", but b is set to override with defaultValue "999"
        val outcome = QrParser.parse("10-20-30-40", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(10.0, vars["a"]!!, 1e-6)
        assertEquals(999.0, vars["b"]!!, 1e-6) // Overridden by default value!
        assertEquals(30.0, vars["c"]!!, 1e-6)
        assertEquals(40.0, vars["d"]!!, 1e-6)
    }

    @Test
    fun testBarcodeValueUsedWhenOverrideDisabled() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "a"),
                VariableDefinition(position = 1, name = "b", defaultValue = "999", overrideBarcode = false)
            )
        )
        // Barcode contains "10-20", override is false, so scanned 20 must be preserved
        val outcome = QrParser.parse("10-20", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(10.0, vars["a"]!!, 1e-6)
        assertEquals(20.0, vars["b"]!!, 1e-6)
    }

    @Test
    fun testComputedVariablePricePerGram() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "price", prefix = "$"),
                VariableDefinition(position = 1, name = "grams", postfix = "g"),
                VariableDefinition(
                    name = "ppg",
                    isComputed = true,
                    computationFormula = "price / grams",
                    prefix = "$",
                    postfix = "/g"
                )
            )
        )
        // Barcode contains "50-250" -> 50 dollars for 250 grams -> 0.20 $/g
        val outcome = QrParser.parse("50-250", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(50.0, vars["price"]!!, 1e-6)
        assertEquals(250.0, vars["grams"]!!, 1e-6)
        assertEquals(0.20, vars["ppg"]!!, 1e-6)
    }

    @Test
    fun testChainedComputedVariables() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "price"),
                VariableDefinition(position = 1, name = "qty"),
                VariableDefinition(name = "subtotal", isComputed = true, computationFormula = "price * qty"),
                VariableDefinition(name = "tax", isComputed = true, computationFormula = "subtotal * 0.18"),
                VariableDefinition(name = "total", isComputed = true, computationFormula = "subtotal + tax")
            )
        )
        // Barcode contains "100-2" -> subtotal=200, tax=36, total=236
        val outcome = QrParser.parse("100-2", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(200.0, vars["subtotal"]!!, 1e-6)
        assertEquals(36.0, vars["tax"]!!, 1e-6)
        assertEquals(236.0, vars["total"]!!, 1e-6)
    }

    @Test
    fun testInvalidInputHandledSafely() {
        val rules = QrParsingRules(delimiter = "-")
        val emptyOutcome = QrParser.parse("", rules)
        assertTrue(emptyOutcome is QrParseOutcome.Error)

        val nonNumericOutcome = QrParser.parse("10-abc-30-40", rules)
        assertTrue(nonNumericOutcome is QrParseOutcome.Error)
    }

    @Test
    fun testMinMaxBoundsValidation() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "val", minValue = 10.0, maxValue = 50.0)
            )
        )
        assertTrue(QrParser.parse("5", rules) is QrParseOutcome.Error)
        assertTrue(QrParser.parse("60", rules) is QrParseOutcome.Error)
        assertTrue(QrParser.parse("25", rules) is QrParseOutcome.Success)
    }

    // --- Math Engine Tests ---

    private val evaluator = MathExpressionEvaluator()

    @Test
    fun testBasicArithmeticAndPrecedence() {
        val vars = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0)
        val res = evaluator.evaluate("a + b * c", vars)
        assertTrue(res is MathResult.Success)
        // 10 + (20 * 30) = 610
        assertEquals(610.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testParenthesesAndDivision() {
        val vars = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0)
        val res = evaluator.evaluate("(a + b) / (d - c)", vars)
        assertTrue(res is MathResult.Success)
        // (10 + 20) / (40 - 30) = 30 / 10 = 3
        assertEquals(3.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testPowerAndModulo() {
        val vars = mapOf("x" to 3.0, "y" to 4.0)
        val resPower = evaluator.evaluate("x ^ 2 + y ^ 2", vars)
        assertTrue(resPower is MathResult.Success)
        // 9 + 16 = 25
        assertEquals(25.0, (resPower as MathResult.Success).value, 1e-6)

        val resMod = evaluator.evaluate("10 % 3", emptyMap())
        assertTrue(resMod is MathResult.Success)
        assertEquals(1.0, (resMod as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testPercentageCalculation() {
        val vars = mapOf("price" to 200.0)
        val res = evaluator.evaluate("price * 15%", vars)
        assertTrue(res is MathResult.Success)
        assertEquals(30.0, (res as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testMathematicalFunctions() {
        val vars = mapOf("a" to 10.0, "b" to 30.0, "c" to 20.0)
        val resMax = evaluator.evaluate("max(a, b, c)", vars)
        assertTrue(resMax is MathResult.Success)
        assertEquals(30.0, (resMax as MathResult.Success).value, 1e-6)

        val resMin = evaluator.evaluate("min(a, b, c)", vars)
        assertTrue(resMin is MathResult.Success)
        assertEquals(10.0, (resMin as MathResult.Success).value, 1e-6)

        val resAvg = evaluator.evaluate("avg(a, b, c)", vars)
        assertTrue(resAvg is MathResult.Success)
        assertEquals(20.0, (resAvg as MathResult.Success).value, 1e-6)

        val resSqrt = evaluator.evaluate("sqrt(144)", emptyMap())
        assertTrue(resSqrt is MathResult.Success)
        assertEquals(12.0, (resSqrt as MathResult.Success).value, 1e-6)

        val resAbs = evaluator.evaluate("abs(-45.5)", emptyMap())
        assertTrue(resAbs is MathResult.Success)
        assertEquals(45.5, (resAbs as MathResult.Success).value, 1e-6)
    }

    @Test
    fun testDivisionByZeroHandledGracefully() {
        val res = evaluator.evaluate("10 / 0", emptyMap())
        assertTrue(res is MathResult.Error)
        assertEquals("Division by zero", (res as MathResult.Error).message)
    }

    // --- Result Formatting Tests ---

    @Test
    fun testResultFormattingCurrency() {
        val formatting = ResultFormatting(
            decimalPrecision = 2,
            prefix = "₺",
            postfix = " TL",
            roundingMode = AppRoundingMode.ROUND_HALF_UP
        )
        val result = ResultFormatter.format(100.0, formatting)
        assertEquals("₺100.00 TL", result)
    }

    @Test
    fun testThousandsSeparatorAndRounding() {
        val formatting = ResultFormatting(
            decimalPrecision = 1,
            prefix = "$",
            thousandsSeparator = ",",
            roundingMode = AppRoundingMode.CEILING
        )
        val result = ResultFormatter.format(12345.21, formatting)
        assertEquals("$12,345.3", result)
    }

    @Test
    fun testNegativeFormatting() {
        val formatting = ResultFormatting(
            decimalPrecision = 2,
            prefix = "$",
            negativeFormat = "(x)"
        )
        val result = ResultFormatter.format(-50.0, formatting)
        assertEquals("($50.00)", result)
    }

    // --- Multi-QR Processing & Profile Serialization ---

    @Test
    fun testMultiQrTrackingEngine() {
        val tracker = QrTracker()
        val profile = ProfileRepository.createDefaultPresets().first()

        val codes = listOf(
            RawDetectedCode("10-20-30-40", RectF(0f, 0f, 100f, 100f)),
            RawDetectedCode("5-10-15-20", RectF(200f, 200f, 300f, 300f)),
            RawDetectedCode("100-200-300-400", RectF(400f, 400f, 500f, 500f))
        )

        val tracked = tracker.update(codes, profile, currentTimeMs = 1000L)
        assertEquals(3, tracked.size)

        // Verify independent calculations
        val results = tracked.map { (it.result.status as CalculationStatus.Success).resultNumber }
        assertTrue(results.contains(100.0))  // 10+20+30+40
        assertTrue(results.contains(50.0))   // 5+10+15+20
        assertTrue(results.contains(1000.0)) // 100+200+300+400
    }

    @Test
    fun testProfileJsonRoundTrip() {
        val preset = ProfileRepository.createDefaultPresets().first()
        val json = preset.toJson()
        val reconstructed = QrProfile.fromJson(json)

        assertEquals(preset.id, reconstructed.id)
        assertEquals(preset.name, reconstructed.name)
        assertEquals(preset.formula.expression, reconstructed.formula.expression)
        assertEquals(preset.parsingRules.delimiter, reconstructed.parsingRules.delimiter)
        assertEquals(preset.formatting.prefix, reconstructed.formatting.prefix)
        assertEquals(preset.formatting.postfix, reconstructed.formatting.postfix)
        assertEquals(preset.overlay.displayMode, reconstructed.overlay.displayMode)
        assertEquals(preset.overlay.tableConfig.headerTitle, reconstructed.overlay.tableConfig.headerTitle)
        assertTrue(reconstructed.overlay.tableConfig.items.isNotEmpty())
    }

    // --- Mini Excel Table Layout & Resolution Tests ---

    @Test
    fun testExcelTableConfigSerialization() {
        val table = ExcelTableConfig(
            isEnabled = true,
            showHeader = true,
            headerTitle = "RECEIPT BREAKDOWN",
            headerSubtitle = "TAX 18%",
            showColumnHeaders = true,
            showCoordinates = true,
            gridTheme = ExcelGridTheme.AMBER_GOLD,
            items = listOf(
                ExcelTableCellItem(
                    source = TableItemSource.VARIABLE,
                    variableName = "price",
                    label = "Base Price",
                    prefix = "$",
                    postfix = " USD",
                    coordinate = "A1",
                    isVisible = true,
                    isEmphasized = false
                ),
                ExcelTableCellItem(
                    source = TableItemSource.FORMULA_RESULT,
                    label = "GRAND TOTAL",
                    prefix = "$",
                    coordinate = "A2",
                    isVisible = true,
                    isEmphasized = true
                )
            )
        )

        val json = table.toJson()
        val restored = ExcelTableConfig.fromJson(json)

        assertEquals("RECEIPT BREAKDOWN", restored.headerTitle)
        assertEquals("TAX 18%", restored.headerSubtitle)
        assertEquals(ExcelGridTheme.AMBER_GOLD, restored.gridTheme)
        assertEquals(2, restored.items.size)

        val item1 = restored.items[0]
        assertEquals("Base Price", item1.label)
        assertEquals("$", item1.prefix)
        assertEquals(" USD", item1.postfix)
        assertEquals("A1", item1.coordinate)
        assertFalse(item1.isEmphasized)

        val item2 = restored.items[1]
        assertEquals("GRAND TOTAL", item2.label)
        assertTrue(item2.isEmphasized)
    }

    @Test
    fun testResolveTableRowsFormattingAndEmphasis() {
        val tableConfig = ExcelTableConfig(
            items = listOf(
                ExcelTableCellItem(
                    source = TableItemSource.VARIABLE,
                    variableName = "price",
                    label = "Price",
                    prefix = "$",
                    coordinate = "A1",
                    isVisible = true
                ),
                ExcelTableCellItem(
                    source = TableItemSource.VARIABLE,
                    variableName = "grams",
                    label = "Grams",
                    postfix = "g",
                    coordinate = "A2",
                    isVisible = false // Hidden
                ),
                ExcelTableCellItem(
                    source = TableItemSource.COMPUTED,
                    variableName = "unit_price",
                    label = "Unit Price",
                    prefix = "$",
                    postfix = "/g",
                    coordinate = "A3",
                    isVisible = true,
                    isEmphasized = true
                )
            )
        )

        val sampleResult = ParsedQrResult(
            rawText = "50-250",
            extractedVariables = mapOf(
                "price" to 50.0,
                "grams" to 250.0,
                "unit_price" to 0.20
            ),
            status = CalculationStatus.Success(0.20, "$0.20")
        )

        val resolved = com.example.ui.resolveTableRows(tableConfig, sampleResult)

        // Grams was isVisible = false, so only 2 rows should be present
        assertEquals(2, resolved.size)

        val row1 = resolved[0]
        assertEquals("Price", row1.first.label)
        assertEquals("$50", row1.second)
        assertFalse(row1.first.isEmphasized)

        val row2 = resolved[1]
        assertEquals("Unit Price", row2.first.label)
        assertEquals("$0.20/g", row2.second)
        assertTrue(row2.first.isEmphasized)
    }

    // --- Manual Variables, Signals & Embedded Profile QR Tests ---

    @Test
    fun testManualVariablesWithoutBarcodeToken() {
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "weight"),
                VariableDefinition(
                    name = "gram_price",
                    isManual = true,
                    defaultValue = "85.5",
                    prefix = "$"
                ),
                VariableDefinition(
                    name = "labor_fee",
                    isManual = true,
                    defaultValue = "15.0"
                ),
                VariableDefinition(
                    name = "total",
                    isComputed = true,
                    computationFormula = "weight * gram_price + labor_fee"
                )
            )
        )

        // Scanned barcode only has ONE token ("10"), but manual variables and formula calculate successfully!
        val outcome = QrParser.parse("10", rules)
        assertTrue("Expected success: $outcome", outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables

        assertEquals(10.0, vars["weight"]!!, 1e-6)
        assertEquals(85.5, vars["gram_price"]!!, 1e-6)
        assertEquals(15.0, vars["labor_fee"]!!, 1e-6)
        assertEquals(10.0 * 85.5 + 15.0, vars["total"]!!, 1e-6)
    }

    @Test
    fun testEmbeddedProfileQrDetectionAndEncoding() {
        val originalProfile = QrProfile(
            name = "Amber Beads Workshop",
            triggerSignal = "AMBER-",
            parsingRules = QrParsingRules(
                delimiter = ";",
                variables = listOf(
                    VariableDefinition(position = 0, name = "beads"),
                    VariableDefinition(name = "bead_cost", isManual = true, defaultValue = "12.5")
                )
            ),
            formula = ArithmeticFormula("beads * bead_cost", "Total batch cost")
        )

        val qrEncoded = QrParser.encodeProfileToQrString(originalProfile)
        assertTrue(qrEncoded.startsWith("ARQR:PROFILE:"))

        val detected = QrParser.detectEmbeddedProfile(qrEncoded)
        assertNotNull(detected)
        assertEquals("Amber Beads Workshop", detected!!.name)
        assertEquals("AMBER-", detected.triggerSignal)
        assertEquals(";", detected.parsingRules.delimiter)
        assertEquals(2, detected.parsingRules.variables.size)
        assertTrue(detected.parsingRules.variables[1].isManual)
        assertEquals("12.5", detected.parsingRules.variables[1].defaultValue)
        assertEquals("beads * bead_cost", detected.formula.expression)
    }

    @Test
    fun testProfileTriggerSignalMatching() {
        val profiles = listOf(
            QrProfile(id = "p1", name = "Tesbih Amber", triggerSignal = "TESBIH-"),
            QrProfile(id = "p2", name = "Gold Jewelry", triggerSignal = "GOLD-"),
            QrProfile(id = "p3", name = "Silver Ring", triggerSignal = "[P:silver]")
        )

        // 1. Matches prefix TESBIH-
        val match1 = QrParser.findMatchingProfileBySignal("TESBIH-50-250", profiles)
        assertNotNull(match1)
        assertEquals("Tesbih Amber", match1!!.name)

        // 2. Matches prefix GOLD-
        val match2 = QrParser.findMatchingProfileBySignal("GOLD-18K-10.5", profiles)
        assertNotNull(match2)
        assertEquals("Gold Jewelry", match2!!.name)

        // 3. Matches structured tag [P:silver]
        val match3 = QrParser.findMatchingProfileBySignal("ITEM-999 [P:silver] 42", profiles)
        assertNotNull(match3)
        assertEquals("Silver Ring", match3!!.name)

        // 4. No signal match
        val matchNone = QrParser.findMatchingProfileBySignal("RANDOM-BARCODE-123", profiles)
        assertNull(matchNone)
    }

    @Test
    fun testProfileSerializationWithManualVarsAndTriggerSignal() {
        val profile = QrProfile(
            name = "Test Manual Profile",
            triggerSignal = "TRIGGER-XYZ",
            parsingRules = QrParsingRules(
                variables = listOf(
                    VariableDefinition(name = "custom_rate", isManual = true, defaultValue = "3.1415")
                )
            )
        )

        val json = profile.toJson()
        val restored = QrProfile.fromJson(json)

        assertEquals("Test Manual Profile", restored.name)
        assertEquals("TRIGGER-XYZ", restored.triggerSignal)
        assertEquals(1, restored.parsingRules.variables.size)
        assertTrue(restored.parsingRules.variables[0].isManual)
        assertEquals("3.1415", restored.parsingRules.variables[0].defaultValue)
    }

    @Test
    fun testComputedVariablesInParsing() {
        // Barcode gives price (150) and grams (25).
        // Computed variable 'price_per_gram' computes price / grams = 6.0
        val rules = QrParsingRules(
            delimiter = "-",
            variables = listOf(
                VariableDefinition(position = 0, name = "price"),
                VariableDefinition(position = 1, name = "grams"),
                VariableDefinition(
                    name = "price_per_gram",
                    isComputed = true,
                    computationFormula = "price / grams",
                    prefix = "$",
                    postfix = "/g",
                    decimalPlaces = 2
                )
            )
        )

        val outcome = QrParser.parse("150-25", rules)
        assertTrue(outcome is QrParseOutcome.Success)
        val vars = (outcome as QrParseOutcome.Success).numericVariables
        assertEquals(150.0, vars["price"]!!, 1e-6)
        assertEquals(25.0, vars["grams"]!!, 1e-6)
        assertEquals(6.0, vars["price_per_gram"]!!, 1e-6)
    }

    @Test
    fun testVariableIndividualDecimalFormatting() {
        val var3Decimals = VariableDefinition(
            name = "rate",
            decimalPlaces = 3,
            prefix = "₺",
            postfix = "/g"
        )
        val var0Decimals = VariableDefinition(
            name = "qty",
            decimalPlaces = 0,
            postfix = " pcs"
        )

        val formatted1 = ResultFormatter.formatVariable(12.34567, var3Decimals, null, includePrefixPostfix = true)
        assertEquals("₺12.346/g", formatted1)

        val formatted2 = ResultFormatter.formatVariable(42.89, var0Decimals, null, includePrefixPostfix = true)
        assertEquals("43 pcs", formatted2)
    }

    // --- QR Attachment & Linked Data Tests ---

    @Test
    fun testQrAttachmentSerializationAndMatching() {
        val attachment = QrAttachment(
            qrContent = "ITEM-9988",
            title = "Ottoman Antique Amber Tesbih",
            notes = "Rare 33-bead cherry amber, 19th century.",
            imageUri = "/data/user/0/com.example/files/qr_media/att_test.jpg",
            videoUri = "/data/user/0/com.example/files/qr_media/att_test.mp4",
            customFields = mapOf("Origin" to "Baltic", "Beads" to "33", "Grade" to "A+"),
            showInOverlay = true,
            showInTable = true
        )

        // Matching
        assertTrue(attachment.matchesQr("ITEM-9988"))
        assertTrue(attachment.matchesQr("ITEM-9988-SUB"))
        assertTrue(attachment.matchesQr("PREFIX-ITEM-9988"))
        assertFalse(attachment.matchesQr("OTHER-CODE"))

        // Serialization & Deserialization
        val json = attachment.toJson()
        val parsed = QrAttachment.fromJson(json)

        assertNotNull(parsed)
        assertEquals(attachment.id, parsed?.id)
        assertEquals(attachment.qrContent, parsed?.qrContent)
        assertEquals(attachment.title, parsed?.title)
        assertEquals(attachment.notes, parsed?.notes)
        assertEquals(attachment.imageUri, parsed?.imageUri)
        assertEquals(attachment.videoUri, parsed?.videoUri)
        assertEquals("Baltic", parsed?.customFields?.get("Origin"))
        assertEquals("33", parsed?.customFields?.get("Beads"))
        assertEquals(true, parsed?.showInOverlay)
        assertEquals(true, parsed?.showInTable)
    }

    @Test
    fun testResolveTableRowsWithLinkedAttachment() {
        val parsedResult = ParsedQrResult(
            rawText = "150-25",
            extractedVariables = mapOf("price" to 150.0, "weight" to 25.0),
            status = CalculationStatus.Success(resultNumber = 6.0, formattedText = "$6.00")
        )

        val tableConfig = ExcelTableConfig(
            headerTitle = "Live Specifications",
            items = listOf(
                ExcelTableCellItem(
                    source = TableItemSource.FORMULA_RESULT,
                    label = "Total",
                    coordinate = "A1",
                    isEmphasized = true
                ),
                ExcelTableCellItem(
                    source = TableItemSource.VARIABLE,
                    variableName = "price",
                    label = "Price",
                    coordinate = "A2"
                )
            )
        )

        val attachment = QrAttachment(
            qrContent = "150-25",
            title = "Handmade Ring",
            customFields = mapOf("Material" to "Silver 925", "Gemstone" to "Onyx"),
            showInTable = true
        )

        val rows = com.example.ui.resolveTableRows(
            tableConfig = tableConfig,
            result = parsedResult,
            variableDefs = emptyList(),
            attachment = attachment
        )

        // Base rows (2) + Attachment rows (Item, Material, Gemstone = 3) -> total 5
        assertTrue("Rows count should include base rows plus attachment fields", rows.size >= 4)

        val itemPair = rows.find { it.first.label == "Item" }
        assertNotNull("Expected 'Item' row from attachment", itemPair)
        assertEquals("Handmade Ring", itemPair?.second)

        val materialPair = rows.find { it.first.label == "Material" }
        assertNotNull("Expected 'Material' row from attachment", materialPair)
        assertEquals("Silver 925", materialPair?.second)
    }

    // --- Callout Layout & Leader Lines Tests ---

    @Test
    fun testOverlayAppearanceCalloutSerialization() {
        val appearance = OverlayAppearance(
            calloutMode = CalloutLayoutMode.SMART_ANTI_COLLISION,
            leaderLineStyle = LeaderLineStyle.DASHED,
            showAnchorDot = true,
            showBarcodeDashedOutline = true,
            leaderLineWidthDp = 2.0f,
            leaderLineColorHex = "#00F5D4"
        )

        val json = appearance.toJson()
        val parsed = OverlayAppearance.fromJson(json)

        assertEquals(CalloutLayoutMode.SMART_ANTI_COLLISION, parsed.calloutMode)
        assertEquals(LeaderLineStyle.DASHED, parsed.leaderLineStyle)
        assertTrue(parsed.showAnchorDot)
        assertTrue(parsed.showBarcodeDashedOutline)
        assertEquals(2.0f, parsed.leaderLineWidthDp, 1e-4f)
        assertEquals("#00F5D4", parsed.leaderLineColorHex)
    }

    @Test
    fun testCalloutLayoutSolverAntiCollisionSeparation() {
        val density = androidx.compose.ui.unit.Density(2.5f, 1.0f)

        val dummyResult1 = ParsedQrResult(rawText = "100-10", status = CalculationStatus.Success(10.0, "$10.00"))
        val dummyResult2 = ParsedQrResult(rawText = "200-20", status = CalculationStatus.Success(10.0, "$10.00"))

        // Two closely positioned QR codes whose data cards would collide
        val qr1 = TrackedQr(
            id = "qr1",
            rawContent = "100-10",
            currentBox = RectF(200f, 600f, 360f, 760f),
            targetBox = RectF(200f, 600f, 360f, 760f),
            result = dummyResult1,
            lastSeenMs = 1000L,
            firstSeenMs = 500L
        )
        val qr2 = TrackedQr(
            id = "qr2",
            rawContent = "200-20",
            currentBox = RectF(220f, 620f, 380f, 780f),
            targetBox = RectF(220f, 620f, 380f, 780f),
            result = dummyResult2,
            lastSeenMs = 1000L,
            firstSeenMs = 500L
        )

        val config = OverlayAppearance(
            calloutMode = CalloutLayoutMode.SMART_ANTI_COLLISION,
            leaderLineStyle = LeaderLineStyle.DASHED
        )

        val solved = com.example.ui.CalloutLayoutSolver.solve(
            items = listOf(qr1, qr2),
            config = config,
            screenWidthPx = 1080f,
            screenHeightPx = 1920f,
            density = density
        )

        assertEquals(2, solved.size)

        val card1 = solved[0]
        val card2 = solved[1]

        // Check that the anti-collision algorithm separated them so they don't overlap
        val centerDistY = kotlin.math.abs(card1.cardCenterY - card2.cardCenterY)
        val centerDistX = kotlin.math.abs(card1.cardCenterX - card2.cardCenterX)

        assertTrue(
            "Cards must be pushed apart vertically or horizontally. DistY: $centerDistY, DistX: $centerDistX",
            centerDistY >= card1.cardHeight * 0.7f || centerDistX >= card1.cardWidth * 0.7f
        )

        // Leader line is bonded to barcode boundary
        assertTrue(card1.hasLeaderLine)
        assertTrue(card2.hasLeaderLine)
    }

    @Test
    fun testQrAttachmentMultipleImagesAndVideosSerialization() {
        val attachment = QrAttachment(
            qrContent = "ITEM-999",
            title = "Jewelry Piece",
            notes = "Pure Silver",
            images = listOf("/data/img1.jpg", "/data/img2.jpg", "/data/img3.jpg"),
            videos = listOf("/data/vid1.mp4", "/data/vid2.mp4"),
            customFields = mapOf("Material" to "Silver 925", "Weight" to "45g")
        )

        assertEquals(3, attachment.allImages.size)
        assertEquals("/data/img1.jpg", attachment.imageUri)
        assertEquals(2, attachment.allVideos.size)
        assertEquals("/data/vid1.mp4", attachment.videoUri)

        val json = attachment.toJson()
        val restored = QrAttachment.fromJson(json)

        assertEquals("ITEM-999", restored.qrContent)
        assertEquals("Jewelry Piece", restored.title)
        assertEquals(3, restored.allImages.size)
        assertEquals(listOf("/data/img1.jpg", "/data/img2.jpg", "/data/img3.jpg"), restored.allImages)
        assertEquals(2, restored.allVideos.size)
        assertEquals(listOf("/data/vid1.mp4", "/data/vid2.mp4"), restored.allVideos)
        assertEquals("Silver 925", restored.customFields["Material"])
    }

    @Test
    fun testQrAttachmentLegacySingleMediaBackwardCompatibility() {
        val legacyJson = org.json.JSONObject().apply {
            put("id", "legacy-id")
            put("qrContent", "OLD-QR")
            put("title", "Old Entry")
            put("imageUri", "/data/single_old_photo.jpg")
            put("videoUri", "/data/single_old_video.mp4")
        }

        val restored = QrAttachment.fromJson(legacyJson)
        assertEquals(1, restored.allImages.size)
        assertEquals("/data/single_old_photo.jpg", restored.allImages[0])
        assertEquals("/data/single_old_photo.jpg", restored.imageUri)
        assertEquals(1, restored.allVideos.size)
        assertEquals("/data/single_old_video.mp4", restored.allVideos[0])
        assertEquals("/data/single_old_video.mp4", restored.videoUri)
    }

    @Test
    fun testImageOptimizationQualityConfigurations() {
        assertEquals(800, ImageOptimizationQuality.COMPACT.maxDimension)
        assertEquals(70, ImageOptimizationQuality.COMPACT.compressionQuality)

        assertEquals(1280, ImageOptimizationQuality.BALANCED.maxDimension)
        assertEquals(80, ImageOptimizationQuality.BALANCED.compressionQuality)

        assertEquals(1920, ImageOptimizationQuality.HIGH.maxDimension)
        assertEquals(90, ImageOptimizationQuality.HIGH.compressionQuality)

        assertEquals(0, ImageOptimizationQuality.ORIGINAL.maxDimension)
        assertEquals(100, ImageOptimizationQuality.ORIGINAL.compressionQuality)
    }

    @Test
    fun testCalloutLayoutSolverStrictScreenBoundaryContainment() {
        val density = androidx.compose.ui.unit.Density(2.0f, 1.0f)
        val dummyResult = ParsedQrResult(rawText = "CORNER-QR", status = CalculationStatus.Success(100.0, "$100"))

        // Place QR right at the extreme top-left screen corner
        val extremeQr = TrackedQr(
            id = "corner1",
            rawContent = "CORNER-QR",
            currentBox = RectF(0f, 0f, 100f, 100f),
            targetBox = RectF(0f, 0f, 100f, 100f),
            result = dummyResult,
            lastSeenMs = 1000L,
            firstSeenMs = 500L
        )

        val config = OverlayAppearance(
            calloutMode = CalloutLayoutMode.DIRECT_FOLLOW,
            autoAvoidEdges = true
        )

        val solved = com.example.ui.CalloutLayoutSolver.solve(
            items = listOf(extremeQr),
            config = config,
            screenWidthPx = 1080f,
            screenHeightPx = 1920f,
            density = density
        )

        assertEquals(1, solved.size)
        val card = solved[0]

        // Strict margins: card left and top must not bleed off the screen
        val cardLeft = card.cardCenterX - card.cardWidth / 2f
        val cardTop = card.cardCenterY - card.cardHeight / 2f
        val cardRight = card.cardCenterX + card.cardWidth / 2f
        val cardBottom = card.cardCenterY + card.cardHeight / 2f

        assertTrue("Card left must be >= 0: $cardLeft", cardLeft >= 0f)
        assertTrue("Card top must be >= 0: $cardTop", cardTop >= 0f)
        assertTrue("Card right must be <= 1080: $cardRight", cardRight <= 1080f)
        assertTrue("Card bottom must be <= 1920: $cardBottom", cardBottom <= 1920f)
    }

    @Test
    fun testExportAndImportIndividualProfileWithoutMedia() {
        val profile = QrProfile(
            name = "Amber Beads Master",
            triggerSignal = "AMBER-",
            parsingRules = QrParsingRules(delimiter = "-")
        )
        val attachment = QrAttachment(
            qrContent = "AMBER-99",
            title = "Cherry Amber 33",
            images = listOf("/dummy/photo1.jpg"),
            videos = listOf("/dummy/video1.mp4")
        )

        // Export WITHOUT media
        val exportJson = com.example.data.BackupTransferManager.createExportJson(
            profiles = listOf(profile),
            attachments = listOf(attachment),
            includeMedia = false
        )

        val preview = com.example.data.BackupTransferManager.parseImportPreview(exportJson)
        assertEquals(1, preview.profiles.size)
        assertEquals("Amber Beads Master", preview.profiles[0].name)
        assertEquals("AMBER-", preview.profiles[0].triggerSignal)
        assertEquals(1, preview.attachments.size)
        assertEquals("Cherry Amber 33", preview.attachments[0].title)

        // Verify media is stripped when exported without media
        assertFalse(preview.hasMedia)
        assertEquals(0, preview.attachments[0].allImages.size)
        assertEquals(0, preview.attachments[0].allVideos.size)
    }

    @Test
    fun testExportAndImportIndividualProfileWithMediaStructure() {
        val profile1 = QrProfile(name = "Gold Scale", triggerSignal = "GOLD-")
        val profile2 = QrProfile(name = "Diamond Scale", triggerSignal = "DIA-")

        val attachment = QrAttachment(
            qrContent = "GOLD-24K",
            title = "Pure 24K Bar"
        )

        // Selectively export only profile1 with media
        val exportJson = com.example.data.BackupTransferManager.createExportJson(
            profiles = listOf(profile1), // Only profile 1!
            attachments = listOf(attachment),
            includeMedia = true
        )

        val preview = com.example.data.BackupTransferManager.parseImportPreview(exportJson)
        assertEquals(1, preview.profiles.size)
        assertEquals("Gold Scale", preview.profiles[0].name)
        assertEquals(1, preview.attachments.size)
        assertEquals("Pure 24K Bar", preview.attachments[0].title)
    }

    @Test
    fun testParseImportPreviewSupportsLegacyProfilesArray() {
        val legacyArrayJson = org.json.JSONArray().apply {
            put(QrProfile(name = "Legacy 1").toJson())
            put(QrProfile(name = "Legacy 2").toJson())
        }.toString()

        val preview = com.example.data.BackupTransferManager.parseImportPreview(legacyArrayJson)
        assertEquals(2, preview.profiles.size)
        assertEquals("Legacy 1", preview.profiles[0].name)
        assertEquals("Legacy 2", preview.profiles[1].name)
        assertEquals(0, preview.attachments.size)
        assertFalse(preview.hasMedia)
    }

    // --- Barcode Reading Options & 2D/3D Format Tests ---

    @Test
    fun testBarcodeCatalogSupportedFormatsAndCategories() {
        val allItems = BarcodeCatalog.ALL_ITEMS
        assertTrue("Should support at least 13 barcode formats", allItems.size >= 13)

        // Verify 2D & 3D Stacked formats
        val twoDItems = allItems.filter { it.category == BarcodeCategory.TWO_D_AND_STACKED }
        val twoDFormatKeys = twoDItems.map { it.codeKey }
        assertTrue(twoDFormatKeys.contains("QR_CODE"))
        assertTrue(twoDFormatKeys.contains("DATA_MATRIX"))
        assertTrue(twoDFormatKeys.contains("PDF417"))
        assertTrue(twoDFormatKeys.contains("AZTEC"))

        // Verify PDF417 is badged as 3D Stacked
        val pdf417 = twoDItems.first { it.codeKey == "PDF417" }
        assertEquals("3D Stacked", pdf417.badge)

        // Verify 1D Retail formats
        val retailItems = allItems.filter { it.category == BarcodeCategory.ONE_D_RETAIL }
        val retailKeys = retailItems.map { it.codeKey }
        assertTrue(retailKeys.contains("EAN_13"))
        assertTrue(retailKeys.contains("EAN_8"))
        assertTrue(retailKeys.contains("UPC_A"))
        assertTrue(retailKeys.contains("UPC_E"))

        // Verify 1D Industrial & Logistics formats
        val logisticsItems = allItems.filter { it.category == BarcodeCategory.ONE_D_LOGISTICS }
        val logisticsKeys = logisticsItems.map { it.codeKey }
        assertTrue(logisticsKeys.contains("CODE_128"))
        assertTrue(logisticsKeys.contains("CODE_39"))
        assertTrue(logisticsKeys.contains("CODE_93"))
        assertTrue(logisticsKeys.contains("CODABAR"))
        assertTrue(logisticsKeys.contains("ITF"))
    }

    @Test
    fun testBarcodeScanPresetsResolution() {
        val allEffective = BarcodeCatalog.resolveEffectiveFormats(BarcodeScanPreset.ALL_FORMATS, emptyList())
        assertEquals(listOf(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS), allEffective)

        val twoDEffective = BarcodeCatalog.resolveEffectiveFormats(BarcodeScanPreset.TWO_D_ONLY, emptyList())
        assertEquals(4, twoDEffective.size)
        assertTrue(twoDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE))
        assertTrue(twoDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX))
        assertTrue(twoDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417))
        assertTrue(twoDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC))

        val oneDEffective = BarcodeCatalog.resolveEffectiveFormats(BarcodeScanPreset.ONE_D_ONLY, emptyList())
        assertEquals(9, oneDEffective.size)
        assertTrue(oneDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13))
        assertTrue(oneDEffective.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128))

        val customEffective = BarcodeCatalog.resolveEffectiveFormats(
            BarcodeScanPreset.CUSTOM,
            listOf(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13, com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        )
        assertEquals(2, customEffective.size)
    }

    @Test
    fun testTrackingSettingsBarcodeSerialization() {
        val original = TrackingSettings(
            smoothingFactor = 0.5f,
            scanPreset = BarcodeScanPreset.TWO_D_ONLY,
            enabledFormats = listOf(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE, com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417),
            enableInvertedDetection = true
        )

        val json = original.toJson()
        val deserialized = TrackingSettings.fromJson(json)

        assertEquals(BarcodeScanPreset.TWO_D_ONLY, deserialized.scanPreset)
        assertEquals(2, deserialized.enabledFormats.size)
        assertTrue(deserialized.enabledFormats.contains(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417))
        assertTrue(deserialized.enableInvertedDetection)
    }

    @Test
    fun testQrTrackerWithVariousBarcodeFormats() {
        val tracker = QrTracker()
        val profile = QrProfile(
            name = "Test Profile",
            parsingRules = QrParsingRules(delimiter = "-")
        )

        val detectedCodes = listOf(
            RawDetectedCode(
                rawValue = "10-20-30-40",
                uprightBoundingBox = RectF(100f, 100f, 200f, 200f),
                format = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
                formatName = "QR CODE"
            ),
            RawDetectedCode(
                rawValue = "8690504018089",
                uprightBoundingBox = RectF(100f, 300f, 400f, 400f),
                format = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                formatName = "EAN-13"
            ),
            RawDetectedCode(
                rawValue = "PDF417-PASS-OK",
                uprightBoundingBox = RectF(100f, 500f, 500f, 600f),
                format = com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417,
                formatName = "PDF417"
            )
        )

        val tracked = tracker.update(detectedCodes, profile)
        assertEquals(3, tracked.size)

        val qrTracked = tracked.first { it.rawContent == "10-20-30-40" }
        assertEquals("QR CODE", qrTracked.formatName)
        assertEquals(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE, qrTracked.barcodeFormat)

        val eanTracked = tracked.first { it.rawContent == "8690504018089" }
        assertEquals("EAN-13", eanTracked.formatName)
        assertEquals(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13, eanTracked.barcodeFormat)

        val pdfTracked = tracked.first { it.rawContent == "PDF417-PASS-OK" }
        assertEquals("PDF417", pdfTracked.formatName)
        assertEquals(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417, pdfTracked.barcodeFormat)
    }
}
