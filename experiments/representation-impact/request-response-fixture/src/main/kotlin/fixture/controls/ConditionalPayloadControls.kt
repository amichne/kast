package fixture.controls

sealed class Parcel<out P, out V> {
    @ConsistentCopyVisibility
    data class Accepted<P, V> internal constructor(val payload: V) : Parcel<P, V>()

    @ConsistentCopyVisibility
    data class Rejected<P, V> internal constructor(val payload: P) : Parcel<P, V>()

    fun <R> convert(operation: (V) -> R): Parcel<P, R> = when (this) {
        is Accepted -> accepted(operation(payload))
        is Rejected -> rejected(payload)
    }

    fun <R> convertProblem(operation: (P) -> R): Parcel<R, V> = when (this) {
        is Accepted -> accepted(payload)
        is Rejected -> rejected(operation(payload))
    }

    companion object {
        fun <V> accepted(payload: V): Parcel<Nothing, V> = Accepted(payload)

        fun <P> rejected(payload: P): Parcel<P, Nothing> = Rejected(payload)
    }
}

object ConditionalPayloadControls {
    fun identity(source: Parcel<String, String>): Parcel<String, String> = source.convert { it }

    fun constant(source: Parcel<String, String>): Parcel<String, String> = source.convert { "constant" }
}
