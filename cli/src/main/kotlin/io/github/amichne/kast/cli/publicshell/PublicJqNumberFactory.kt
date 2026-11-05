package io.github.amichne.kast.cli.publicshell

import com.fasterxml.jackson.databind.node.BigIntegerNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.NumericNode
import com.fasterxml.jackson.databind.node.ValueNode
import java.math.BigDecimal
import java.math.BigInteger

/** Preserve original JSON numbers for navigation; reject the engine's narrowing conversions when they change value. */
internal object PublicJqNumberFactory : JsonNodeFactory() {
    override fun numberNode(value: Long): NumericNode = CheckedJqLong(value)

    override fun numberNode(value: BigInteger?): ValueNode = if (value == null) nullNode() else CheckedJqInteger(value)

    override fun numberNode(value: BigDecimal?): ValueNode = if (value == null) nullNode() else CheckedJqDecimal(value)
}

internal class JqNumericValueLoss : RuntimeException()

private class CheckedJqLong(private val original: Long) : LongNode(original) {
    override fun doubleValue(): Double = checkedJqDouble(BigDecimal.valueOf(original))

    override fun intValue(): Int = exactJqInteger { Math.toIntExact(original) }
}

private class CheckedJqInteger(private val original: BigInteger) : BigIntegerNode(original) {
    override fun doubleValue(): Double = checkedJqDouble(original.toBigDecimal())

    override fun longValue(): Long = exactJqInteger { original.longValueExact() }

    override fun intValue(): Int = exactJqInteger { original.intValueExact() }
}

private class CheckedJqDecimal(private val original: BigDecimal) : DecimalNode(original) {
    override fun doubleValue(): Double = checkedJqDouble(original)

    override fun longValue(): Long = exactJqInteger { original.longValueExact() }

    override fun intValue(): Int = exactJqInteger { original.intValueExact() }
}

private fun checkedJqDouble(original: BigDecimal): Double {
    val value = original.toDouble()
    if (!value.isFinite() || BigDecimal.valueOf(value).compareTo(original) != 0) throw JqNumericValueLoss()
    return value
}

private inline fun <Value> exactJqInteger(convert: () -> Value): Value =
    try {
        convert()
    } catch (_: ArithmeticException) {
        throw JqNumericValueLoss()
    }
