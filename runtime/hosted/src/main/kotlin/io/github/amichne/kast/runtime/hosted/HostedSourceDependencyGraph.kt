package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Entry
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Execution
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Payload
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause

/** Pure detached graph decisions; the existing source owner supplies time and performs every mutation. */
internal fun sourceDependencyClosure(
    entries: Map<ProtocolText, Entry>,
    start: ProtocolText,
    now: Long,
    ttl: Long,
): Refinement<Set<ProtocolText>, HostedPublicationFailureCause> {
    val root = entries[start] ?: return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
    val pending = ArrayDeque<ProtocolText>()
    val seen = linkedSetOf<ProtocolText>()
    pending.add(start)
    while (pending.isNotEmpty()) {
        val token = pending.removeFirst()
        if (!seen.add(token)) continue
        val entry = entries[token] ?: return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        if (sourceEntryExpired(entry, now, ttl)) return Refinement.Rejected(HostedPublicationFailureCause.EXPIRED)
        if (entry.lease != root.lease || entry.request != root.request)
            return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        when (val dependencies = sourceDependencies(entry)) {
            is Refinement.Refined -> pending.addAll(dependencies.value)
            is Refinement.Rejected -> return dependencies
        }
    }
    return Refinement.Refined(java.util.Set.copyOf(seen))
}

internal fun sourceEntryExpired(entry: Entry, now: Long, ttl: Long): Boolean = now - entry.createdAt >= ttl

internal fun sourceExpiredEntries(
    entries: Map<ProtocolText, Entry>,
    claimRoots: Set<ProtocolText>,
    now: Long,
    ttl: Long,
): Set<ProtocolText> {
    val graph = linkedMapOf<ProtocolText, Set<ProtocolText>>()
    val dependents = linkedMapOf<ProtocolText, MutableSet<ProtocolText>>()
    val unavailable = ArrayDeque<ProtocolText>()
    entries.forEach { (token, entry) ->
        val children =
            when (val retained = sourceDependencies(entry)) {
                is Refinement.Refined -> retained.value
                is Refinement.Rejected -> {
                    unavailable.add(token)
                    emptySet()
                }
            }
        graph[token] = children
        if (sourceEntryExpired(entry, now, ttl)) unavailable.add(token)
        children.forEach { child ->
            dependents.getOrPut(child) { linkedSetOf() }.add(token)
            val dependency = entries[child]
            if (dependency == null || dependency.lease != entry.lease || dependency.request != entry.request)
                unavailable.add(token)
        }
    }
    return sourceUnclaimedEntries(unavailable, dependents, sourceClaimedStorage(graph, claimRoots))
}

private fun sourceUnclaimedEntries(
    unavailable: ArrayDeque<ProtocolText>,
    dependents: Map<ProtocolText, Set<ProtocolText>>,
    claimed: Set<ProtocolText>,
): Set<ProtocolText> {
    val seen = linkedSetOf<ProtocolText>()
    val removed = linkedSetOf<ProtocolText>()
    while (unavailable.isNotEmpty()) {
        val token = unavailable.removeFirst()
        if (!seen.add(token)) continue
        if (token !in claimed) removed.add(token)
        dependents[token]?.let(unavailable::addAll)
    }
    return java.util.Set.copyOf(removed)
}

/** Claims retain physical storage; publication pins never extend intrinsic token validity. */
private fun sourceClaimedStorage(
    graph: Map<ProtocolText, Set<ProtocolText>>,
    roots: Set<ProtocolText>,
): Set<ProtocolText> {
    val pending = ArrayDeque<ProtocolText>()
    pending.addAll(roots)
    val seen = linkedSetOf<ProtocolText>()
    while (pending.isNotEmpty()) {
        val token = pending.removeFirst()
        if (seen.add(token)) graph[token]?.let(pending::addAll)
    }
    return seen
}

private fun sourceDependencies(entry: Entry): Refinement<Set<ProtocolText>, HostedPublicationFailureCause> =
    when (val payload = entry.payload) {
        is Payload.Output -> Refinement.Refined(setOfNotNull(payload.outcome.sourceContinuation()))
        is Payload.Cursor -> Refinement.Refined(emptySet())
        Payload.Consumed ->
            when (val execution = entry.execution) {
                is Execution.Published -> Refinement.Refined(execution.children)
                Execution.Ready,
                is Execution.Running -> Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
            }
    }
