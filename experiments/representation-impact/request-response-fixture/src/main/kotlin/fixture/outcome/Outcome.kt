package fixture.outcome

sealed class Outcome<out E, out S> {
    @ConsistentCopyVisibility
    data class Success<E, S> internal constructor(val value: S) : Outcome<E, S>()
    @ConsistentCopyVisibility
    data class Error<E, S> internal constructor(val value: E) : Outcome<E, S>()

    fun <T> map(transform: (S) -> T): Outcome<E, T> = when (this) {
        is Success -> success(transform(value))
        is Error -> error(value)
    }

    fun <T> mapError(transform: (E) -> T): Outcome<T, S> = when (this) {
        is Success -> success(value)
        is Error -> error(transform(value))
    }

    companion object {
        fun <S> success(value: S): Outcome<Nothing, S> = Success(value)

        fun <E> error(value: E): Outcome<E, Nothing> = Error(value)

        fun <E, S> sequential(block: OutcomeScope<E>.() -> S): Outcome<E, S> = try {
            success(OutcomeScope<E>().block())
        } catch (halt: OutcomeScope.Halt) {
            @Suppress("UNCHECKED_CAST")
            error(halt.value as E)
        }
    }
}

class OutcomeScope<E> internal constructor() {
    operator fun <S> Outcome<E, S>.component1(): S = bind()

    fun <S> Outcome<E, S>.bind(): S = when (this) {
        is Outcome.Success -> value
        is Outcome.Error -> throw Halt(value)
    }

    internal class Halt(val value: Any?) : RuntimeException()
}

inline fun <E, S, T> Outcome<E, S>.flatMap(transform: (S) -> Outcome<E, T>): Outcome<E, T> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Error -> Outcome.error(value)
}

inline fun <E, T> T?.requireNotNull(error: () -> E): Outcome<E, T> =
    this?.let { Outcome.success(it) } ?: Outcome.error(error())
