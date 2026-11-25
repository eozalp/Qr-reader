package com.example.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

sealed class MathResult {
    data class Success(val value: Double) : MathResult()
    data class Error(val message: String) : MathResult()
}

/**
 * Multi-type evaluation result allowing formulas to return either a Number (Double/Date/Boolean)
 * or a Text (String) value cleanly (e.g. status badges like "KRİTİK STOK", "GECİKMİŞ", "DİZEL").
 */
sealed class MathValueResult {
    data class SuccessNum(val value: Double) : MathValueResult()
    data class SuccessText(val text: String) : MathValueResult()
    data class Error(val message: String) : MathValueResult()
}

/**
 * Composable, malleable expression engine supporting user-defined formulas built from
 * data types, operators, math tools, date/calendar tools, and string tools.
 *
 * Supported Data Types:
 * - Numeric (Int, Float, Double)
 * - Boolean (true=1.0, false=0.0)
 * - Date (stored as epoch-days numeric value + calendar accessors)
 * - String (string literals "..." / '...' and string variables)
 *
 * Supported Operators:
 * - Arithmetic: +, -, *, /, % (modulo or postfix percent), ^ (power)
 * - Comparison: ==, !=, <, <=, >, >= (works on numbers, dates, booleans, and strings)
 * - Logical: && (and), || (or), ! (not)
 * - Conditional: cond ? a : b  and  if(cond, a, b) (short-circuiting)
 */
class MathExpressionEvaluator {

    fun evaluate(
        expression: String,
        variables: Map<String, Double>,
        stringVariables: Map<String, String> = emptyMap()
    ): MathResult {
        return when (val valueResult = evaluateValue(expression, variables, stringVariables)) {
            is MathValueResult.SuccessNum -> MathResult.Success(valueResult.value)
            is MathValueResult.SuccessText -> {
                val parsed = valueResult.text.replace(',', '.').trim().toDoubleOrNull()
                if (parsed != null && !parsed.isNaN() && !parsed.isInfinite()) {
                    MathResult.Success(parsed)
                } else {
                    MathResult.Error("Formül sonucu metin ('${valueResult.text}'), sayısal çıktı bekleniyor. Sayıya çevirmek için num(...) kullanın.")
                }
            }
            is MathValueResult.Error -> MathResult.Error(valueResult.message)
        }
    }

    /**
     * Evaluates any expression into either a numeric or string result.
     * Supports local variables, @global shared constants, dates, booleans, and text outputs.
     */
    fun evaluateValue(
        expression: String,
        variables: Map<String, Double>,
        stringVariables: Map<String, String> = emptyMap()
    ): MathValueResult {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) {
            return MathValueResult.Error("Formül boş")
        }

        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay().toDouble()
        val enrichedNums = buildMap {
            put("bugun", todayEpoch)
            put("today", todayEpoch)
            put("bu_yil", today.year.toDouble())
            put("now_year", today.year.toDouble())
            put("current_year", today.year.toDouble())
            put("bu_ay", today.monthValue.toDouble())
            put("now_month", today.monthValue.toDouble())
            put("current_month", today.monthValue.toDouble())
            put("bu_gun", today.dayOfMonth.toDouble())
            put("now_day", today.dayOfMonth.toDouble())
            put("current_day", today.dayOfMonth.toDouble())
            put("pi", PI)
            put("e", E)
            put("true", 1.0)
            put("false", 0.0)
            put("dogru", 1.0)
            put("yanlis", 0.0)
            putAll(variables)
        }

        return try {
            val tokens = tokenize(trimmed)
            val parser = AstParser(tokens)
            val ast = parser.parseExpression()
            if (parser.hasNext()) {
                return MathValueResult.Error("Beklenmeyen sembol: '${parser.peek()}'")
            }
            val ctx = EvalContext(
                numVars = enrichedNums,
                strVars = stringVariables,
                todayEpochDay = todayEpoch
            )
            when (val evaluated = eval(ast, ctx)) {
                is EvalVal.Num -> {
                    if (evaluated.v.isNaN() || evaluated.v.isInfinite()) {
                        MathValueResult.Error("Hesaplama tanımsız veya sonsuz değer üretti")
                    } else {
                        MathValueResult.SuccessNum(evaluated.v)
                    }
                }
                is EvalVal.Str -> MathValueResult.SuccessText(evaluated.s)
            }
        } catch (e: ArithmeticException) {
            MathValueResult.Error(e.message ?: "Aritmetik hata")
        } catch (e: IllegalArgumentException) {
            MathValueResult.Error(e.message ?: "Geçersiz formül ifadesi")
        } catch (e: Exception) {
            MathValueResult.Error("Formül değerlendirme hatası: ${e.message}")
        }
    }

    private sealed class Token {
        data class Number(val value: Double) : Token() {
            override fun toString() = value.toString()
        }
        data class StringLiteral(val value: String) : Token() {
            override fun toString() = "\"$value\""
        }
        data class Word(val raw: String, val lower: String) : Token() {
            override fun toString() = raw
        }
        data class Op(val symbol: String) : Token() {
            override fun toString() = symbol
        }
        object LParen : Token() { override fun toString() = "(" }
        object RParen : Token() { override fun toString() = ")" }
        object Comma : Token() { override fun toString() = "," }
        object Percent : Token() { override fun toString() = "%" }
        object Question : Token() { override fun toString() = "?" }
        object Colon : Token() { override fun toString() = ":" }
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = expr.length

        while (i < len) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                c == ',' -> { tokens.add(Token.Comma); i++ }
                c == '%' -> { tokens.add(Token.Percent); i++ }
                c == '?' -> { tokens.add(Token.Question); i++ }
                c == ':' -> { tokens.add(Token.Colon); i++ }
                c == '"' || c == '\'' -> {
                    val quote = c
                    i++
                    val sb = StringBuilder()
                    var closed = false
                    while (i < len) {
                        val ch = expr[i]
                        if (ch == '\\' && i + 1 < len) {
                            sb.append(expr[i + 1])
                            i += 2
                        } else if (ch == quote) {
                            closed = true
                            i++
                            break
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    if (!closed) {
                        throw IllegalArgumentException("Kapatılmamış metin tırnağı ($quote)")
                    }
                    tokens.add(Token.StringLiteral(sb.toString()))
                }
                i + 1 < len && expr.substring(i, i + 2) in setOf("==", "!=", "<=", ">=", "&&", "||") -> {
                    tokens.add(Token.Op(expr.substring(i, i + 2)))
                    i += 2
                }
                c in setOf('+', '-', '*', '/', '^', '<', '>', '!') -> {
                    tokens.add(Token.Op(c.toString()))
                    i++
                }
                c == '=' -> {
                    // Treat single '=' in expressions as equality comparison convenience or fail cleanly if unexpected?
                    // Supporting single '=' as '==' helps non-programmers write if(a = 1, ...) naturally!
                    tokens.add(Token.Op("=="))
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    var hasDot = c == '.'
                    i++
                    while (i < len && (expr[i].isDigit() || (!hasDot && expr[i] == '.'))) {
                        if (expr[i] == '.') hasDot = true
                        i++
                    }
                    val numStr = expr.substring(start, i)
                    val numVal = numStr.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Geçersiz sayı: '$numStr'")
                    tokens.add(Token.Number(numVal))
                }
                c.isLetter() || c == '_' || c == '@' -> {
                    val start = i
                    i++
                    while (i < len && (expr[i].isLetterOrDigit() || expr[i] == '_')) {
                        i++
                    }
                    val raw = expr.substring(start, i)
                    val lower = raw.lowercase(Locale.ROOT)
                    when (lower) {
                        "and", "ve" -> tokens.add(Token.Op("&&"))
                        "or", "veya" -> tokens.add(Token.Op("||"))
                        "not", "degil" -> tokens.add(Token.Op("!"))
                        else -> tokens.add(Token.Word(raw, lower))
                    }
                }
                else -> throw IllegalArgumentException("Desteklenmeyen karakter: '$c'")
            }
        }
        return tokens
    }

    // --- AST Nodes ---
    private sealed class Expr {
        data class NumLit(val value: Double) : Expr()
        data class StrLit(val value: String) : Expr()
        data class VarRef(val raw: String, val lower: String) : Expr()
        data class Unary(val op: String, val right: Expr) : Expr()
        data class PostfixPercent(val inner: Expr) : Expr()
        data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
        data class Conditional(val cond: Expr, val ifTrue: Expr, val ifFalse: Expr) : Expr()
        data class Call(val name: String, val args: List<Expr>) : Expr()
    }

    private class AstParser(private val tokens: List<Token>) {
        private var pos = 0

        fun hasNext(): Boolean = pos < tokens.size
        fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null
        private fun peekNext(): Token? = if (pos + 1 < tokens.size) tokens[pos + 1] else null

        private fun consume(): Token {
            if (pos >= tokens.size) throw IllegalArgumentException("Formül beklenmedik şekilde sonlandı")
            return tokens[pos++]
        }

        private fun matchOp(vararg ops: String): String? {
            val t = peek()
            if (t is Token.Op && t.symbol in ops) {
                pos++
                return t.symbol
            }
            return null
        }

        // expression -> ternary
        fun parseExpression(): Expr = parseTernary()

        // ternary -> logicalOr ( '?' expression ':' ternary )?
        private fun parseTernary(): Expr {
            val cond = parseLogicalOr()
            if (peek() is Token.Question) {
                consume()
                val ifTrue = parseExpression()
                if (peek() !is Token.Colon) {
                    throw IllegalArgumentException("Koşul operatörü '?' sonrasında ':' bekleniyor")
                }
                consume()
                val ifFalse = parseTernary()
                return Expr.Conditional(cond, ifTrue, ifFalse)
            }
            return cond
        }

        private fun parseLogicalOr(): Expr {
            var left = parseLogicalAnd()
            while (matchOp("||") != null) {
                left = Expr.Binary("||", left, parseLogicalAnd())
            }
            return left
        }

        private fun parseLogicalAnd(): Expr {
            var left = parseEquality()
            while (matchOp("&&") != null) {
                left = Expr.Binary("&&", left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): Expr {
            var left = parseComparison()
            while (true) {
                val op = matchOp("==", "!=") ?: break
                left = Expr.Binary(op, left, parseComparison())
            }
            return left
        }

        private fun parseComparison(): Expr {
            var left = parseAdditive()
            while (true) {
                val op = matchOp("<", "<=", ">", ">=") ?: break
                left = Expr.Binary(op, left, parseAdditive())
            }
            return left
        }

        private fun parseAdditive(): Expr {
            var left = parseMultiplicative()
            while (true) {
                val op = matchOp("+", "-") ?: break
                left = Expr.Binary(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): Expr {
            var left = parsePower()
            while (hasNext()) {
                val next = peek()
                if (next is Token.Op && (next.symbol == "*" || next.symbol == "/")) {
                    consume()
                    left = Expr.Binary(next.symbol, left, parsePower())
                } else if (next is Token.Percent) {
                    val upcoming = peekNext()
                    if (isBinaryModuloFollow(upcoming)) {
                        consume()
                        left = Expr.Binary("%", left, parsePower())
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parsePower(): Expr {
            val base = parseUnary()
            if (matchOp("^") != null) {
                val exponent = parsePower() // Right-associative
                return Expr.Binary("^", base, exponent)
            }
            return base
        }

        private fun parseUnary(): Expr {
            val op = matchOp("+", "-", "!")
            if (op != null) {
                return Expr.Unary(op, parseUnary())
            }
            return parsePostfix()
        }

        private fun parsePostfix(): Expr {
            var expr = parsePrimary()
            while (peek() is Token.Percent) {
                val upcoming = peekNext()
                if (isBinaryModuloFollow(upcoming)) {
                    break // Handled as binary modulo in parseMultiplicative
                } else {
                    consume()
                    expr = Expr.PostfixPercent(expr)
                }
            }
            return expr
        }

        private fun isBinaryModuloFollow(upcoming: Token?): Boolean {
            return upcoming is Token.Number ||
                upcoming is Token.StringLiteral ||
                upcoming is Token.Word ||
                upcoming is Token.LParen ||
                (upcoming is Token.Op && upcoming.symbol == "!")
        }

        private fun parsePrimary(): Expr {
            val token = peek() ?: throw IllegalArgumentException("Değer veya ifade bekleniyor")
            return when (token) {
                is Token.Number -> {
                    consume()
                    Expr.NumLit(token.value)
                }
                is Token.StringLiteral -> {
                    consume()
                    Expr.StrLit(token.value)
                }
                is Token.Word -> {
                    consume()
                    if (peek() is Token.LParen) {
                        parseCall(token.lower)
                    } else {
                        Expr.VarRef(token.raw, token.lower)
                    }
                }
                is Token.LParen -> {
                    consume()
                    val inner = parseExpression()
                    val closing = if (hasNext()) consume() else null
                    if (closing !is Token.RParen) {
                        throw IllegalArgumentException("Kapatma parantezi ')' eksik")
                    }
                    inner
                }
                else -> throw IllegalArgumentException("Beklenmeyen ifade: '$token'")
            }
        }

        private fun parseCall(name: String): Expr {
            consume() // '('
            val args = mutableListOf<Expr>()
            if (peek() !is Token.RParen) {
                args.add(parseExpression())
                while (peek() is Token.Comma) {
                    consume()
                    args.add(parseExpression())
                }
            }
            val closing = if (hasNext()) consume() else null
            if (closing !is Token.RParen) {
                throw IllegalArgumentException("'$name(...)' fonksiyonunda ')' eksik")
            }
            return Expr.Call(name, args)
        }
    }

    // --- Evaluation ---
    private sealed class EvalVal {
        data class Num(val v: Double) : EvalVal()
        data class Str(val s: String) : EvalVal()
    }

    private class EvalContext(
        val numVars: Map<String, Double>,
        val strVars: Map<String, String>,
        val todayEpochDay: Double
    )

    private fun asNum(valObj: EvalVal, contextHint: String = "işlem"): Double {
        return when (valObj) {
            is EvalVal.Num -> valObj.v
            is EvalVal.Str -> valObj.s.replace(',', '.').trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("'$contextHint' için sayı gerekli, metin bulundu: '${valObj.s}'")
        }
    }

    private fun safeEpochDate(epochDays: Double, fnName: String): LocalDate {
        val day = epochDays.toLong()
        return runCatching { LocalDate.ofEpochDay(day) }
            .getOrElse { throw IllegalArgumentException("'$fnName': geçersiz tarih değeri ($day)") }
    }

    private fun asStr(valObj: EvalVal): String {
        return when (valObj) {
            is EvalVal.Str -> valObj.s
            is EvalVal.Num -> {
                if (valObj.v % 1.0 == 0.0 && abs(valObj.v) < 1e15) {
                    valObj.v.toLong().toString()
                } else {
                    valObj.v.toString()
                }
            }
        }
    }

    private fun asBool(valObj: EvalVal): Boolean {
        return when (valObj) {
            is EvalVal.Num -> abs(valObj.v) > 1e-12
            is EvalVal.Str -> valObj.s.isNotEmpty() && !valObj.s.equals("false", ignoreCase = true) && valObj.s != "0"
        }
    }

    private fun eval(expr: Expr, ctx: EvalContext): EvalVal {
        return when (expr) {
            is Expr.NumLit -> EvalVal.Num(expr.value)
            is Expr.StrLit -> EvalVal.Str(expr.value)
            is Expr.VarRef -> {
                val bareRaw = expr.raw.removePrefix("@")
                val bareLower = expr.lower.removePrefix("@")
                val num = ctx.numVars[expr.raw]
                    ?: ctx.numVars[expr.lower]
                    ?: if (expr.raw.startsWith("@")) (ctx.numVars[bareRaw] ?: ctx.numVars[bareLower]) else null
                if (num != null) {
                    EvalVal.Num(num)
                } else {
                    val str = ctx.strVars[expr.raw]
                        ?: ctx.strVars[expr.lower]
                        ?: if (expr.raw.startsWith("@")) (ctx.strVars[bareRaw] ?: ctx.strVars[bareLower]) else null
                    if (str != null) {
                        EvalVal.Str(str)
                    } else {
                        throw IllegalArgumentException("Bilinmeyen değişken: '${expr.raw}'")
                    }
                }
            }
            is Expr.Unary -> {
                val right = eval(expr.right, ctx)
                when (expr.op) {
                    "+" -> EvalVal.Num(asNum(right, "+"))
                    "-" -> EvalVal.Num(-asNum(right, "-"))
                    "!" -> EvalVal.Num(if (asBool(right)) 0.0 else 1.0)
                    else -> throw IllegalArgumentException("Bilinmeyen tekli operatör: ${expr.op}")
                }
            }
            is Expr.PostfixPercent -> {
                val v = asNum(eval(expr.inner, ctx), "%")
                EvalVal.Num(v / 100.0)
            }
            is Expr.Conditional -> {
                val cond = asBool(eval(expr.cond, ctx))
                if (cond) eval(expr.ifTrue, ctx) else eval(expr.ifFalse, ctx)
            }
            is Expr.Binary -> evalBinary(expr, ctx)
            is Expr.Call -> evalCall(expr, ctx)
        }
    }

    private fun evalBinary(expr: Expr.Binary, ctx: EvalContext): EvalVal {
        // Short-circuit logical ops
        if (expr.op == "&&") {
            val l = asBool(eval(expr.left, ctx))
            if (!l) return EvalVal.Num(0.0)
            return EvalVal.Num(if (asBool(eval(expr.right, ctx))) 1.0 else 0.0)
        }
        if (expr.op == "||") {
            val l = asBool(eval(expr.left, ctx))
            if (l) return EvalVal.Num(1.0)
            return EvalVal.Num(if (asBool(eval(expr.right, ctx))) 1.0 else 0.0)
        }

        val left = eval(expr.left, ctx)
        val right = eval(expr.right, ctx)

        return when (expr.op) {
            "+" -> {
                if (left is EvalVal.Str || right is EvalVal.Str) {
                    EvalVal.Str(asStr(left) + asStr(right))
                } else {
                    EvalVal.Num((left as EvalVal.Num).v + (right as EvalVal.Num).v)
                }
            }
            "-" -> EvalVal.Num(asNum(left, "-") - asNum(right, "-"))
            "*" -> EvalVal.Num(asNum(left, "*") * asNum(right, "*"))
            "/" -> {
                val r = asNum(right, "/")
                if (abs(r) < 1e-12) throw ArithmeticException("Division by zero")
                EvalVal.Num(asNum(left, "/") / r)
            }
            "%" -> {
                val r = asNum(right, "%")
                if (abs(r) < 1e-12) throw ArithmeticException("Sıfıra göre mod hatası")
                EvalVal.Num(asNum(left, "%") % r)
            }
            "^" -> EvalVal.Num(asNum(left, "^").pow(asNum(right, "^")))
            "==" -> {
                val eq = if (left is EvalVal.Str && right is EvalVal.Str) {
                    left.s == right.s
                } else if (left is EvalVal.Num && right is EvalVal.Num) {
                    abs(left.v - right.v) < 1e-9
                } else {
                    asStr(left) == asStr(right)
                }
                EvalVal.Num(if (eq) 1.0 else 0.0)
            }
            "!=" -> {
                val eq = if (left is EvalVal.Str && right is EvalVal.Str) {
                    left.s == right.s
                } else if (left is EvalVal.Num && right is EvalVal.Num) {
                    abs(left.v - right.v) < 1e-9
                } else {
                    asStr(left) == asStr(right)
                }
                EvalVal.Num(if (!eq) 1.0 else 0.0)
            }
            "<" -> EvalVal.Num(if (compareValues(left, right) < 0) 1.0 else 0.0)
            "<=" -> EvalVal.Num(if (compareValues(left, right) <= 0) 1.0 else 0.0)
            ">" -> EvalVal.Num(if (compareValues(left, right) > 0) 1.0 else 0.0)
            ">=" -> EvalVal.Num(if (compareValues(left, right) >= 0) 1.0 else 0.0)
            else -> throw IllegalArgumentException("Bilinmeyen operatör: ${expr.op}")
        }
    }

    private fun compareValues(left: EvalVal, right: EvalVal): Int {
        return if (left is EvalVal.Str && right is EvalVal.Str) {
            left.s.compareTo(right.s)
        } else {
            asNum(left, "karşılaştırma").compareTo(asNum(right, "karşılaştırma"))
        }
    }

    private fun evalCall(call: Expr.Call, ctx: EvalContext): EvalVal {
        val name = call.name
        val args = call.args

        // Short-circuit conditional function
        if (name == "if" || name == "eger") {
            if (args.size != 3) throw IllegalArgumentException("$name(koşul, doğruysa, yanlışsa) 3 argüman bekler")
            val cond = asBool(eval(args[0], ctx))
            return if (cond) eval(args[1], ctx) else eval(args[2], ctx)
        }

        // Short-circuit multi-branch lookup: switch(hedef, k1, v1, k2, v2, ..., [varsayilan])
        if (name == "switch" || name == "sec") {
            if (args.size < 3) throw IllegalArgumentException("$name(deger, k1, v1, ..., [varsayilan]) en az 3 argüman bekler")
            val target = eval(args[0], ctx)
            var idx = 1
            while (idx + 1 < args.size) {
                val candidate = eval(args[idx], ctx)
                val matched = when {
                    target is EvalVal.Str && candidate is EvalVal.Str -> target.s.equals(candidate.s, ignoreCase = true)
                    target is EvalVal.Num && candidate is EvalVal.Num -> abs(target.v - candidate.v) < 1e-9
                    else -> asStr(target).equals(asStr(candidate), ignoreCase = true)
                }
                if (matched) return eval(args[idx + 1], ctx)
                idx += 2
            }
            return if (idx < args.size) eval(args[idx], ctx) else EvalVal.Num(0.0)
        }

        val evaluated = args.map { eval(it, ctx) }
        fun numArg(idx: Int): Double = asNum(evaluated[idx], name)
        fun strArg(idx: Int): String = asStr(evaluated[idx])

        return when (name) {
            // --- Math Tools ---
            "abs" -> {
                requireCount(name, args, 1)
                EvalVal.Num(abs(numArg(0)))
            }
            "sqrt" -> {
                requireCount(name, args, 1)
                val x = numArg(0)
                if (x < 0) throw ArithmeticException("Negatif sayının karekökü alınamaz")
                EvalVal.Num(sqrt(x))
            }
            "pow" -> {
                requireCount(name, args, 2)
                EvalVal.Num(numArg(0).pow(numArg(1)))
            }
            "round" -> {
                if (args.size == 1) {
                    EvalVal.Num(round(numArg(0)))
                } else if (args.size == 2) {
                    val value = numArg(0)
                    val decimals = numArg(1).toInt().coerceIn(0, 10)
                    val factor = 10.0.pow(decimals)
                    EvalVal.Num(round(value * factor) / factor)
                } else {
                    throw IllegalArgumentException("round(x[, ondalik]) 1 veya 2 argüman bekler")
                }
            }
            "floor" -> {
                requireCount(name, args, 1)
                EvalVal.Num(floor(numArg(0)))
            }
            "ceil" -> {
                requireCount(name, args, 1)
                EvalVal.Num(ceil(numArg(0)))
            }
            "trunc" -> {
                requireCount(name, args, 1)
                EvalVal.Num(truncate(numArg(0)))
            }
            "sign" -> {
                requireCount(name, args, 1)
                EvalVal.Num(sign(numArg(0)))
            }
            "ln" -> {
                requireCount(name, args, 1)
                val x = numArg(0)
                if (x <= 0) throw ArithmeticException("ln() pozitif sayı bekler")
                EvalVal.Num(ln(x))
            }
            "log10" -> {
                requireCount(name, args, 1)
                val x = numArg(0)
                if (x <= 0) throw ArithmeticException("log10() pozitif sayı bekler")
                EvalVal.Num(log10(x))
            }
            "exp" -> {
                requireCount(name, args, 1)
                EvalVal.Num(exp(numArg(0)))
            }
            "min" -> {
                if (args.isEmpty()) throw IllegalArgumentException("min() en az 1 argüman bekler")
                EvalVal.Num(evaluated.indices.minOf { numArg(it) })
            }
            "max" -> {
                if (args.isEmpty()) throw IllegalArgumentException("max() en az 1 argüman bekler")
                EvalVal.Num(evaluated.indices.maxOf { numArg(it) })
            }
            "avg" -> {
                if (args.isEmpty()) throw IllegalArgumentException("avg() en az 1 argüman bekler")
                EvalVal.Num(evaluated.indices.map { numArg(it) }.average())
            }
            "sum" -> {
                if (args.isEmpty()) throw IllegalArgumentException("sum() en az 1 argüman bekler")
                EvalVal.Num(evaluated.indices.sumOf { numArg(it) })
            }
            "clamp" -> {
                requireCount(name, args, 3)
                val v = numArg(0)
                val lo = numArg(1)
                val hi = numArg(2)
                EvalVal.Num(v.coerceIn(min(lo, hi), max(lo, hi)))
            }

            // --- Date & Calendar Tools ---
            "date", "tarih_yap" -> {
                requireCount(name, args, 3)
                val y = numArg(0).toInt()
                val m = numArg(1).toInt()
                val d = numArg(2).toInt()
                val ld = runCatching { LocalDate.of(y, m, d) }
                    .getOrElse { throw IllegalArgumentException("Geçersiz tarih: $y-$m-$d") }
                EvalVal.Num(ld.toEpochDay().toDouble())
            }
            "year", "yil" -> {
                requireCount(name, args, 1)
                EvalVal.Num(safeEpochDate(numArg(0), name).year.toDouble())
            }
            "month", "ay" -> {
                requireCount(name, args, 1)
                EvalVal.Num(safeEpochDate(numArg(0), name).monthValue.toDouble())
            }
            "day", "gun" -> {
                requireCount(name, args, 1)
                EvalVal.Num(safeEpochDate(numArg(0), name).dayOfMonth.toDouble())
            }
            "weekday", "haftanin_gunu" -> {
                requireCount(name, args, 1)
                EvalVal.Num(safeEpochDate(numArg(0), name).dayOfWeek.value.toDouble())
            }
            "gun_farki", "days_between", "days" -> {
                when (args.size) {
                    1 -> EvalVal.Num(ctx.todayEpochDay - numArg(0))
                    2 -> EvalVal.Num(numArg(0) - numArg(1))
                    else -> throw IllegalArgumentException("$name() 1 veya 2 argüman bekler")
                }
            }
            "ay_farki", "months_between", "months" -> {
                when (args.size) {
                    1 -> EvalVal.Num((ctx.todayEpochDay - numArg(0)) / 30.4375)
                    2 -> EvalVal.Num((numArg(0) - numArg(1)) / 30.4375)
                    else -> throw IllegalArgumentException("$name() 1 veya 2 argüman bekler")
                }
            }
            "yil_farki", "years_between", "years" -> {
                when (args.size) {
                    1 -> EvalVal.Num((ctx.todayEpochDay - numArg(0)) / 365.25)
                    2 -> EvalVal.Num((numArg(0) - numArg(1)) / 365.25)
                    else -> throw IllegalArgumentException("$name() 1 veya 2 argüman bekler")
                }
            }

            // --- String & Conversion Tools ---
            "len", "uzunluk" -> {
                requireCount(name, args, 1)
                EvalVal.Num(strArg(0).length.toDouble())
            }
            "num", "sayi" -> {
                when (args.size) {
                    1 -> EvalVal.Num(strArg(0).replace(',', '.').trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException("'$name': sayıya çevrilemedi"))
                    2 -> EvalVal.Num(strArg(0).replace(',', '.').trim().toDoubleOrNull() ?: numArg(1))
                    else -> throw IllegalArgumentException("$name(metin[, varsayilan]) 1 veya 2 argüman bekler")
                }
            }
            "contains", "icerir" -> {
                requireCount(name, args, 2)
                EvalVal.Num(if (strArg(0).contains(strArg(1), ignoreCase = true)) 1.0 else 0.0)
            }
            "starts_with", "baslar" -> {
                requireCount(name, args, 2)
                EvalVal.Num(if (strArg(0).startsWith(strArg(1), ignoreCase = true)) 1.0 else 0.0)
            }
            "ends_with", "biter" -> {
                requireCount(name, args, 2)
                EvalVal.Num(if (strArg(0).endsWith(strArg(1), ignoreCase = true)) 1.0 else 0.0)
            }
            "equals", "esit" -> {
                requireCount(name, args, 2)
                EvalVal.Num(if (strArg(0) == strArg(1)) 1.0 else 0.0)
            }
            "substr", "parca" -> {
                if (args.size !in 2..3) throw IllegalArgumentException("$name(metin, baslangic[, uzunluk]) 2 veya 3 argüman bekler")
                val s = strArg(0)
                val start = numArg(1).toInt().coerceIn(0, s.length)
                val sub = if (args.size == 3) {
                    val lenArg = numArg(2).toInt().coerceAtLeast(0)
                    val end = (start + lenArg).coerceIn(start, s.length)
                    s.substring(start, end)
                } else {
                    s.substring(start)
                }
                EvalVal.Str(sub)
            }
            "lower", "kucuk" -> {
                requireCount(name, args, 1)
                EvalVal.Str(strArg(0).lowercase())
            }
            "upper", "buyuk" -> {
                requireCount(name, args, 1)
                EvalVal.Str(strArg(0).uppercase())
            }
            "between", "aralik" -> {
                requireCount(name, args, 3)
                val v = numArg(0)
                val lo = numArg(1)
                val hi = numArg(2)
                EvalVal.Num(if (v >= min(lo, hi) && v <= max(lo, hi)) 1.0 else 0.0)
            }
            "add_days", "gun_ekle" -> {
                requireCount(name, args, 2)
                val baseDate = safeEpochDate(numArg(0), name)
                val delta = numArg(1).toLong()
                val shifted = runCatching { baseDate.plusDays(delta) }
                    .getOrElse { throw IllegalArgumentException("'$name': tarih taşması ($delta gün)") }
                EvalVal.Num(shifted.toEpochDay().toDouble())
            }
            "format_date", "tarih_metin" -> {
                requireCount(name, args, 1)
                val date = safeEpochDate(numArg(0), name)
                EvalVal.Str(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
            }
            "replace", "degistir" -> {
                requireCount(name, args, 3)
                EvalVal.Str(strArg(0).replace(strArg(1), strArg(2)))
            }
            "concat", "birlestir" -> {
                if (args.isEmpty()) throw IllegalArgumentException("$name() en az 1 argüman bekler")
                EvalVal.Str(evaluated.joinToString("") { asStr(it) })
            }
            "default", "bos_ise" -> {
                requireCount(name, args, 2)
                val first = evaluated[0]
                val isEmpty = when (first) {
                    is EvalVal.Str -> first.s.isBlank()
                    is EvalVal.Num -> first.v.isNaN()
                }
                if (isEmpty) evaluated[1] else first
            }
            "index_of", "konum" -> {
                requireCount(name, args, 2)
                EvalVal.Num(strArg(0).indexOf(strArg(1), ignoreCase = true).toDouble())
            }
            "trim", "kirp" -> {
                requireCount(name, args, 1)
                EvalVal.Str(strArg(0).trim())
            }
            else -> throw IllegalArgumentException("Desteklenmeyen fonksiyon: '$name'")
        }
    }

    private fun requireCount(name: String, args: List<Expr>, expected: Int) {
        if (args.size != expected) {
            throw IllegalArgumentException("$name() $expected argüman bekler")
        }
    }
}
