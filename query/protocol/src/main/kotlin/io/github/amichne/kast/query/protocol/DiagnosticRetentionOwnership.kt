package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.DiagnosticStateAttempt as Attempt
import io.github.amichne.kast.query.protocol.DiagnosticStateExecution as Execution
import io.github.amichne.kast.query.protocol.DiagnosticStateInput as Input
import io.github.amichne.kast.query.protocol.DiagnosticStateKey as Key
import io.github.amichne.kast.query.protocol.DiagnosticStateLifetime as Lifetime
import java.util.UUID

/** These transitions run only while the existing diagnostic owner holds its admission/publication lock. */
internal fun DiagnosticCheckpointStore.active(attempt: Attempt) =
    lifetime == Lifetime.ACTIVE && clock() - attempt.createdAt < ttl

internal fun DiagnosticCheckpointStore.reachable(start: Key?): Set<Key> {
    if (start == null) return emptySet()
    val seen = linkedSetOf<Key>()
    var next: Key? = start
    while (next != null && seen.add(next)) {
        val entry = entries[next] ?: return emptySet()
        next =
            when (val input = entry.input) {
                is Input.Output -> input.page.continuation()?.let { Key.Continuation(it) }
                is Input.Scan -> null
                Input.Consumed -> {
                    val dependencies = (entry.execution as? Execution.Published)?.children.orEmpty()
                    if (dependencies.any { it !in entries }) return emptySet()
                    seen.addAll(dependencies)
                    null
                }
            }
    }
    return seen
}

internal fun DiagnosticCheckpointStore.pinned(): Set<Key> =
    attempts.values.flatMap { listOf(it.parent) + it.children }.toSet()

internal fun DiagnosticCheckpointStore.dependencies(): Set<Key> =
    entries.values.flatMap { (it.execution as? Execution.Published)?.children.orEmpty() }.toSet()

internal fun DiagnosticCheckpointStore.bytes(): Long {
    val total = entries.values.sumOf { it.bytes } + attempts.size * DIAGNOSTIC_ENTRY_OVERHEAD
    highWaterBytes = maxOf(highWaterBytes, total)
    return total
}

internal fun DiagnosticCheckpointStore.makeCapacity(
    extraEntries: Int,
    extraBytes: Long,
    protected: Set<Key> = emptySet(),
): Boolean {
    if (extraEntries > capacity || extraBytes > maximumBytes) return false
    while (entries.size + attempts.size + extraEntries > capacity || bytes() + extraBytes > maximumBytes) {
        val excluded = pinned() + dependencies() + protected
        val victim =
            entries.entries.firstOrNull {
                it.key !in excluded && it.value.owner == null && it.value.execution !is Execution.Running
            } ?: return false
        entries.remove(victim.key)
        val previouslyOwned = (victim.value.execution as? Execution.Published)?.children.orEmpty()
        val surviving = dependencies() + pinned() + protected
        previouslyOwned
            .filter { it !in surviving }
            .forEach { child ->
                if (entries[child]?.execution == Execution.Ready) entries.remove(child)
            }
    }
    return true
}

internal fun DiagnosticCheckpointStore.expire() {
    val pinned = pinned()
    val now = clock()
    entries.entries.removeIf { it.key !in pinned && it.value.owner == null && now - it.value.createdAt >= ttl }
}

internal fun DiagnosticCheckpointStore.issueToken(prefix: String): Refinement<ProtocolText, DiagnosticCheckRejection> {
    val token =
        when (val parsed = ProtocolText.parse(prefix + UUID.randomUUID())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        }
    return if (Key.Continuation(token) in entries)
        Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
    else Refinement.Refined(token)
}
