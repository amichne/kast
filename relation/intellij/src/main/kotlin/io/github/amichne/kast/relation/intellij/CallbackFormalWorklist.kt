package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackParameterIdentity

internal enum class CallbackFormalSchedule {
    SCHEDULED,
    ALREADY_DISCOVERED,
}

internal sealed interface CallbackFormalWork<out T> {
    data object Exhausted : CallbackFormalWork<Nothing>

    data class Pending<T>(val value: T) : CallbackFormalWork<T>
}

/** Request-owned frontier; only newly discovered exact formals can acquire another body scan. */
internal class CallbackFormalWorklist<T>(root: CallbackParameterIdentity, initial: T) {
    private val discovered = linkedSetOf(root)
    private val frontier = ArrayDeque<T>().apply { addLast(initial) }

    val formals: List<CallbackParameterIdentity>
        get() = discovered.toList()

    fun schedule(formal: CallbackParameterIdentity, value: T): CallbackFormalSchedule =
        if (discovered.add(formal)) {
            frontier.addLast(value)
            CallbackFormalSchedule.SCHEDULED
        } else CallbackFormalSchedule.ALREADY_DISCOVERED

    fun next(): CallbackFormalWork<T> =
        if (frontier.isEmpty()) CallbackFormalWork.Exhausted else CallbackFormalWork.Pending(frontier.removeFirst())
}
