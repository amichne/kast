package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path

internal enum class RelationRootCardinality {
    UNIQUE,
    AMBIGUOUS,
}

internal sealed interface RelationPathOwner {
    data object Unowned : RelationPathOwner

    data class Owned(val path: Path, val cardinality: RelationRootCardinality) : RelationPathOwner
}

/** Request-local imported ownership. Preparation is linear in roots; lookup is bounded by the file's ancestor depth. */
internal class RelationSourceRootIndex(roots: List<Path>) {
    private val owners =
        roots
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, count) ->
                if (count == 1) RelationRootCardinality.UNIQUE else RelationRootCardinality.AMBIGUOUS
            }

    fun owner(
        path: Path,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): RelationPathOwner {
        // Each path has one ancestor at each depth. The first imported ancestor preserves the deepest-owner rule,
        // including excluded nested owners and ambiguous imported ownership.
        for (ancestor in generateSequence(path) { it.parent }) {
            observation.count(IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES)
            val cardinality = owners[ancestor] ?: continue
            return RelationPathOwner.Owned(ancestor, cardinality)
        }
        return RelationPathOwner.Unowned
    }
}
