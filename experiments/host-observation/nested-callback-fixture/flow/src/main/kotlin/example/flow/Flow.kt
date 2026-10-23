package example.flow

class FlowScope

fun <T> runFlow(block: FlowScope.() -> T): T = FlowScope().block()

sealed interface Attempt<out T> {
    data class Value<T>(val value: T) : Attempt<T>

    data class Failure(val cause: Throwable) : Attempt<Nothing>
}

fun <T> capture(block: () -> T): Attempt<T> = try {
    Attempt.Value(block())
} catch (cause: Throwable) {
    Attempt.Failure(cause)
}
