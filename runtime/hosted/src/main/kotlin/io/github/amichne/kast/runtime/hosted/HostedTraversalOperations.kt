package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase

/**
 * Observes exclusive traversal coordination intervals, including unphased relation-service overhead. Native relation
 * phases retain their own durations; this is not an inclusive traversal span. The existing traversal engine and
 * relation authority retain every request and outcome unchanged.
 */
internal fun hostedTraversalOperations(
    relations: RelationOperations,
    observation: IntellijReadObservation,
): TraversalOperations {
    val traversal =
        traversalOperations(
            RelationOperations { request ->
                try {
                    relations.read(request)
                } finally {
                    observation.phase(IntellijReadPhase.TRAVERSAL)
                }
            }
        )
    return TraversalOperations { plan ->
        observation.phase(IntellijReadPhase.TRAVERSAL)
        traversal.run(plan)
    }
}
