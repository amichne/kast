package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeModule
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeSourceRoot
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import java.nio.file.Path

/** Exactly one supported cached source-folder owner, without claiming Gradle producer provenance. */
internal class HostedSourceScope private constructor(val module: DetachedIdeModule, val root: DetachedIdeSourceRoot) {
    companion object {
        fun admit(
            model: DetachedIdeWorkspaceModel,
            file: SymbolDiscoveryFileIdentity.Workspace,
        ): Refinement<HostedSourceScope, HostedQueryFailure> {
            val owners =
                model.modules.flatMap { module ->
                    module.sourceRoots
                        .filter { root ->
                            Path.of(file.path.value)
                                .startsWith(Path.of(model.canonicalRoot.value).resolve(root.location.value))
                        }
                        .map { module to it }
                }
            val (module, root) =
                owners.singleOrNull()
                    ?: return Refinement.Rejected(
                        if (owners.isEmpty()) HostedQueryFailure.OUTSIDE_SCOPE else HostedQueryFailure.AMBIGUOUS_SCOPE
                    )
            if (
                root.kind !in setOf(DetachedSourceRootKind.PRODUCTION, DetachedSourceRootKind.TEST) ||
                    root.provenance != DetachedSourceRootProvenance.AUTHORED
            )
                return Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_MODEL)
            return Refinement.Refined(HostedSourceScope(module, root))
        }
    }
}
