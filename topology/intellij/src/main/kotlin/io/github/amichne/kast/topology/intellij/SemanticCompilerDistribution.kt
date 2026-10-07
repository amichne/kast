package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement

/**
 * Explicit alternate distributions need their own content inventory. Encoding a path as a compiler argument does not
 * establish that its contents match the currently captured SDK and classpath inputs.
 */
internal fun admitSemanticCompilerDistribution(
    kotlinHome: String?,
    intellijPluginRoot: String?,
): SemanticCapture<Unit> =
    if (!kotlinHome.isNullOrEmpty() || !intellijPluginRoot.isNullOrEmpty())
        captureRejected(SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED)
    else Refinement.Refined(Unit)
