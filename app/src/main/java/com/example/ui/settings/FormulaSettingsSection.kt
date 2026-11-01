package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.MathExpressionEvaluator
import com.example.engine.MathResult
import com.example.engine.QrParseOutcome
import com.example.engine.QrParser
import com.example.model.ArithmeticFormula
import com.example.model.QrParsingRules

@Composable
fun FormulaSettingsSection(
    formula: ArithmeticFormula,
    rules: QrParsingRules,
    onFormulaChanged: (ArithmeticFormula) -> Unit,
    modifier: Modifier = Modifier
) {
    var expressionText by remember(formula.expression) { mutableStateOf(formula.expression) }
    var testQrInput by remember { mutableStateOf("10-20-30-40") }

    val mathEvaluator = remember { MathExpressionEvaluator() }

    // Live test calculation
    val testOutcome = remember(expressionText, testQrInput, rules) {
        when (val parse = QrParser.parse(testQrInput, rules)) {
            is QrParseOutcome.Error -> "Parse Error: ${parse.message}"
            is QrParseOutcome.Success -> {
                when (val eval = mathEvaluator.evaluate(expressionText, parse.numericVariables)) {
                    is MathResult.Error -> "Math Error: ${eval.message}"
                    is MathResult.Success -> {
                        val num = eval.value
                        val formatted = if (num % 1.0 == 0.0) num.toLong().toString() else "%.4f".format(num)
                        "Result: $formatted"
                    }
                }
            }
        }
    }

    val isOutcomeSuccess = testOutcome.startsWith("Result:")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Arithmetic Formula",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Write a formula using variables, operations (+, -, *, /, %, ^), and functions (abs, min, max, avg, sqrt).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = expressionText,
                onValueChange = {
                    expressionText = it
                    onFormulaChanged(formula.copy(expression = it))
                },
                label = { Text("Mathematical Formula") },
                placeholder = { Text("e.g. a + b + c + d") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("formula_input_field"),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        // Variable Picker Chips
        item {
            Text(
                text = "Insert Available Variable",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            val variableNames = rules.variables.flatMap {
                if (it.alias.isNotBlank()) listOf(it.name, it.alias) else listOf(it.name)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (variableNames.isEmpty()) {
                    Text("No variables defined yet in QR Parsing", color = MaterialTheme.colorScheme.outline)
                }
                variableNames.forEach { varName ->
                    SuggestionChip(
                        onClick = {
                            val newExpr = if (expressionText.isEmpty() || expressionText.endsWith(" ") || expressionText.endsWith("(")) {
                                "$expressionText$varName"
                            } else {
                                "$expressionText $varName"
                            }
                            expressionText = newExpr
                            onFormulaChanged(formula.copy(expression = newExpr))
                        },
                        label = { Text(varName, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("var_chip_$varName")
                    )
                }
            }
        }

        // Quick Math Operators Toolbar
        item {
            Text(
                text = "Quick Operators & Functions",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            val ops = listOf("+", "-", "*", "/", "%", "^", "(", ")", "abs(", "sqrt(", "min(", "max(", "avg(")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ops.forEach { op ->
                    FilledTonalButton(
                        onClick = {
                            val separator = if (op in listOf("+", "-", "*", "/", "%", "^")) " " else ""
                            val newExpr = if (separator.isNotEmpty()) "$expressionText$separator$op " else "$expressionText$op"
                            expressionText = newExpr
                            onFormulaChanged(formula.copy(expression = newExpr))
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(op, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Common Presets Chips
        item {
            Text(
                text = "Formula Examples",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            val presets = listOf(
                "Sum 4" to "a + b + c + d",
                "Product" to "a * b",
                "Ratio" to "(a + b) / (c + d)",
                "Percentage" to "(a * b) / 100",
                "Tax Added" to "a * (1 + b / 100)",
                "Average" to "avg(a, b, c, d)"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (label, expr) ->
                    AssistChip(
                        onClick = {
                            expressionText = expr
                            onFormulaChanged(formula.copy(expression = expr))
                        },
                        label = { Text("$label: $expr") }
                    )
                }
            }
        }

        // Interactive Live Formula Tester
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isOutcomeSuccess) Color(0xFF064E3B).copy(alpha = 0.35f) else Color(0xFF450A0A).copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Live Formula Tester",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = if (isOutcomeSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (isOutcomeSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = testQrInput,
                        onValueChange = { testQrInput = it },
                        label = { Text("Sample QR Code Content") },
                        placeholder = { Text("e.g. 10-20-30-40") },
                        modifier = Modifier.fillMaxWidth().testTag("sample_qr_test_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = testOutcome,
                            color = if (isOutcomeSuccess) Color(0xFF34D399) else Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
