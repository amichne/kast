package io.github.amichne.kast.kernel

/**
 * An ordered collection whose representation proves that at least one failure exists.
 *
 * The constructor is closed so an empty failure protocol cannot cross into the strong core.
 */
class NonEmptyFailures<out Failure> private constructor(
    private val first: Failure,
    private val remaining: List<Failure>,
) : Iterable<Failure> {
    val size: Int
        get() = 1 + remaining.size

    override fun iterator(): Iterator<Failure> = sequenceOf(first).plus(remaining.asSequence()).iterator()

    fun toList(): List<Failure> = listOf(first) + remaining

    internal fun concatenate(
        other: NonEmptyFailures<@UnsafeVariance Failure>,
    ): NonEmptyFailures<Failure> = NonEmptyFailures(first, remaining + other.first + other.remaining)

    override fun equals(other: Any?): Boolean =
        other is NonEmptyFailures<*> && toList() == other.toList()

    override fun hashCode(): Int = toList().hashCode()

    override fun toString(): String = toList().toString()

    companion object {
        fun <Failure> one(failure: Failure): NonEmptyFailures<Failure> =
            NonEmptyFailures(first = failure, remaining = emptyList())

        fun <Failure> from(
            first: Failure,
            remaining: Iterable<Failure>,
        ): NonEmptyFailures<Failure> = NonEmptyFailures(first, remaining.toList())
    }
}

/**
 * Closed result of admitting a weak value when independent checks may all contribute failures.
 *
 * [Validated] carries the stronger representation. [Rejected] always carries at least one typed
 * failure and preserves definition order when validations are combined.
 */
sealed interface Validation<out Strong, out Failure> {
    data class Validated<Strong>(
        val value: Strong,
    ) : Validation<Strong, Nothing>

    data class Rejected<Failure>(
        val failures: NonEmptyFailures<Failure>,
    ) : Validation<Nothing, Failure>

    companion object {
        fun <Strong> validated(value: Strong): Validation<Strong, Nothing> = Validated(value)

        fun <Strong, Failure> rejected(failure: Failure): Validation<Strong, Failure> =
            Rejected(NonEmptyFailures.one(failure))
    }
}

/** Transforms an admitted value while retaining an already-proven rejection unchanged. */
inline fun <Strong, Transformed, Failure> Validation<Strong, Failure>.map(
    transform: (Strong) -> Transformed,
): Validation<Transformed, Failure> = when (this) {
    is Validation.Validated -> Validation.Validated(transform(value))
    is Validation.Rejected -> Validation.Rejected(failures)
}

/** Refines failure values without changing whether the validation succeeded. */
inline fun <Strong, Failure, TransformedFailure> Validation<Strong, Failure>.mapFailures(
    transform: (Failure) -> TransformedFailure,
): Validation<Strong, TransformedFailure> = when (this) {
    is Validation.Validated -> Validation.Validated(value)
    is Validation.Rejected -> {
        val transformed = failures.map(transform)
        Validation.Rejected(
            NonEmptyFailures.from(
                first = transformed.first(),
                remaining = transformed.drop(1),
            ),
        )
    }
}

/**
 * Applicatively combines independent validations, retaining every failure in evaluation order.
 */
fun <First, Second, Combined, Failure> Validation<First, Failure>.zipAccumulating(
    other: Validation<Second, Failure>,
    combine: (First, Second) -> Combined,
): Validation<Combined, Failure> = when (this) {
    is Validation.Validated -> when (other) {
        is Validation.Validated -> Validation.Validated(combine(value, other.value))
        is Validation.Rejected -> Validation.Rejected(other.failures)
    }

    is Validation.Rejected -> when (other) {
        is Validation.Validated -> Validation.Rejected(failures)
        is Validation.Rejected -> Validation.Rejected(failures.concatenate(other.failures))
    }
}

/** A reusable boundary definition from one weak representation to one proven domain value. */
fun interface RefinementDefinition<in Weak, out Strong, out Failure> {
    fun refine(candidate: Weak): Validation<Strong, Failure>
}

/** Combines two independent definitions over the same weak candidate. */
fun <Weak, First, Second, Combined, Failure>
    RefinementDefinition<Weak, First, Failure>.zipAccumulating(
        other: RefinementDefinition<Weak, Second, Failure>,
        combine: (First, Second) -> Combined,
    ): RefinementDefinition<Weak, Combined, Failure> = RefinementDefinition { candidate ->
    refine(candidate).zipAccumulating(other.refine(candidate), combine)
}

/** Bridges an existing single-failure refinement into the accumulating representation. */
fun <Strong, Failure> Refinement<Strong, Failure>.asValidation(): Validation<Strong, Failure> =
    when (this) {
        is Refinement.Refined -> Validation.Validated(value)
        is Refinement.Rejected -> Validation.Rejected(NonEmptyFailures.one(failure))
    }
