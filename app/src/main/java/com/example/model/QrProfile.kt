package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.math.RoundingMode
import java.util.UUID

/**
 * Data types supported for QR parsed variables.
 */
enum class FieldDataType {
    INTEGER,
    FLOAT,
    DOUBLE,
    STRING
}

/**
 * Definition of a single variable extracted from QR content.
 */
data class VariableDefinition(
    val id: String = UUID.randomUUID().toString(),
    val position: Int = 0, // 0-indexed position after splitting by delimiter
    val name: String = "a", // e.g., "a", "price", "qty"
    val alias: String = "",
    val dataType: FieldDataType = FieldDataType.DOUBLE,
    val isOptional: Boolean = false,
    val defaultValue: String = "0",
    val overrideBarcode: Boolean = false, // If true, default value overrides scanned barcode token
    val showPill: Boolean = true, // Whether to show an individual pill for this variable in overlay
    val prefix: String = "", // Prefix specific to this variable pill (e.g. $, ₺)
    val postfix: String = "", // Postfix specific to this variable pill (e.g. g, kg, TL)
    val isComputed: Boolean = false, // True if computed from other variables (e.g. price / grams)
    val computationFormula: String = "", // e.g. "price / grams" or "a / b"
    val isManual: Boolean = false, // True if manually added variable not extracted from barcode tokens
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val decimalPlaces: Int? = null, // null = Auto/Inherit from profile, 0 = Integer, 1..6
    val thousandsSeparator: String = "inherit", // "inherit", "none", ",", ".", " ", "'"
    val decimalSeparator: String = "inherit", // "inherit", ".", ","
    val roundingMode: AppRoundingMode? = null, // null = inherit from profile
    val isPercentage: Boolean = false, // If true, treats and formats as percentage
    val negativeFormat: String = "inherit" // "inherit", "-x", "(x)", "-$x"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("position", position)
            put("name", name)
            put("alias", alias)
            put("dataType", dataType.name)
            put("isOptional", isOptional)
            put("defaultValue", defaultValue)
            put("overrideBarcode", overrideBarcode)
            put("showPill", showPill)
            put("prefix", prefix)
            put("postfix", postfix)
            put("isComputed", isComputed)
            put("computationFormula", computationFormula)
            put("isManual", isManual)
            if (minValue != null) put("minValue", minValue)
            if (maxValue != null) put("maxValue", maxValue)
            if (decimalPlaces != null) put("decimalPlaces", decimalPlaces)
            put("thousandsSeparator", thousandsSeparator)
            put("decimalSeparator", decimalSeparator)
            if (roundingMode != null) put("roundingMode", roundingMode.name)
            put("isPercentage", isPercentage)
            put("negativeFormat", negativeFormat)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): VariableDefinition {
            return VariableDefinition(
                id = json.optString("id", UUID.randomUUID().toString()),
                position = json.optInt("position", 0),
                name = json.optString("name", "a"),
                alias = json.optString("alias", ""),
                dataType = runCatching {
                    FieldDataType.valueOf(json.optString("dataType", FieldDataType.DOUBLE.name))
                }.getOrDefault(FieldDataType.DOUBLE),
                isOptional = json.optBoolean("isOptional", false),
                defaultValue = json.optString("defaultValue", "0"),
                overrideBarcode = json.optBoolean("overrideBarcode", false),
                showPill = json.optBoolean("showPill", true),
                prefix = json.optString("prefix", ""),
                postfix = json.optString("postfix", ""),
                isComputed = json.optBoolean("isComputed", false),
                computationFormula = json.optString("computationFormula", ""),
                isManual = json.optBoolean("isManual", false),
                minValue = if (json.has("minValue")) json.optDouble("minValue") else null,
                maxValue = if (json.has("maxValue")) json.optDouble("maxValue") else null,
                decimalPlaces = if (json.has("decimalPlaces") && !json.isNull("decimalPlaces")) json.optInt("decimalPlaces") else null,
                thousandsSeparator = json.optString("thousandsSeparator", "inherit"),
                decimalSeparator = json.optString("decimalSeparator", "inherit"),
                roundingMode = if (json.has("roundingMode")) runCatching {
                    AppRoundingMode.valueOf(json.getString("roundingMode"))
                }.getOrNull() else null,
                isPercentage = json.optBoolean("isPercentage", false),
                negativeFormat = json.optString("negativeFormat", "inherit")
            )
        }
    }
}

/**
 * Rules for splitting and parsing QR content.
 */
data class QrParsingRules(
    val delimiter: String = "-", // e.g. "-", ",", ";", "|", "/", " "
    val customDelimiter: String = "",
    val decimalSeparator: String = ".", // "." or ","
    val trimWhitespace: Boolean = true,
    val allowNegative: Boolean = true,
    val variables: List<VariableDefinition> = listOf(
        VariableDefinition(position = 0, name = "a"),
        VariableDefinition(position = 1, name = "b"),
        VariableDefinition(position = 2, name = "c"),
        VariableDefinition(position = 3, name = "d")
    )
) {
    val effectiveDelimiter: String
        get() = if (customDelimiter.isNotEmpty()) customDelimiter else delimiter

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("delimiter", delimiter)
            put("customDelimiter", customDelimiter)
            put("decimalSeparator", decimalSeparator)
            put("trimWhitespace", trimWhitespace)
            put("allowNegative", allowNegative)
            val varArray = JSONArray()
            variables.forEach { varArray.put(it.toJson()) }
            put("variables", varArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): QrParsingRules {
            val varList = mutableListOf<VariableDefinition>()
            val varArray = json.optJSONArray("variables")
            if (varArray != null) {
                for (i in 0 until varArray.length()) {
                    val obj = varArray.optJSONObject(i)
                    if (obj != null) {
                        varList.add(VariableDefinition.fromJson(obj))
                    }
                }
            } else {
                varList.addAll(
                    listOf(
                        VariableDefinition(position = 0, name = "a"),
                        VariableDefinition(position = 1, name = "b"),
                        VariableDefinition(position = 2, name = "c"),
                        VariableDefinition(position = 3, name = "d")
                    )
                )
            }

            return QrParsingRules(
                delimiter = json.optString("delimiter", "-"),
                customDelimiter = json.optString("customDelimiter", ""),
                decimalSeparator = json.optString("decimalSeparator", "."),
                trimWhitespace = json.optBoolean("trimWhitespace", true),
                allowNegative = json.optBoolean("allowNegative", true),
                variables = varList
            )
        }
    }
}

/**
 * Mathematical formula settings.
 */
data class ArithmeticFormula(
    val expression: String = "a + b + c + d",
    val description: String = "Sum of four values"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("expression", expression)
            put("description", description)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ArithmeticFormula {
            return ArithmeticFormula(
                expression = json.optString("expression", "a + b + c + d"),
                description = json.optString("description", "")
            )
        }
    }
}

/**
 * Rounding mode choices for formatting.
 */
enum class AppRoundingMode(val standardMode: RoundingMode) {
    NO_ROUNDING(RoundingMode.UNNECESSARY),
    ROUND_HALF_UP(RoundingMode.HALF_UP),
    ROUND_HALF_DOWN(RoundingMode.HALF_DOWN),
    ROUND_HALF_EVEN(RoundingMode.HALF_EVEN),
    CEILING(RoundingMode.CEILING),
    FLOOR(RoundingMode.FLOOR),
    TRUNCATE(RoundingMode.DOWN)
}

/**
 * Result formatting options.
 */
data class ResultFormatting(
    val numericType: String = "Double", // Integer, Float, Double
    val roundingMode: AppRoundingMode = AppRoundingMode.ROUND_HALF_UP,
    val decimalPrecision: Int = 2, // 0 to 6
    val prefix: String = "",
    val postfix: String = "",
    val thousandsSeparator: String = "none", // "none", ",", ".", " ", "'"
    val decimalSeparator: String = ".", // "." or ","
    val showLeadingZeros: Boolean = false,
    val minIntegerDigits: Int = 1,
    val negativeFormat: String = "-x" // "-x", "(x)", "-$x"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("numericType", numericType)
            put("roundingMode", roundingMode.name)
            put("decimalPrecision", decimalPrecision)
            put("prefix", prefix)
            put("postfix", postfix)
            put("thousandsSeparator", thousandsSeparator)
            put("decimalSeparator", decimalSeparator)
            put("showLeadingZeros", showLeadingZeros)
            put("minIntegerDigits", minIntegerDigits)
            put("negativeFormat", negativeFormat)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ResultFormatting {
            return ResultFormatting(
                numericType = json.optString("numericType", "Double"),
                roundingMode = runCatching {
                    AppRoundingMode.valueOf(json.optString("roundingMode", AppRoundingMode.ROUND_HALF_UP.name))
                }.getOrDefault(AppRoundingMode.ROUND_HALF_UP),
                decimalPrecision = json.optInt("decimalPrecision", 2),
                prefix = json.optString("prefix", ""),
                postfix = json.optString("postfix", ""),
                thousandsSeparator = json.optString("thousandsSeparator", "none"),
                decimalSeparator = json.optString("decimalSeparator", "."),
                showLeadingZeros = json.optBoolean("showLeadingZeros", false),
                minIntegerDigits = json.optInt("minIntegerDigits", 1),
                negativeFormat = json.optString("negativeFormat", "-x")
            )
        }
    }
}

/**
 * Anchor positions for the visual overlay relative to the QR code.
 */
enum class OverlayAnchorPosition {
    CENTER,
    ABOVE,
    BELOW,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * Background style for overlay: Solid, Gradient, OutlineOnly, Transparent
 */
enum class OverlayFillMode {
    FILLED_SOLID,
    FILLED_GRADIENT,
    OUTLINE_ONLY,
    SEMI_TRANSPARENT
}

/**
 * Border style: Solid, Dashed
 */
enum class BorderStyle {
    SOLID,
    DASHED
}

/**
 * Source type for a cell in the Mini Excel Table overlay.
 */
enum class TableItemSource {
    VARIABLE,        // Extracted token variable (e.g. price, grams)
    COMPUTED,        // Computed variable (e.g. price_per_gram)
    FORMULA_RESULT,  // Main evaluated formula result
    RAW_QR,          // Raw scanned QR text
    CUSTOM_TEXT      // Static custom label/value
}

/**
 * Display modes for the visual overlay.
 */
enum class OverlayDisplayMode {
    EXCEL_TABLE,    // Mini Excel Spreadsheet grid
    COMPACT_CARD,   // Classic compact card with pills
    HYBRID          // Both compact summary card and Excel table
}

/**
 * Themes for the Excel grid overlay.
 */
enum class ExcelGridTheme {
    EXCEL_CLASSIC,  // Microsoft Excel dark emerald green (#107C41 / #22C55E)
    NEON_CYAN,      // Cyber Cyan (#00F5D4)
    MODERN_SLATE,   // Crisp Ice Blue & Slate (#38BDF8 / #0F172A)
    AMBER_GOLD      // Financial Amber & Gold (#F59E0B)
}

/**
 * Configuration for an individual cell/row in the Mini Excel Table.
 */
data class ExcelTableCellItem(
    val id: String = UUID.randomUUID().toString(),
    val source: TableItemSource = TableItemSource.VARIABLE,
    val variableName: String = "",
    val label: String = "",
    val isVisible: Boolean = true,
    val prefix: String = "",
    val postfix: String = "",
    val isEmphasized: Boolean = false,
    val customValue: String = "",
    val coordinate: String = "" // e.g. "A1", "A2", "B1" or auto-generated
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("source", source.name)
            put("variableName", variableName)
            put("label", label)
            put("isVisible", isVisible)
            put("prefix", prefix)
            put("postfix", postfix)
            put("isEmphasized", isEmphasized)
            put("customValue", customValue)
            put("coordinate", coordinate)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ExcelTableCellItem {
            return ExcelTableCellItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                source = runCatching {
                    TableItemSource.valueOf(json.optString("source", TableItemSource.VARIABLE.name))
                }.getOrDefault(TableItemSource.VARIABLE),
                variableName = json.optString("variableName", ""),
                label = json.optString("label", ""),
                isVisible = json.optBoolean("isVisible", true),
                prefix = json.optString("prefix", ""),
                postfix = json.optString("postfix", ""),
                isEmphasized = json.optBoolean("isEmphasized", false),
                customValue = json.optString("customValue", ""),
                coordinate = json.optString("coordinate", "")
            )
        }
    }
}

/**
 * Configuration for the Mini Excel Table overlay layout.
 */
data class ExcelTableConfig(
    val isEnabled: Boolean = true,
    val showHeader: Boolean = true,
    val headerTitle: String = "DATA SHEET",
    val headerSubtitle: String = "QR SPREADSHEET",
    val showColumnHeaders: Boolean = true,
    val showCoordinates: Boolean = true,
    val gridTheme: ExcelGridTheme = ExcelGridTheme.EXCEL_CLASSIC,
    val showGridLines: Boolean = true,
    val items: List<ExcelTableCellItem> = emptyList()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("isEnabled", isEnabled)
            put("showHeader", showHeader)
            put("headerTitle", headerTitle)
            put("headerSubtitle", headerSubtitle)
            put("showColumnHeaders", showColumnHeaders)
            put("showCoordinates", showCoordinates)
            put("gridTheme", gridTheme.name)
            put("showGridLines", showGridLines)
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            put("items", arr)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ExcelTableConfig {
            val list = mutableListOf<ExcelTableCellItem>()
            val arr = json.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        list.add(ExcelTableCellItem.fromJson(obj))
                    }
                }
            }
            return ExcelTableConfig(
                isEnabled = json.optBoolean("isEnabled", true),
                showHeader = json.optBoolean("showHeader", true),
                headerTitle = json.optString("headerTitle", "DATA SHEET"),
                headerSubtitle = json.optString("headerSubtitle", "QR SPREADSHEET"),
                showColumnHeaders = json.optBoolean("showColumnHeaders", true),
                showCoordinates = json.optBoolean("showCoordinates", true),
                gridTheme = runCatching {
                    ExcelGridTheme.valueOf(json.optString("gridTheme", ExcelGridTheme.EXCEL_CLASSIC.name))
                }.getOrDefault(ExcelGridTheme.EXCEL_CLASSIC),
                showGridLines = json.optBoolean("showGridLines", true),
                items = list
            )
        }
    }
}

/**
 * Advanced Visual Overlay configuration (CSS-inspired parameters translated to native Compose).
 */
data class OverlayAppearance(
    val anchorPosition: OverlayAnchorPosition = OverlayAnchorPosition.CENTER,
    val offsetX: Float = 0f, // dp offset
    val offsetY: Float = 0f,
    val autoAvoidEdges: Boolean = true,

    // Dimensions
    val autoWidth: Boolean = true,
    val customWidth: Float = 180f,
    val autoHeight: Boolean = true,
    val customHeight: Float = 70f,
    val scale: Float = 1.0f,

    // Typography
    val fontSizeSp: Float = 20f,
    val isBold: Boolean = true,
    val isItalic: Boolean = false,
    val textAlignCenter: Boolean = true,
    val letterSpacingSp: Float = 0.5f,
    val textColorHex: String = "#00F5D4", // Neon Cyan default
    val showTextShadow: Boolean = true,
    val textShadowColorHex: String = "#000000",

    // Background & Fill
    val fillMode: OverlayFillMode = OverlayFillMode.SEMI_TRANSPARENT,
    val backgroundColorHex: String = "#0F172A", // Deep Navy Slate
    val gradientColorEndHex: String = "#1E1B4B", // Indigo
    val backgroundOpacity: Float = 0.90f,

    // Border
    val showBorder: Boolean = true,
    val borderColorHex: String = "#00F5D4",
    val borderWidthDp: Float = 2f,
    val borderStyle: BorderStyle = BorderStyle.SOLID,
    val cornerRadiusDp: Float = 16f,

    // Effects & Layout
    val showGlow: Boolean = true,
    val showDropShadow: Boolean = true,
    val paddingDp: Float = 12f,
    val showVariableSummaryPill: Boolean = true,
    val showCalculationFormulaHint: Boolean = true,
    val showRawQrPill: Boolean = false,
    val hiddenPillVariables: List<String> = emptyList(), // Individually hidden variables in pills
    val iconName: String = "calculator", // "calculator", "tag", "qr", "sparkle", "none"
    val displayMode: OverlayDisplayMode = OverlayDisplayMode.EXCEL_TABLE,
    val tableConfig: ExcelTableConfig = ExcelTableConfig(),

    // Callout & Bonded Leader Line Layout
    val calloutMode: CalloutLayoutMode = CalloutLayoutMode.SMART_ANTI_COLLISION,
    val leaderLineStyle: LeaderLineStyle = LeaderLineStyle.DASHED,
    val showAnchorDot: Boolean = true,
    val showBarcodeDashedOutline: Boolean = true,
    val leaderLineWidthDp: Float = 1.5f,
    val leaderLineColorHex: String = "" // empty = use borderColorHex
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("anchorPosition", anchorPosition.name)
            put("offsetX", offsetX.toDouble())
            put("offsetY", offsetY.toDouble())
            put("autoAvoidEdges", autoAvoidEdges)
            put("autoWidth", autoWidth)
            put("customWidth", customWidth.toDouble())
            put("autoHeight", autoHeight)
            put("customHeight", customHeight.toDouble())
            put("scale", scale.toDouble())
            put("fontSizeSp", fontSizeSp.toDouble())
            put("isBold", isBold)
            put("isItalic", isItalic)
            put("textAlignCenter", textAlignCenter)
            put("letterSpacingSp", letterSpacingSp.toDouble())
            put("textColorHex", textColorHex)
            put("showTextShadow", showTextShadow)
            put("textShadowColorHex", textShadowColorHex)
            put("fillMode", fillMode.name)
            put("backgroundColorHex", backgroundColorHex)
            put("gradientColorEndHex", gradientColorEndHex)
            put("backgroundOpacity", backgroundOpacity.toDouble())
            put("showBorder", showBorder)
            put("borderColorHex", borderColorHex)
            put("borderWidthDp", borderWidthDp.toDouble())
            put("borderStyle", borderStyle.name)
            put("cornerRadiusDp", cornerRadiusDp.toDouble())
            put("showGlow", showGlow)
            put("showDropShadow", showDropShadow)
            put("paddingDp", paddingDp.toDouble())
            put("showVariableSummaryPill", showVariableSummaryPill)
            put("showCalculationFormulaHint", showCalculationFormulaHint)
            put("showRawQrPill", showRawQrPill)
            val hiddenArray = JSONArray()
            hiddenPillVariables.forEach { hiddenArray.put(it) }
            put("hiddenPillVariables", hiddenArray)
            put("iconName", iconName)
            put("displayMode", displayMode.name)
            put("tableConfig", tableConfig.toJson())
            put("calloutMode", calloutMode.name)
            put("leaderLineStyle", leaderLineStyle.name)
            put("showAnchorDot", showAnchorDot)
            put("showBarcodeDashedOutline", showBarcodeDashedOutline)
            put("leaderLineWidthDp", leaderLineWidthDp.toDouble())
            put("leaderLineColorHex", leaderLineColorHex)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): OverlayAppearance {
            val hiddenList = mutableListOf<String>()
            val hArray = json.optJSONArray("hiddenPillVariables")
            if (hArray != null) {
                for (i in 0 until hArray.length()) {
                    hiddenList.add(hArray.optString(i))
                }
            }
            return OverlayAppearance(
                anchorPosition = runCatching {
                    OverlayAnchorPosition.valueOf(json.optString("anchorPosition", OverlayAnchorPosition.CENTER.name))
                }.getOrDefault(OverlayAnchorPosition.CENTER),
                offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                autoAvoidEdges = json.optBoolean("autoAvoidEdges", true),
                autoWidth = json.optBoolean("autoWidth", true),
                customWidth = json.optDouble("customWidth", 180.0).toFloat(),
                autoHeight = json.optBoolean("autoHeight", true),
                customHeight = json.optDouble("customHeight", 70.0).toFloat(),
                scale = json.optDouble("scale", 1.0).toFloat(),
                fontSizeSp = json.optDouble("fontSizeSp", 20.0).toFloat(),
                isBold = json.optBoolean("isBold", true),
                isItalic = json.optBoolean("isItalic", false),
                textAlignCenter = json.optBoolean("textAlignCenter", true),
                letterSpacingSp = json.optDouble("letterSpacingSp", 0.5).toFloat(),
                textColorHex = json.optString("textColorHex", "#00F5D4"),
                showTextShadow = json.optBoolean("showTextShadow", true),
                textShadowColorHex = json.optString("textShadowColorHex", "#000000"),
                fillMode = runCatching {
                    OverlayFillMode.valueOf(json.optString("fillMode", OverlayFillMode.SEMI_TRANSPARENT.name))
                }.getOrDefault(OverlayFillMode.SEMI_TRANSPARENT),
                backgroundColorHex = json.optString("backgroundColorHex", "#0F172A"),
                gradientColorEndHex = json.optString("gradientColorEndHex", "#1E1B4B"),
                backgroundOpacity = json.optDouble("backgroundOpacity", 0.90).toFloat(),
                showBorder = json.optBoolean("showBorder", true),
                borderColorHex = json.optString("borderColorHex", "#00F5D4"),
                borderWidthDp = json.optDouble("borderWidthDp", 2.0).toFloat(),
                borderStyle = runCatching {
                    BorderStyle.valueOf(json.optString("borderStyle", BorderStyle.SOLID.name))
                }.getOrDefault(BorderStyle.SOLID),
                cornerRadiusDp = json.optDouble("cornerRadiusDp", 16.0).toFloat(),
                showGlow = json.optBoolean("showGlow", true),
                showDropShadow = json.optBoolean("showDropShadow", true),
                paddingDp = json.optDouble("paddingDp", 12.0).toFloat(),
                showVariableSummaryPill = json.optBoolean("showVariableSummaryPill", true),
                showCalculationFormulaHint = json.optBoolean("showCalculationFormulaHint", true),
                showRawQrPill = json.optBoolean("showRawQrPill", false),
                hiddenPillVariables = hiddenList,
                iconName = json.optString("iconName", "calculator"),
                displayMode = runCatching {
                    OverlayDisplayMode.valueOf(json.optString("displayMode", OverlayDisplayMode.EXCEL_TABLE.name))
                }.getOrDefault(OverlayDisplayMode.EXCEL_TABLE),
                tableConfig = if (json.has("tableConfig")) {
                    ExcelTableConfig.fromJson(json.getJSONObject("tableConfig"))
                } else {
                    ExcelTableConfig()
                },
                calloutMode = runCatching {
                    CalloutLayoutMode.valueOf(json.optString("calloutMode", CalloutLayoutMode.SMART_ANTI_COLLISION.name))
                }.getOrDefault(CalloutLayoutMode.SMART_ANTI_COLLISION),
                leaderLineStyle = runCatching {
                    LeaderLineStyle.valueOf(json.optString("leaderLineStyle", LeaderLineStyle.DASHED.name))
                }.getOrDefault(LeaderLineStyle.DASHED),
                showAnchorDot = json.optBoolean("showAnchorDot", true),
                showBarcodeDashedOutline = json.optBoolean("showBarcodeDashedOutline", true),
                leaderLineWidthDp = json.optDouble("leaderLineWidthDp", 1.5).toFloat(),
                leaderLineColorHex = json.optString("leaderLineColorHex", "")
            )
        }
    }
}

/**
 * Callout placement and leader line bonding behavior.
 */
enum class CalloutLayoutMode {
    DIRECT_FOLLOW,          // Attached directly to QR anchor
    SMART_ANTI_COLLISION,   // Auto-repulsion layout with bonded leader line
    DOCKED_SIDEBAR          // Pinned neatly to side margin rail with bonded leader line
}

/**
 * Visual styling for the bonded leader line connecting the barcode to the data box.
 */
enum class LeaderLineStyle {
    DASHED,                 // Technical HUD dashed line
    THIN_SOLID,             // Modern glowing continuous line
    ELBOW_JOINT             // Orthogonal 90-degree HUD elbow
}

/**
 * Tracking & Camera behavior configuration.
 */
data class TrackingSettings(
    val smoothingFactor: Float = 0.45f, // EMA smoothing for bounding box tracking (0.0 to 1.0)
    val disappearanceTimeoutMs: Long = 750L, // How long overlay remains after QR is lost
    val maxTrackedCodes: Int = 10,
    val useFrontCamera: Boolean = false,
    val enableTorch: Boolean = false,
    val zoomLevel: Float = 1.0f,
    val isScanningPaused: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("smoothingFactor", smoothingFactor.toDouble())
            put("disappearanceTimeoutMs", disappearanceTimeoutMs)
            put("maxTrackedCodes", maxTrackedCodes)
            put("useFrontCamera", useFrontCamera)
            put("enableTorch", enableTorch)
            put("zoomLevel", zoomLevel.toDouble())
            put("isScanningPaused", isScanningPaused)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TrackingSettings {
            return TrackingSettings(
                smoothingFactor = json.optDouble("smoothingFactor", 0.45).toFloat(),
                disappearanceTimeoutMs = json.optLong("disappearanceTimeoutMs", 750L),
                maxTrackedCodes = json.optInt("maxTrackedCodes", 10),
                useFrontCamera = json.optBoolean("useFrontCamera", false),
                enableTorch = json.optBoolean("enableTorch", false),
                zoomLevel = json.optDouble("zoomLevel", 1.0).toFloat(),
                isScanningPaused = json.optBoolean("isScanningPaused", false)
            )
        }
    }
}

/**
 * A complete user configuration profile containing all rules, formula, format, and styling.
 */
data class QrProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default Profile",
    val description: String = "Standard 4-field sum calculator (a-b-c-d)",
    val triggerSignal: String = "", // QR prefix, keyword or tag (e.g. "[P:gold]" or "TESBIH-") to auto-switch profile
    val parsingRules: QrParsingRules = QrParsingRules(),
    val formula: ArithmeticFormula = ArithmeticFormula(),
    val formatting: ResultFormatting = ResultFormatting(),
    val overlay: OverlayAppearance = OverlayAppearance(),
    val tracking: TrackingSettings = TrackingSettings()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("triggerSignal", triggerSignal)
            put("parsingRules", parsingRules.toJson())
            put("formula", formula.toJson())
            put("formatting", formatting.toJson())
            put("overlay", overlay.toJson())
            put("tracking", tracking.toJson())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): QrProfile {
            return QrProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Default Profile"),
                description = json.optString("description", ""),
                triggerSignal = json.optString("triggerSignal", ""),
                parsingRules = if (json.has("parsingRules")) QrParsingRules.fromJson(json.getJSONObject("parsingRules")) else QrParsingRules(),
                formula = if (json.has("formula")) ArithmeticFormula.fromJson(json.getJSONObject("formula")) else ArithmeticFormula(),
                formatting = if (json.has("formatting")) ResultFormatting.fromJson(json.getJSONObject("formatting")) else ResultFormatting(),
                overlay = if (json.has("overlay")) OverlayAppearance.fromJson(json.getJSONObject("overlay")) else OverlayAppearance(),
                tracking = if (json.has("tracking")) TrackingSettings.fromJson(json.getJSONObject("tracking")) else TrackingSettings()
            )
        }
    }
}
