package com.example

import android.graphics.RectF
import com.example.camera.QrTracker
import com.example.camera.RawDetectedCode
import com.example.data.ProfileRepository
import com.example.engine.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
