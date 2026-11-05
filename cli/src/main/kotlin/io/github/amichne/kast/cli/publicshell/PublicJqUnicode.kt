package io.github.amichne.kast.cli.publicshell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.exception.JsonQueryException

/** Stock 1.6.5 implode narrows scalars to UTF-16 chars. Keep the complete Unicode scalar instead. */
internal object PublicJqUnicode {
    fun install(scope: Scope) {
        scope.addFunction("implode", 0) { _, _, value, _, output, _ ->
            if (!value.isArray) throw JsonQueryException("implode requires an array of Unicode scalars")
            val text = buildString { value.forEach { appendCodePoint(scalar(it)) } }
            output.emit(TextNode(text), null)
        }
    }

    private fun scalar(value: JsonNode): Int {
        if (!value.isNumber) invalidScalar()
        val point = integer(value)
        if (!Character.isValidCodePoint(point) || point in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code)
            invalidScalar()
        return point
    }

    private fun integer(value: JsonNode): Int =
        try {
            value.decimalValue().intValueExact()
        } catch (_: ArithmeticException) {
            invalidScalar()
        } catch (_: NumberFormatException) {
            invalidScalar()
        }

    private fun invalidScalar(): Nothing = throw JsonQueryException("implode requires valid integral Unicode scalars")
}
