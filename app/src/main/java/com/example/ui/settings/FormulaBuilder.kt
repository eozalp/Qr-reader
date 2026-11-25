package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathValueResult
import com.example.engine.ResultFormatter
import com.example.model.FieldDataType
import com.example.model.GlobalVariable
import com.example.model.ResultFormatting
import com.example.model.VariableDefinition
import com.example.ui.theme.AppMonoFontFamily
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class BuilderToolCategory(val label: String) {
    VARIABLES("Değişkenler"),
    RECIPES("Kalıplar"),
    OPERATORS("Operatörler"),
    MATH("Matematik"),
    DATE_TEXT("Tarih & Metin")
}

private class FormulaSyntaxVisualTransformation(
    private val variableNames: Set<String>,
    private val varColor: Color,
    private val globalColor: Color,
    private val funcColor: Color,
    private val stringColor: Color,
    private val numberColor: Color,
    private val operatorColor: Color
) : VisualTransformation {

    private val tokenRegex = Regex(
        "(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*')|" + // 1: string literal
            "(@[A-Za-z_][A-Za-z0-9_]*)|" +              // 2: @global variable
            "([A-Za-z_][A-Za-z0-9_]*)(?=\\s*\\()|" +    // 3: function call
            "([A-Za-z_][A-Za-z0-9_]*)|" +               // 4: identifier/variable
            "(\\d+(?:\\.\\d+)?)|" +                     // 5: number
            "(==|!=|<=|>=|&&|\\|\\||[+\\-*/%^<>!?=:])"  // 6: operator
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = buildAnnotatedString {
            append(raw)
            tokenRegex.findAll(raw).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val style = when {
                    match.groups[1] != null -> SpanStyle(color = stringColor, fontWeight = FontWeight.Medium)
                    match.groups[2] != null -> SpanStyle(color = globalColor, fontWeight = FontWeight.ExtraBold)
                    match.groups[3] != null -> SpanStyle(color = funcColor, fontWeight = FontWeight.Bold)
                    match.groups[4] != null -> {
                        val word = match.value.lowercase(java.util.Locale.ROOT)
                        if (word in variableNames || word in setOf("bugun", "today", "bu_yil", "bu_ay", "bu_gun")) {
                            SpanStyle(color = varColor, fontWeight = FontWeight.Bold)
                        } else if (word in setOf("ve", "veya", "degil", "and", "or", "not", "true", "false", "dogru", "yanlis")) {
                            SpanStyle(color = operatorColor, fontWeight = FontWeight.Bold)
                        } else {
                            null
                        }
                    }
                    match.groups[5] != null -> SpanStyle(color = numberColor, fontWeight = FontWeight.SemiBold)
                    match.groups[6] != null -> SpanStyle(color = operatorColor, fontWeight = FontWeight.ExtraBold)
                    else -> null
                }
                if (style != null) {
                    addStyle(style, start, end)
                }
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

/**
 * Generalist interactive Calculation Builder allowing users to compose numeric, text, date,
 * or boolean formulas from profile variables, @global shared constants, operators, recipes,
 * math tools, calendar primitives, and string tools — with syntax highlighting, undo/redo,
 * cursor-safe insertion, and live multi-type preview.
 */
@Composable
fun FormulaBuilderPanel(
    expression: String,
    onExpressionChanged: (String) -> Unit,
    variables: List<VariableDefinition>,
    globalVariables: List<GlobalVariable> = emptyList(),
    liveNumericVars: Map<String, Double> = emptyMap(),
    liveStringVars: Map<String, String> = emptyMap(),
    formatting: ResultFormatting? = null,
    previewVariable: VariableDefinition? = null,
    label: String = "Hesaplama İfadesi",
    placeholder: String = "örn: if(adet < min_stok, \"KRİTİK\", \"OK\") veya lt * @motorin_lt",
    testTagPrefix: String = "main_formula",
    onRequestAddVariable: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeCategory by remember { mutableStateOf(BuilderToolCategory.VARIABLES) }

    // Cursor-safe TextFieldValue: never reset cursor to end when parent echoes our own edit!
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(expression, TextRange(expression.length)))
    }
    LaunchedEffect(expression) {
        if (expression != textFieldValue.text) {
            textFieldValue = TextFieldValue(expression, TextRange(expression.length))
        }
    }

    // Undo / Redo stacks
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }

    fun pushExpression(updated: String, newCursor: Int) {
        if (updated != textFieldValue.text) {
            undoStack.add(textFieldValue.text)
            if (undoStack.size > 40) undoStack.removeAt(0)
            redoStack.clear()
        }
        textFieldValue = TextFieldValue(updated, TextRange(newCursor.coerceIn(0, updated.length)))
        onExpressionChanged(updated)
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(textFieldValue.text)
            textFieldValue = TextFieldValue(prev, TextRange(prev.length))
            onExpressionChanged(prev)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(textFieldValue.text)
            textFieldValue = TextFieldValue(next, TextRange(next.length))
            onExpressionChanged(next)
        }
    }

    val evaluator = remember { MathExpressionEvaluator() }
    val liveEvalResult = remember(expression, liveNumericVars, liveStringVars) {
        if (expression.isBlank()) null
        else evaluator.evaluateValue(expression, liveNumericVars, liveStringVars)
    }

    val knownVarNames = remember(variables) {
        buildSet {
            variables.forEach { v ->
                val lowerName = v.name.lowercase(java.util.Locale.ROOT)
                add(lowerName)
                if (v.alias.isNotBlank()) add(v.alias.lowercase(java.util.Locale.ROOT))
                if (v.dataType == FieldDataType.DATE) {
                    add("${lowerName}_gun")
                    add("${lowerName}_ay")
                    add("${lowerName}_yil")
                }
            }
        }
    }

    val isDark = MaterialTheme.colorScheme.surface.red < 0.5f
    val syntaxVisual = remember(knownVarNames, isDark) {
        FormulaSyntaxVisualTransformation(
            variableNames = knownVarNames,
            varColor = if (isDark) Color(0xFF22D3EE) else Color(0xFF0E7490),
            globalColor = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309),
            funcColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9),
            stringColor = if (isDark) Color(0xFF34D399) else Color(0xFF047857),
            numberColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF1D4ED8),
            operatorColor = if (isDark) Color(0xFFFB7185) else Color(0xFFBE123C)
        )
    }

    fun insertAtCursor(rawInsert: String, smartSpaces: Boolean = true, cursorBackOffset: Int = 0) {
        val text = textFieldValue.text
        val selStart = textFieldValue.selection.start.coerceIn(0, text.length)
        val selEnd = textFieldValue.selection.end.coerceIn(0, text.length)

        val insertion = if (smartSpaces) {
            val needLeadingSpace = selStart > 0 &&
                !text[selStart - 1].isWhitespace() &&
                text[selStart - 1] !in setOf('(', ',', '"', '\'') &&
                rawInsert !in setOf("(", ")", ",", "%", "\"\"")
            val prefix = if (needLeadingSpace) " " else ""
            "$prefix$rawInsert"
        } else {
            rawInsert
        }

        val updated = text.replaceRange(selStart, selEnd, insertion)
        val newCursor = (selStart + insertion.length - cursorBackOffset).coerceIn(0, updated.length)
        pushExpression(updated, newCursor)
    }

    fun backspaceAtCursor() {
        val text = textFieldValue.text
        val selStart = textFieldValue.selection.start.coerceIn(0, text.length)
        val selEnd = textFieldValue.selection.end.coerceIn(0, text.length)
        if (selStart != selEnd) {
            val updated = text.removeRange(selStart, selEnd)
            pushExpression(updated, selStart)
        } else if (selStart > 0) {
            val updated = text.removeRange(selStart - 1, selStart)
            pushExpression(updated, selStart - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Syntax-Highlighted Expression Editor
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { next ->
                if (next.text != textFieldValue.text) {
                    undoStack.add(textFieldValue.text)
                    if (undoStack.size > 40) undoStack.removeAt(0)
                    redoStack.clear()
                }
                textFieldValue = next
                onExpressionChanged(next.text)
            },
            label = { Text(label) },
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            visualTransformation = syntaxVisual,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = AppMonoFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_input"),
            maxLines = 4
        )

        // 1B. Compact Editor Action Toolbar (Undo / Redo / Backspace / Clear)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { undo() },
                enabled = undoStack.isNotEmpty(),
                modifier = Modifier.size(36.dp).testTag("${testTagPrefix}_undo")
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Geri Al", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { redo() },
                enabled = redoStack.isNotEmpty(),
                modifier = Modifier.size(36.dp).testTag("${testTagPrefix}_redo")
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Yinele", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { backspaceAtCursor() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Geri Sil", modifier = Modifier.size(18.dp))
            }
            if (textFieldValue.text.isNotEmpty()) {
                IconButton(
                    onClick = { pushExpression("", 0) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Temizle", modifier = Modifier.size(18.dp))
                }
            }
        }

        // 2. Live Multi-Type Result / Syntax Feedback Pill
        if (liveEvalResult != null) {
            Spacer(modifier = Modifier.height(4.dp))
            when (liveEvalResult) {
                is MathValueResult.SuccessNum -> {
                    val formatted = when {
                        previewVariable?.dataType == FieldDataType.DATE -> {
                            runCatching {
                                val d = LocalDate.ofEpochDay(liveEvalResult.value.toLong())
                                "${previewVariable.prefix}${d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}${previewVariable.postfix}"
                            }.getOrElse { liveEvalResult.value.toString() }
                        }
                        previewVariable?.dataType == FieldDataType.BOOLEAN -> {
                            if (kotlin.math.abs(liveEvalResult.value) > 1e-12) "Doğru" else "Yanlış"
                        }
                        previewVariable != null -> ResultFormatter.formatVariable(
                            value = liveEvalResult.value,
                            varDef = previewVariable,
                            profileFormatting = formatting,
                            includePrefixPostfix = true
                        )
                        formatting != null -> ResultFormatter.format(liveEvalResult.value, formatting)
                        else -> liveEvalResult.value.toString()
                    }
                    LiveFormulaResultPill(label = "Canlı Sonuç:", value = formatted, isError = false)
                }
                is MathValueResult.SuccessText -> {
                    val pfx = previewVariable?.prefix ?: ""
                    val sfx = previewVariable?.postfix ?: ""
                    LiveFormulaResultPill(label = "Canlı Metin:", value = "$pfx${liveEvalResult.text}$sfx", isError = false)
                }
                is MathValueResult.Error -> {
                    LiveFormulaResultPill(label = "", value = liveEvalResult.message, isError = true)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Builder Tool Category Selector Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BuilderToolCategory.entries.forEach { cat ->
                val selected = activeCategory == cat
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { activeCategory = cat }
                        .testTag("${testTagPrefix}_cat_${cat.name}")
                ) {
                    Text(
                        text = cat.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Category Tool Palette
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (activeCategory) {
                    BuilderToolCategory.VARIABLES -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Profil Değişkenleri",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (onRequestAddVariable != null) {
                                TextButton(
                                    onClick = onRequestAddVariable,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Yeni Ekle", fontSize = 11.sp)
                                }
                            }
                        }

                        if (variables.isEmpty()) {
                            Text(
                                text = "Henüz başka değişken tanımlanmadı.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                variables.forEach { v ->
                                    val typeShort = when (v.dataType) {
                                        FieldDataType.INTEGER -> "INT"
                                        FieldDataType.FLOAT -> "FLT"
                                        FieldDataType.DOUBLE -> "SAYI"
                                        FieldDataType.BOOLEAN -> "BOOL"
                                        FieldDataType.STRING -> "METİN"
                                        FieldDataType.DATE -> "TARİH"
                                    }
                                    val previewVal = liveStringVars[v.name]
                                        ?: liveNumericVars[v.name]?.let {
                                            if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e12) it.toLong().toString()
                                            else String.format("%.2f", it)
                                        }
                                    BuilderInsertChip(
                                        title = v.name,
                                        subtitle = previewVal?.let { "$typeShort • $it" } ?: typeShort,
                                        accent = v.dataType == FieldDataType.DATE || v.dataType == FieldDataType.STRING,
                                        onClick = { insertAtCursor(v.name) }
                                    )
                                }
                            }

                            val dateVars = variables.filter { it.dataType == FieldDataType.DATE }
                            if (dateVars.isNotEmpty()) {
                                Text(
                                    text = "Tarih Fark Değişkenleri:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    dateVars.forEach { dv ->
                                        listOf(
                                            "${dv.name}_gun" to "Geçen gün",
                                            "${dv.name}_ay" to "Geçen ay",
                                            "${dv.name}_yil" to "Geçen yıl"
                                        ).forEach { (tok, desc) ->
                                            BuilderInsertChip(
                                                title = tok,
                                                subtitle = desc,
                                                onClick = { insertAtCursor(tok) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (globalVariables.isNotEmpty()) {
                            Text(
                                text = "Ortak @Global Sabitler (Tüm Profillerde):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                globalVariables.forEach { gv ->
                                    val token = "@${gv.name.removePrefix("@")}"
                                    BuilderInsertChip(
                                        title = token,
                                        subtitle = gv.value,
                                        accent = true,
                                        onClick = { insertAtCursor(token) }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Sistem Sabitleri:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "bugun" to "Bugün (tarih)",
                                "bu_yil" to "Bu yıl",
                                "bu_ay" to "Bu ay (1-12)",
                                "bu_gun" to "Bu gün (1-31)",
                                "true" to "Doğru (1)",
                                "false" to "Yanlış (0)",
                                "pi" to "π"
                            ).forEach { (sym, hint) ->
                                BuilderInsertChip(
                                    title = sym,
                                    subtitle = hint,
                                    onClick = { insertAtCursor(sym) }
                                )
                            }
                        }
                    }

                    BuilderToolCategory.RECIPES -> {
                        val v1 = variables.firstOrNull { !it.isComputed }?.name ?: "a"
                        val v2 = variables.filter { !it.isComputed }.getOrNull(1)?.name ?: "b"
                        Text(
                            text = "Evrensel Hazır Kalıplar (Tek Tıkla Ekle)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Triple("if($v1 < 5, \"KRİTİK\", \"YETERLİ\")", "Eğer-İse Durum", "Stok / eşik etiketi"),
                                Triple("switch($v1, \"A\", 10, \"B\", 20, 0)", "Çoklu Eşleme", "Tip/kod → fiyat/değer"),
                                Triple("$v1 * (1 + $v2 / 100)", "+% Ekle", "KDV veya kâr ekle"),
                                Triple("$v1 * (1 - $v2 / 100)", "-% İndirim", "Yüzde iskonto"),
                                Triple("gun_ekle($v1, 14)", "Tarih + Gün", "İade / son kullanma"),
                                Triple("max(0, gun_farki(bugun, $v1))", "Gecikme / Yaş", "Bugünden gün farkı"),
                                Triple("birlestir($v1, \" / \", $v2)", "Metin Birleştir", "Raf + kod etiketi"),
                                Triple("round($v1 / $v2, 2)", "Birim Oran", "Litre / gram / adet başı")
                            ).forEach { (snippet, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    accent = true,
                                    onClick = { insertAtCursor(snippet) }
                                )
                            }
                        }
                    }

                    BuilderToolCategory.OPERATORS -> {
                        Text(
                            text = "Aritmetik & Parantez",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("+", "-", "*", "/", "%", "^", "(", ")", ",").forEach { op ->
                                OperatorKeyButton(
                                    label = op,
                                    onClick = {
                                        val smart = op !in listOf("(", ")", ",", "%")
                                        insertAtCursor(if (op == ",") ", " else op, smartSpaces = smart)
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Karşılaştırma, Mantık & Koşul",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BuilderInsertChip(
                                title = "if( , , )",
                                subtitle = "Eğer koşul",
                                accent = true,
                                onClick = { insertAtCursor("if(, , )", cursorBackOffset = 5) }
                            )
                            BuilderInsertChip(
                                title = "switch(...)",
                                subtitle = "Çoklu eşleme",
                                accent = true,
                                onClick = { insertAtCursor("switch(, \"\", , 0)", cursorBackOffset = 10) }
                            )
                            BuilderInsertChip(
                                title = "aralik()",
                                subtitle = "Min..Max arası",
                                onClick = { insertAtCursor("aralik(, 0, 100)", cursorBackOffset = 9) }
                            )
                            listOf(
                                "==" to "Eşit",
                                "!=" to "Eşit değil",
                                "<" to "Küçük",
                                "<=" to "Küçük/Eşit",
                                ">" to "Büyük",
                                ">=" to "Büyük/Eşit",
                                "&&" to "VE",
                                "||" to "VEYA",
                                "!" to "DEĞİL"
                            ).forEach { (op, hint) ->
                                BuilderInsertChip(
                                    title = op,
                                    subtitle = hint,
                                    onClick = { insertAtCursor(op) }
                                )
                            }
                            BuilderInsertChip(
                                title = "\"\"",
                                subtitle = "Metin tırnağı",
                                onClick = { insertAtCursor("\"\"", smartSpaces = false, cursorBackOffset = 1) }
                            )
                        }

                        Text(
                            text = "Hızlı Sayı Tuşları",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ".").forEach { digit ->
                                OperatorKeyButton(
                                    label = digit,
                                    onClick = { insertAtCursor(digit, smartSpaces = false) }
                                )
                            }
                        }
                    }

                    BuilderToolCategory.MATH -> {
                        Text(
                            text = "Matematik & Yuvarlama Araçları (Oto Parantez)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Quad("round(, 2)", 4, "round(x, 2)", "Yuvarla"),
                                Quad("ceil()", 1, "ceil(x)", "Yukarı yuvarla"),
                                Quad("floor()", 1, "floor(x)", "Aşağı yuvarla"),
                                Quad("trunc()", 1, "trunc(x)", "Tam kısım"),
                                Quad("abs()", 1, "abs(x)", "Mutlak değer"),
                                Quad("min(, )", 3, "min(a, b)", "En küçük"),
                                Quad("max(, )", 3, "max(a, b)", "En büyük"),
                                Quad("avg(, )", 3, "avg(a, b)", "Ortalama"),
                                Quad("sum(, )", 3, "sum(a, b)", "Toplam"),
                                Quad("clamp(, 0, 100)", 9, "clamp(x, lo, hi)", "Sınırla"),
                                Quad("pow(, 2)", 4, "pow(a, b)", "Üs a^b"),
                                Quad("sqrt()", 1, "sqrt(x)", "Karekök"),
                                Quad("ln()", 1, "ln(x)", "Doğal log"),
                                Quad("log10()", 1, "log10(x)", "Log 10")
                            ).forEach { (insert, backOffset, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert, cursorBackOffset = backOffset) }
                                )
                            }
                        }
                    }

                    BuilderToolCategory.DATE_TEXT -> {
                        Text(
                            text = "Tarih & Takvim Araçları",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Quad("gun_ekle(, 14)", 5, "gun_ekle(t, n)", "Gün ekle / çıkar"),
                                Quad("gun_farki(bugun, )", 1, "gun_farki(t2, t1)", "Gün farkı"),
                                Quad("ay_farki(bugun, )", 1, "ay_farki(t2, t1)", "Ay farkı"),
                                Quad("yil_farki(bugun, )", 1, "yil_farki(t2, t1)", "Yıl farkı"),
                                Quad("tarih_metin()", 1, "tarih_metin(t)", "gg.aa.yyyy metin"),
                                Quad("date(2026, 1, 1)", 1, "date(y, a, g)", "Tarih üret"),
                                Quad("year()", 1, "year(tarih)", "Yılı al"),
                                Quad("month()", 1, "month(tarih)", "Ayı al"),
                                Quad("day()", 1, "day(tarih)", "Günü al")
                            ).forEach { (insert, backOffset, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert, cursorBackOffset = backOffset) }
                                )
                            }
                        }

                        Text(
                            text = "Metin & Dönüşüm Araçları",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Quad("birlestir(, \" \", )", 7, "birlestir(a, b)", "Metin birleştir"),
                                Quad("degistir(, \"\", \"\")", 9, "degistir(s, e, y)", "Metin değiştir"),
                                Quad("bos_ise(, \"-\")", 6, "bos_ise(s, yedek)", "Boşsa yedek"),
                                Quad("num()", 1, "num(metin)", "Sayıya çevir"),
                                Quad("substr(, 0, 4)", 7, "substr(s, bas, uz)", "Alt parça kes"),
                                Quad("contains(, \"\")", 5, "contains(s, ara)", "İçeriyor mu"),
                                Quad("starts_with(, \"\")", 5, "starts_with(s, ek)", "Başlıyor mu"),
                                Quad("len()", 1, "len(metin)", "Karakter sayısı"),
                                Quad("upper()", 1, "upper(s)", "Büyük harf"),
                                Quad("lower()", 1, "lower(s)", "Küçük harf"),
                                Quad("trim()", 1, "trim(s)", "Boşluk sil")
                            ).forEach { (insert, backOffset, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert, cursorBackOffset = backOffset) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Quad(val insert: String, val backOffset: Int, val title: String, val sub: String)

@Composable
private fun LiveFormulaResultPill(
    label: String,
    value: String,
    isError: Boolean
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (isError) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (!isError) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = AppMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun OperatorKeyButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = 38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontFamily = AppMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun BuilderInsertChip(
    title: String,
    subtitle: String? = null,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (accent) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontFamily = AppMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
