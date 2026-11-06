package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Reviewed platform admission stays separate from exact hosted-contract equality. */
enum class SupportedIdeReleaseLine {
    IDEA_262,
    IDEA_263;

    companion object {
        fun parse(raw: String): Refinement<SupportedIdeReleaseLine, IdeReleaseLineFailure> =
            when (raw) {
                "262" -> Refinement.Refined(IDEA_262)
                "263" -> Refinement.Refined(IDEA_263)
                else -> Refinement.Rejected(IdeReleaseLineFailure.UNSUPPORTED)
            }
    }
}

enum class IdeReleaseLineFailure {
    UNSUPPORTED
}

internal fun admitIdePlatformPair(
    expectedIde: IdeBuildIdentity,
    expectedKotlin: KotlinPluginBuildIdentity,
    observedIde: IdeBuildIdentity,
    observedKotlin: KotlinPluginBuildIdentity,
): Refinement<Unit, IdeHostCompatibilityMismatch> =
    when {
        SupportedIdeReleaseLine.parse(expectedIde.releaseLine.value) is Refinement.Rejected ||
            SupportedIdeReleaseLine.parse(observedIde.releaseLine.value) is Refinement.Rejected ->
            Refinement.Rejected(IdeHostCompatibilityMismatch.IdeBuild(expectedIde, observedIde))
        expectedKotlin.releaseLine != expectedIde.releaseLine ->
            Refinement.Rejected(
                IdeHostCompatibilityMismatch.KotlinPluginBuild(
                    KotlinPluginBuildIdentity.forIde(expectedIde),
                    expectedKotlin,
                )
            )
        observedKotlin.releaseLine != observedIde.releaseLine ->
            Refinement.Rejected(
                IdeHostCompatibilityMismatch.KotlinPluginBuild(
                    KotlinPluginBuildIdentity.forIde(observedIde),
                    observedKotlin,
                )
            )
        else -> Refinement.Refined(Unit)
    }
