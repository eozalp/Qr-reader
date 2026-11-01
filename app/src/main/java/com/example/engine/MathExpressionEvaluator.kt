package com.example.engine

import kotlin.math.*

sealed class MathResult {
    data class Success(val value: Double) : MathResult()
    data class Error(val message: String) : MathResult()
}

/**
 * Safe, robust Recursive Descent Parser and Evaluator for arithmetic expressions.
 * Supports:
 * - Basic operators: +, -, *, /, %, ^
 * - Precedence: Parentheses -> Functions -> Exponents -> Multiplicative -> Additive
 * - Postfix percentage: 50% => 0.50
 * - Unary + and -
 * - Functions: abs(x), sqrt(x), min(a,b,...), max(a,b,...), avg(a,b,...), round(x)
 * - Arbitrary variable substitution
 * - Division by zero and NaN detection
 */
class MathExpressionEvaluator {

    fun evaluate(expression: String, variables: Map<String, Double>): MathResult {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) {
            return MathResult.Error("Formula is empty")
        }

        return try {
            val tokens = tokenize(trimmed, variables)
            val parser = Parser(tokens)
            val result = parser.parseExpression()
            if (parser.hasNext()) {
                MathResult.Error("Unexpected symbol '${parser.peek()}' in formula")
            } else if (result.isNaN() || result.isInfinite()) {
                MathResult.Error("Math calculation resulted in undefined or infinite value")
            } else {
                MathResult.Success(result)
            }
        } catch (e: ArithmeticException) {
            MathResult.Error(e.message ?: "Arithmetic error")
        } catch (e: IllegalArgumentException) {
            MathResult.Error(e.message ?: "Invalid formula expression")
        } catch (e: Exception) {
            MathResult.Error("Formula evaluation failed: ${e.message}")
        }
    }

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Identifier(val name: String) : Token()
        data class Op(val char: Char) : Token()
        object LParen : Token()
        object RParen : Token()
        object Comma : Token()
        object Percent : Token()
    }

    private fun tokenize(expr: String, variables: Map<String, Double>): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = expr.length

        while (i < len) {
            val c = expr[i]
            when {
                c.isWhitespace() -> {
                    i++
                }
                c == '(' -> {
                    tokens.add(Token.LParen)
                    i++
                }
                c == ')' -> {
                    tokens.add(Token.RParen)
                    i++
                }
                c == ',' -> {
                    tokens.add(Token.Comma)
                    i++
                }
                c == '%' -> {
                    // Could be modulo binary operator or percentage postfix
                    tokens.add(Token.Percent)
                    i++
                }
                c in "+-*/^" -> {
                    tokens.add(Token.Op(c))
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
                        ?: throw IllegalArgumentException("Invalid number '$numStr'")
                    tokens.add(Token.Number(numVal))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    i++
                    while (i < len && (expr[i].isLetterOrDigit() || expr[i] == '_')) {
                        i++
                    }
                    val word = expr.substring(start, i)
                    val lowerWord = word.lowercase()

                    // Check if variable
                    if (variables.containsKey(word)) {
                        tokens.add(Token.Number(variables.getValue(word)))
                    } else if (variables.containsKey(lowerWord)) {
                        tokens.add(Token.Number(variables.getValue(lowerWord)))
                    } else if (isKnownFunction(lowerWord)) {
                        tokens.add(Token.Identifier(lowerWord))
                    } else {
                        throw IllegalArgumentException("Unknown variable or function '$word'")
                    }
                }
                else -> {
                    throw IllegalArgumentException("Unsupported character '$c'")
                }
            }
        }
        return tokens
    }

    private fun isKnownFunction(name: String): Boolean {
        return name in setOf("abs", "sqrt", "min", "max", "avg", "round")
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun hasNext(): Boolean = pos < tokens.size

        fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null

        private fun consume(): Token {
            if (pos >= tokens.size) throw IllegalArgumentException("Unexpected end of formula")
            return tokens[pos++]
        }

        private fun matchOp(c: Char): Boolean {
            val token = peek()
            if (token is Token.Op && token.char == c) {
                pos++
                return true
            }
            return false
        }

        // expression -> term ( ('+' | '-') term )*
        fun parseExpression(): Double {
            var value = parseTerm()
            while (hasNext()) {
                when {
                    matchOp('+') -> {
                        value += parseTerm()
                    }
                    matchOp('-') -> {
                        value -= parseTerm()
                    }
                    else -> break
                }
            }
            return value
        }

        // term -> factor ( ('*' | '/' | '%') factor )*
        private fun parseTerm(): Double {
            var value = parseFactor()
            while (hasNext()) {
                val next = peek()
                if (next is Token.Op && (next.char == '*' || next.char == '/')) {
                    consume()
                    val right = parseFactor()
                    if (next.char == '*') {
                        value *= right
                    } else {
                        if (abs(right) < 1e-12) {
                            throw ArithmeticException("Division by zero")
                        }
                        value /= right
                    }
                } else if (next is Token.Percent) {
                    // Check if it's binary modulo or postfix percentage:
                    // If followed by an expression term, treat as modulo
                    consume()
                    // If next token can start a factor (Number, Identifier, LParen, Op +/-, etc.)
                    val upcoming = peek()
                    if (upcoming is Token.Number || upcoming is Token.Identifier || upcoming is Token.LParen || (upcoming is Token.Op && upcoming.char in "+-")) {
                        val right = parseFactor()
                        if (abs(right) < 1e-12) {
                            throw ArithmeticException("Modulo by zero")
                        }
                        value %= right
                    } else {
                        // Postfix percentage: e.g. 50% = 0.5
                        value /= 100.0
                    }
                } else {
                    break
                }
            }
            return value
        }

        // factor -> power ( '^' factor )?
        private fun parseFactor(): Double {
            var value = parsePrimary()

            // Postfix percent if directly after primary
            while (peek() is Token.Percent) {
                val nextNextPos = pos + 1
                val upcoming = if (nextNextPos < tokens.size) tokens[nextNextPos] else null
                val isBinaryMod = upcoming is Token.Number || upcoming is Token.Identifier || upcoming is Token.LParen
                if (isBinaryMod) {
                    break // Handle in parseTerm
                } else {
                    consume()
                    value /= 100.0
                }
            }

            if (matchOp('^')) {
                val exponent = parseFactor() // Right-associative
                value = value.pow(exponent)
            }
            return value
        }

        // primary -> unary | number | function | '(' expression ')'
        private fun parsePrimary(): Double {
            val token = peek() ?: throw IllegalArgumentException("Expected value or expression")

            // Unary + or -
            if (token is Token.Op && (token.char == '+' || token.char == '-')) {
                consume()
                val factor = parsePrimary()
                return if (token.char == '-') -factor else factor
            }

            if (token is Token.Number) {
                consume()
                return token.value
            }

            if (token is Token.Identifier) {
                consume()
                return parseFunctionCall(token.name)
            }

            if (token is Token.LParen) {
                consume()
                val inside = parseExpression()
                val closing = if (hasNext()) consume() else null
                if (closing !is Token.RParen) {
                    throw IllegalArgumentException("Missing closing parenthesis ')'")
                }
                return inside
            }

            throw IllegalArgumentException("Unexpected token $token")
        }

        private fun parseFunctionCall(funcName: String): Double {
            val lparen = if (hasNext()) consume() else null
            if (lparen !is Token.LParen) {
                throw IllegalArgumentException("Expected '(' after function '$funcName'")
            }

            val args = mutableListOf<Double>()
            if (peek() !is Token.RParen) {
                args.add(parseExpression())
                while (peek() is Token.Comma) {
                    consume() // ','
                    args.add(parseExpression())
                }
            }

            val rparen = if (hasNext()) consume() else null
            if (rparen !is Token.RParen) {
                throw IllegalArgumentException("Expected ')' closing arguments for '$funcName'")
            }

            return when (funcName) {
                "abs" -> {
                    if (args.size != 1) throw IllegalArgumentException("abs() expects exactly 1 argument")
                    abs(args[0])
                }
                "sqrt" -> {
                    if (args.size != 1) throw IllegalArgumentException("sqrt() expects exactly 1 argument")
                    if (args[0] < 0) throw ArithmeticException("sqrt() of negative number")
                    sqrt(args[0])
                }
                "round" -> {
                    if (args.size != 1) throw IllegalArgumentException("round() expects exactly 1 argument")
                    round(args[0])
                }
                "min" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("min() requires at least 1 argument")
                    args.minOrNull() ?: 0.0
                }
                "max" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("max() requires at least 1 argument")
                    args.maxOrNull() ?: 0.0
                }
                "avg" -> {
                    if (args.isEmpty()) throw IllegalArgumentException("avg() requires at least 1 argument")
                    args.average()
                }
                else -> throw IllegalArgumentException("Unsupported function '$funcName'")
            }
        }
    }
}
