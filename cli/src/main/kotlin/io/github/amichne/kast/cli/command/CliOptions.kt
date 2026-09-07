package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed Clikt option presence; optional values never enter domain refinement as null. */
internal sealed interface CliOptionValue<out Value> {
    data object Absent : CliOptionValue<Nothing>

    data class Present<Value>(
        val value: Value,
    ) : CliOptionValue<Value>
}

/** Refines an option to an absolute normalized path; physical identity is proven by its owner. */
internal fun ParameterHolder.absolutePathOption(
    name: String,
    help: String,
): NullableOption<Path, Path> = option(name, help = help, metavar = "absolute-path")
    .convert("absolute-path") { raw ->
        val candidate = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            fail("must be an absolute normalized path")
        }
        if (!candidate.isAbsolute || candidate.normalize() != candidate) {
            fail("must be an absolute normalized path")
        }
        candidate
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
