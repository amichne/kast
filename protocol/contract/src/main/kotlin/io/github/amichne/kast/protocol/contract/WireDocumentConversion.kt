package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Closed result of refining one generated wire document into its protocol-owned value. */
sealed interface WireDocumentConversion<out Value> {
    data class Converted<Value>(val value: Value) : WireDocumentConversion<Value>

    data object Rejected : WireDocumentConversion<Nothing>
}

inline fun <Value, Result> WireDocumentConversion<Value>.mapConverted(
    transform: (Value) -> Result
): WireDocumentConversion<Result> =
    when (this) {
        is WireDocumentConversion.Converted -> WireDocumentConversion.Converted(transform(value))
        is WireDocumentConversion.Rejected -> this
    }

inline fun <Value, Result> WireDocumentConversion<Value>.flatMapConverted(
    transform: (Value) -> WireDocumentConversion<Result>
): WireDocumentConversion<Result> =
    when (this) {
        is WireDocumentConversion.Converted -> transform(value)
        is WireDocumentConversion.Rejected -> this
    }

fun <Value> WireDocumentConversion<WireDocumentConversion<Value>>.flattenConverted(): WireDocumentConversion<Value> =
    flatMapConverted {
        it
    }

inline fun <First, Second, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    transform: (First, Second) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    second.mapConverted { admittedSecond -> transform(admittedFirst, admittedSecond) }
}

inline fun <First, Second, Third, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    transform: (First, Second, Third) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third) { admittedSecond, admittedThird ->
        transform(admittedFirst, admittedSecond, admittedThird)
    }
}

inline fun <First, Second, Third, Fourth, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    fourth: WireDocumentConversion<Fourth>,
    transform: (First, Second, Third, Fourth) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third, fourth) { admittedSecond, admittedThird, admittedFourth ->
        transform(admittedFirst, admittedSecond, admittedThird, admittedFourth)
    }
}

inline fun <First, Second, Third, Fourth, Fifth, Result> combineConverted(
    first: WireDocumentConversion<First>,
    second: WireDocumentConversion<Second>,
    third: WireDocumentConversion<Third>,
    fourth: WireDocumentConversion<Fourth>,
    fifth: WireDocumentConversion<Fifth>,
    transform: (First, Second, Third, Fourth, Fifth) -> Result,
): WireDocumentConversion<Result> = first.flatMapConverted { admittedFirst ->
    combineConverted(second, third, fourth, fifth) { value2, value3, value4, value5 ->
        transform(admittedFirst, value2, value3, value4, value5)
    }
}

inline fun <Input, Output> Iterable<Input>.convertEach(
    transform: (Input) -> WireDocumentConversion<Output>
): WireDocumentConversion<List<Output>> {
    val converted = mutableListOf<Output>()
    for (input in this) {
        when (val conversion = transform(input)) {
            is WireDocumentConversion.Converted -> converted += conversion.value
            is WireDocumentConversion.Rejected -> return conversion
        }
    }
    return WireDocumentConversion.Converted(converted.toList())
}

/**
 * Proof transition: `Refinement<Value, Failure> -> WireDocumentConversion<Value>`.
 *
 * Preserves a successful domain refinement and closes every domain rejection as [WireDocumentConversion.Rejected]. The
 * public wire failure retains the value's semantic role; raw boundary primitives remain owned by the calling generated
 * document mapper.
 */
fun <Value, Failure> Refinement<Value, Failure>.toWireDocumentConversion(): WireDocumentConversion<Value> =
    when (this) {
        is Refinement.Refined -> WireDocumentConversion.Converted(value)
        is Refinement.Rejected -> WireDocumentConversion.Rejected
    }
