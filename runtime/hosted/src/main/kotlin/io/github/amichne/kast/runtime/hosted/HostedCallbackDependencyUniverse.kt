package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import java.nio.file.Path

internal sealed interface HostedCallbackDependencyUniverse {
    data object WholeWorkspace : HostedCallbackDependencyUniverse

    data class Forward(val module: WorkspaceModuleIdentity) : HostedCallbackDependencyUniverse
}

/** Model ownership is admitted before any source, SDK or classpath enumeration. Ambiguity disables optional reuse. */
internal fun WorkspaceSearchScopeModel.callbackUniverse(
    endpoint: RelationEndpoint
): Refinement<HostedCallbackDependencyUniverse.Forward, HostedCallbackPartitionFailure> {
    val file = Path.of(endpoint.file.stableValue)
    val owners = sourceRoots.filter { file.startsWith(Path.of(it.sourceRoot.value)) }.map { it.module }.toSet()
    return when (owners.size) {
        0 -> Refinement.Rejected(HostedCallbackPartitionFailure.SourceNotInventoried)
        1 -> Refinement.Refined(HostedCallbackDependencyUniverse.Forward(owners.single()))
        else -> Refinement.Rejected(HostedCallbackPartitionFailure.AmbiguousSourceOwnership)
    }
}
