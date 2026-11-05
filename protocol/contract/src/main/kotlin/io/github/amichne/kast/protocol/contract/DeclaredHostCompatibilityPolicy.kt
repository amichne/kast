package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Bind a separately compiled Host to its declared SDK before admitting the actual running patch identities. */
object DeclaredHostCompatibilityPolicy {
    fun define(
        targets: DeclaredIntellijTargets,
        compiled: HostedCompatibilityDocument,
    ): Refinement<IdeHostCompatibilityPolicy, IdeHostCompatibilityFailure> {
        val baseline =
            when (val parsed = AdmittedIdeHostCompatibility.parse(compiled.candidate())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
        val target =
            when (val selected = targets.forReleaseLine(baseline.ideBuild.releaseLine.value)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        IdeHostCompatibilityFailure.Mismatch(
                            IdeHostCompatibilityMismatch.IdeBuild(targets.defaultTarget.ideBuild, baseline.ideBuild)
                        )
                    )
            }
        if (baseline.ideBuild != target.ideBuild)
            return Refinement.Rejected(
                IdeHostCompatibilityFailure.Mismatch(
                    IdeHostCompatibilityMismatch.IdeBuild(target.ideBuild, baseline.ideBuild)
                )
            )
        if (baseline.kotlinPluginBuild != target.kotlinPluginBuild)
            return Refinement.Rejected(
                IdeHostCompatibilityFailure.Mismatch(
                    IdeHostCompatibilityMismatch.KotlinPluginBuild(target.kotlinPluginBuild, baseline.kotlinPluginBuild)
                )
            )
        return IdeHostCompatibilityPolicy.define(compiled.candidate())
    }
}
