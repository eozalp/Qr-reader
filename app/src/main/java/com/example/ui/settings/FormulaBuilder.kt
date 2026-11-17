package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.ResultFormatter
import com.example.model.FieldDataType
import com.example.model.ResultFormatting
import com.example.model.VariableDefinition

private enum class BuilderToolCategory(val label: String) {
    VARIABLES("Değişkenler"),
    OPERATORS("Operatörler"),
    MATH("Matematik"),
    DATE_TEXT("Tarih & Metin")
}

/**
 * Reusable interactive Calculation Builder allowing users to compose arbitrary expressions
 * from typed variables, arithmetic/comparison/logical/ternary operators, math tools,
 * calendar/date primitives, and string tools.
 */
@Composable
fun FormulaBuilderPanel(
    expression: String,
    onExpressionChanged: (String) -> Unit,
    variables: List<VariableDefinition>,
    liveNumericVars: Map<String, Double> = emptyMap(),
    liveStringVars: Map<String, String> = emptyMap(),
    formatting: ResultFormatting? = null,
    label: String = "Hesaplama İfadesi",
    placeholder: String = "örn: maliyet * (1 + oran / 100)",
    testTagPrefix: String = "main_formula",
    onRequestAddVariable: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeCategory by remember { mutableStateOf(BuilderToolCategory.OPERATORS) }
    var textFieldValue by remember(expression) {
        mutableStateOf(TextFieldValue(expression, TextRange(expression.length)))
    }
    val evaluator = remember { MathExpressionEvaluator() }

    val liveEvalResult = remember(expression, liveNumericVars, liveStringVars) {
        if (expression.isBlank()) null
        else evaluator.evaluate(expression, liveNumericVars, liveStringVars)
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
        textFieldValue = TextFieldValue(updated, TextRange(newCursor))
        onExpressionChanged(updated)
    }

    fun backspaceAtCursor() {
        val text = textFieldValue.text
        val selStart = textFieldValue.selection.start.coerceIn(0, text.length)
        val selEnd = textFieldValue.selection.end.coerceIn(0, text.length)
        if (selStart != selEnd) {
            val updated = text.removeRange(selStart, selEnd)
            textFieldValue = TextFieldValue(updated, TextRange(selStart))
            onExpressionChanged(updated)
        } else if (selStart > 0) {
            val updated = text.removeRange(selStart - 1, selStart)
            textFieldValue = TextFieldValue(updated, TextRange(selStart - 1))
            onExpressionChanged(updated)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Expression Text Editor
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = {
                textFieldValue = it
                onExpressionChanged(it.text)
            },
            label = { Text(label) },
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_input"),
            maxLines = 3,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { backspaceAtCursor() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Geri Sil",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                textFieldValue = TextFieldValue("", TextRange(0))
                                onExpressionChanged("")
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Temizle",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        )

        // 2. Live Result / Syntax Feedback Pill
        if (liveEvalResult != null) {
            Spacer(modifier = Modifier.height(6.dp))
            when (liveEvalResult) {
                is MathResult.Success -> {
                    val formatted = formatting?.let { ResultFormatter.format(liveEvalResult.value, it) }
                        ?: liveEvalResult.value.toString()
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Önizleme Sonucu:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = formatted,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                is MathResult.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = liveEvalResult.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                                text = "Tanımlı Değişkenler & Veri Tipleri",
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
                                text = "Henüz değişken tanımlanmadı.",
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
                                "bugun" to "Bugün (gün)",
                                "bu_yil" to "Yıl",
                                "bu_ay" to "Ay",
                                "bu_gun" to "Gün",
                                "true" to "Doğru (1)",
                                "false" to "Yanlış (0)",
                                "pi" to "π",
                                "e" to "e"
                            ).forEach { (sym, hint) ->
                                BuilderInsertChip(
                                    title = sym,
                                    subtitle = hint,
                                    onClick = { insertAtCursor(sym) }
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
                                title = "if( , , )",
                                subtitle = "Koşullu",
                                accent = true,
                                onClick = { insertAtCursor("if(, , )", cursorBackOffset = 5) }
                            )
                            BuilderInsertChip(
                                title = "? :",
                                subtitle = "Üçlü koşul",
                                onClick = { insertAtCursor("? : ", cursorBackOffset = 3) }
                            )
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
                            text = "Matematik & Yuvarlama Araçları",
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
                                Triple("round(", "round(x, 2)", "Yuvarla"),
                                Triple("ceil(", "ceil(x)", "Yukarı yuvarla"),
                                Triple("floor(", "floor(x)", "Aşağı yuvarla"),
                                Triple("trunc(", "trunc(x)", "Tam kısım"),
                                Triple("abs(", "abs(x)", "Mutlak değer"),
                                Triple("min(", "min(a, b)", "En küçük"),
                                Triple("max(", "max(a, b)", "En büyük"),
                                Triple("avg(", "avg(a, b)", "Ortalama"),
                                Triple("sum(", "sum(a, b)", "Toplam"),
                                Triple("pow(", "pow(a, b)", "Üs a^b"),
                                Triple("sqrt(", "sqrt(x)", "Karekök"),
                                Triple("clamp(", "clamp(x, min, max)", "Sınırla"),
                                Triple("ln(", "ln(x)", "Doğal log"),
                                Triple("log10(", "log10(x)", "Log 10"),
                                Triple("exp(", "exp(x)", "e^x"),
                                Triple("sign(", "sign(x)", "İşaret (-1/0/1)")
                            ).forEach { (insert, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert) }
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
                                Triple("gun_farki(bugun, ", "gun_farki(t2, t1)", "Gün farkı"),
                                Triple("ay_farki(bugun, ", "ay_farki(t2, t1)", "Ay farkı"),
                                Triple("yil_farki(bugun, ", "yil_farki(t2, t1)", "Yıl farkı"),
                                Triple("date(", "date(yil, ay, gun)", "Tarih üret"),
                                Triple("year(", "year(tarih)", "Yılı al"),
                                Triple("month(", "month(tarih)", "Ayı al"),
                                Triple("day(", "day(tarih)", "Günü al"),
                                Triple("weekday(", "weekday(tarih)", "Haftanın günü (1-7)")
                            ).forEach { (insert, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert) }
                                )
                            }
                        }

                        Text(
                            text = "Metin & Tip Dönüşüm Araçları",
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
                                Triple("num(", "num(metin)", "Sayıya çevir"),
                                Triple("len(", "len(metin)", "Karakter sayısı"),
                                Triple("substr(", "substr(s, bas, uz)", "Alt parça kes"),
                                Triple("contains(", "contains(s, aranan)", "İçeriyor mu"),
                                Triple("starts_with(", "starts_with(s, onEk)", "Başlıyor mu"),
                                Triple("ends_with(", "ends_with(s, sonEk)", "Bitiyor mu"),
                                Triple("equals(", "equals(s1, s2)", "Birebir eşit"),
                                Triple("lower(", "lower(s)", "Küçük harf"),
                                Triple("upper(", "upper(s)", "Büyük harf"),
                                Triple("trim(", "trim(s)", "Boşluk sil")
                            ).forEach { (insert, title, sub) ->
                                BuilderInsertChip(
                                    title = title,
                                    subtitle = sub,
                                    onClick = { insertAtCursor(insert) }
                                )
                            }
                        }
                    }
                }
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
            .height(36.dp)
            .widthIn(min = 36.dp)
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
                fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
