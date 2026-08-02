package net.jacoblo.autoclicker

/**
 * The small expression language behind conditions and Set-variable actions.
 *
 * Parsing is pure and happens once; evaluation is suspending so that blocking
 * built-ins like waitImage() can delay() and stay cancellable when the user
 * presses stop.
 */

class ExpressionException(message: String) : Exception(message)

sealed interface Value {

	data class Num(val value: Long) : Value
	data class Str(val value: String) : Value
	data class Bool(val value: Boolean) : Value

	fun asBool(): Boolean = when (this) {
		is Bool -> value
		is Num -> value != 0L
		is Str -> value.isNotEmpty()
	}

	fun asNum(): Long = when (this) {
		is Bool -> if (value) 1L else 0L
		is Num -> value
		is Str -> value.toLongOrNull() ?: 0L
	}

	fun asText(): String = when (this) {
		is Bool -> value.toString()
		is Num -> value.toString()
		is Str -> value
	}
}

sealed interface Expr {
	data class Lit(val value: Value) : Expr
	data class Var(val name: String) : Expr
	data class Unary(val op: String, val operand: Expr) : Expr
	data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
	data class Call(val name: String, val args: List<Expr>) : Expr
}

/** Supplies variables and built-in functions to [evaluate]. */
interface EvalContext {
	fun variable(name: String): Value?
	suspend fun call(name: String, args: List<Value>): Value
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

private enum class TokenType { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }

private data class Token(val type: TokenType, val text: String)

// Longest first, so "==" is not read as two "=" and "<=" not as "<".
private val OPERATORS = listOf("==", "!=", "<=", ">=", "&&", "||", "<", ">", "+", "-", "*", "/", "%", "!")

private fun lex(source: String): List<Token> {
	val tokens = mutableListOf<Token>()
	var i = 0

	while (i < source.length) {
		val c = source[i]

		if (c.isWhitespace()) {
			i++
			continue
		}

		if (c.isDigit()) {
			val start = i
			while (i < source.length && source[i].isDigit()) i++
			tokens.add(Token(TokenType.NUMBER, source.substring(start, i)))
			continue
		}

		if (c == '"' || c == '\'') {
			val quote = c
			i++
			val sb = StringBuilder()
			while (i < source.length && source[i] != quote) {
				if (source[i] == '\\' && i + 1 < source.length) i++
				sb.append(source[i])
				i++
			}
			if (i >= source.length) throw ExpressionException("unterminated string")
			i++
			tokens.add(Token(TokenType.STRING, sb.toString()))
			continue
		}

		if (c.isLetter() || c == '_') {
			val start = i
			while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
			tokens.add(Token(TokenType.IDENT, source.substring(start, i)))
			continue
		}

		if (c == '(') {
			tokens.add(Token(TokenType.LPAREN, "(")); i++; continue
		}
		if (c == ')') {
			tokens.add(Token(TokenType.RPAREN, ")")); i++; continue
		}
		if (c == ',') {
			tokens.add(Token(TokenType.COMMA, ",")); i++; continue
		}

		val op = OPERATORS.firstOrNull { source.startsWith(it, i) }
			?: throw ExpressionException("unexpected character '$c'")
		tokens.add(Token(TokenType.OP, op))
		i += op.length
	}

	tokens.add(Token(TokenType.EOF, ""))
	return tokens
}

// ---------------------------------------------------------------------------
// Parser -- precedence climbing
// ---------------------------------------------------------------------------

private val PRECEDENCE = mapOf(
	"||" to 1,
	"&&" to 2,
	"==" to 3, "!=" to 3,
	"<" to 4, ">" to 4, "<=" to 4, ">=" to 4,
	"+" to 5, "-" to 5,
	"*" to 6, "/" to 6, "%" to 6
)

private class Parser(private val tokens: List<Token>) {

	private var pos = 0

	private fun peek() = tokens[pos]

	private fun advance() = tokens[pos++]

	fun parse(): Expr {
		val expr = parseBinary(0)
		if (peek().type != TokenType.EOF) {
			throw ExpressionException("unexpected '${peek().text}'")
		}
		return expr
	}

	private fun parseBinary(minPrecedence: Int): Expr {
		var left = parseUnary()
		while (true) {
			val token = peek()
			if (token.type != TokenType.OP) break
			val precedence = PRECEDENCE[token.text] ?: break
			if (precedence < minPrecedence) break
			advance()
			// All operators here are left-associative.
			val right = parseBinary(precedence + 1)
			left = Expr.Binary(token.text, left, right)
		}
		return left
	}

	private fun parseUnary(): Expr {
		val token = peek()
		if (token.type == TokenType.OP && (token.text == "!" || token.text == "-")) {
			advance()
			return Expr.Unary(token.text, parseUnary())
		}
		return parsePrimary()
	}

	private fun parsePrimary(): Expr {
		val token = advance()
		return when (token.type) {
			TokenType.NUMBER ->
				Expr.Lit(Value.Num(token.text.toLongOrNull() ?: throw ExpressionException("bad number '${token.text}'")))

			TokenType.STRING -> Expr.Lit(Value.Str(token.text))

			TokenType.IDENT -> when {
				token.text == "true" -> Expr.Lit(Value.Bool(true))
				token.text == "false" -> Expr.Lit(Value.Bool(false))
				peek().type == TokenType.LPAREN -> {
					advance()
					val args = mutableListOf<Expr>()
					if (peek().type != TokenType.RPAREN) {
						while (true) {
							args.add(parseBinary(0))
							if (peek().type == TokenType.COMMA) advance() else break
						}
					}
					if (advance().type != TokenType.RPAREN) throw ExpressionException("expected ')'")
					Expr.Call(token.text, args)
				}
				else -> Expr.Var(token.text)
			}

			TokenType.LPAREN -> {
				val inner = parseBinary(0)
				if (advance().type != TokenType.RPAREN) throw ExpressionException("expected ')'")
				inner
			}

			else -> throw ExpressionException("unexpected '${token.text}'")
		}
	}
}

/** Throws [ExpressionException] on a malformed expression. */
fun parseExpression(source: String): Expr = Parser(lex(source)).parse()

// ---------------------------------------------------------------------------
// Evaluator
// ---------------------------------------------------------------------------

suspend fun evaluate(expr: Expr, context: EvalContext): Value = when (expr) {
	is Expr.Lit -> expr.value
	is Expr.Var -> context.variable(expr.name) ?: Value.Num(0)
	is Expr.Call -> context.call(expr.name, expr.args.map { evaluate(it, context) })

	is Expr.Unary -> when (expr.op) {
		"!" -> Value.Bool(!evaluate(expr.operand, context).asBool())
		"-" -> Value.Num(-evaluate(expr.operand, context).asNum())
		else -> throw ExpressionException("unknown operator '${expr.op}'")
	}

	is Expr.Binary -> evaluateBinary(expr, context)
}

private suspend fun evaluateBinary(expr: Expr.Binary, context: EvalContext): Value {
	// Short-circuit before evaluating the right side, so `image("x") && slow()`
	// does not pay for the right half when the left already decided it.
	if (expr.op == "&&") {
		val left = evaluate(expr.left, context)
		return if (!left.asBool()) Value.Bool(false) else Value.Bool(evaluate(expr.right, context).asBool())
	}
	if (expr.op == "||") {
		val left = evaluate(expr.left, context)
		return if (left.asBool()) Value.Bool(true) else Value.Bool(evaluate(expr.right, context).asBool())
	}

	val left = evaluate(expr.left, context)
	val right = evaluate(expr.right, context)

	return when (expr.op) {
		// Text on either side makes + concatenation rather than addition.
		"+" -> if (left is Value.Str || right is Value.Str) {
			Value.Str(left.asText() + right.asText())
		} else {
			Value.Num(left.asNum() + right.asNum())
		}
		"-" -> Value.Num(left.asNum() - right.asNum())
		"*" -> Value.Num(left.asNum() * right.asNum())
		"/" -> right.asNum().let { if (it == 0L) Value.Num(0) else Value.Num(left.asNum() / it) }
		"%" -> right.asNum().let { if (it == 0L) Value.Num(0) else Value.Num(left.asNum() % it) }

		"==" -> Value.Bool(equalValues(left, right))
		"!=" -> Value.Bool(!equalValues(left, right))
		"<" -> Value.Bool(left.asNum() < right.asNum())
		">" -> Value.Bool(left.asNum() > right.asNum())
		"<=" -> Value.Bool(left.asNum() <= right.asNum())
		">=" -> Value.Bool(left.asNum() >= right.asNum())

		else -> throw ExpressionException("unknown operator '${expr.op}'")
	}
}

private fun equalValues(left: Value, right: Value): Boolean =
	if (left is Value.Str || right is Value.Str) left.asText() == right.asText()
	else left.asNum() == right.asNum()
