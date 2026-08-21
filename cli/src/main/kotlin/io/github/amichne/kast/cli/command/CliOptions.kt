package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText

/** Closed Clikt option presence; optional values never enter domain refinement as null. */
internal sealed interface CliOptionValue<out Value> {
    data object Absent : CliOptionValue<Nothing>

    data class Present<Value>(
        val value: Value,
    ) : CliOptionValue<Value>
}

/**
 * Proof transition: `String option -> ProtocolText option`.
 *
 * Establishes non-blank bounded protocol text. Protocol text's closed refinement failure becomes a
 * Clikt usage rejection. Raw option text is extracted only inside Clikt's conversion boundary.
 */
internal fun ParameterHolder.protocolTextOption(
    name: String,
    help: String,
): NullableOption<ProtocolText, ProtocolText> = option(name, help = help, metavar = "text")
    .convert("text") { raw ->
        when (val parsed = ProtocolText.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> fail(
                "must be non-blank and no longer than 1048576 characters",
            )
        }
    }

/**
 * Proof transition: `String option -> ProtocolCount option`.
 *
 * Establishes an integer in `1..1000`. Malformed and out-of-range values become Clikt usage
 * rejections. Raw option text is extracted only inside Clikt's conversion boundary.
 */
internal fun ParameterHolder.protocolCountOption(
    name: String,
    help: String,
): NullableOption<ProtocolCount, ProtocolCount> = option(name, help = help, metavar = "1..1000")
    .convert("1..1000") { raw ->
        val number = raw.toIntOrNull() ?: fail("must be an integer in 1..1000")
        when (val parsed = ProtocolCount.parse(number)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> fail("must be an integer in 1..1000")
        }
    }

/**
 * Proof transition: `String option -> ProtocolOffset option`.
 *
 * Establishes a non-negative integer offset. Malformed and negative values become Clikt usage
 * rejections. Raw option text is extracted only inside Clikt's conversion boundary.
 */
internal fun ParameterHolder.protocolOffsetOption(
    name: String,
    help: String,
): NullableOption<ProtocolOffset, ProtocolOffset> = option(name, help = help, metavar = "offset")
    .convert("offset") { raw ->
        val number = raw.toIntOrNull() ?: fail("must be a non-negative integer")
        when (val parsed = ProtocolOffset.parse(number)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> fail("must be a non-negative integer")
        }
    }

/**
 * Proof transition: `String option + Map<String, Value> -> Value option`.
 *
 * Establishes one member of the supplied closed value set. Unknown text becomes a Clikt usage
 * rejection. Raw option text is extracted only inside Clikt's conversion boundary.
 */
internal fun <Value : Any> ParameterHolder.closedChoiceOption(
    name: String,
    metavar: String,
    help: String,
    values: Map<String, Value>,
): NullableOption<Value, Value> = option(name, help = help, metavar = metavar)
    .convert(metavar) { raw ->
        values[raw] ?: fail("must be one of ${values.keys.joinToString(", ")}")
    }

/**
 * Proof transition: `NullableOption<Value> -> required exactly-once Value option`.
 *
 * Establishes that the option appeared exactly once. Missing and duplicate calls become closed
 * Clikt usage failures. Raw invocation multiplicity remains inside Clikt's option boundary.
 */
internal fun <Value, Raw> NullableOption<Value, Raw>.requiredOnce():
    OptionWithValues<Value, Value, Raw> = transformAll(showAsRequired = true) { calls ->
        when (calls.size) {
            0 -> throw MissingOption(option)
            1 -> calls.single()
            else -> fail("may be specified exactly once")
        }
    }

/**
 * Proof transition: `NullableOption<Value> -> optional at-most-once Value option`.
 *
 * Establishes absence or exactly one supplied value. Duplicate calls become a Clikt usage
 * rejection. Raw invocation multiplicity remains inside Clikt's option boundary.
 */
internal fun <Value, Raw> NullableOption<Value, Raw>.optionalOnce():
    OptionWithValues<CliOptionValue<Value>, Value, Raw> = transformAll { calls ->
        when (calls.size) {
            0 -> CliOptionValue.Absent
            1 -> CliOptionValue.Present(calls.single())
            else -> fail("may be specified at most once")
        }
    }

/**
 * Proof transition: `NullableOption<Value> + Value -> exactly-once-or-default Value option`.
 *
 * Establishes either one supplied value or the declared default. Duplicate calls become a Clikt
 * usage rejection. Raw invocation multiplicity remains inside Clikt's option boundary.
 */
internal fun <Value, Raw> NullableOption<Value, Raw>.defaultOnce(
    default: Value,
    defaultForHelp: String,
): OptionWithValues<Value, Value, Raw> = transformAll(defaultForHelp = defaultForHelp) { calls ->
    when (calls.size) {
        0 -> default
        1 -> calls.single()
        else -> fail("may be specified at most once")
    }
}
