package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectTestRead
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparationFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.DetachedClasspathBoundary
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedModelBoundary
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture
import io.github.amichne.kast.workspace.intellij.read.DetachedModelObservation
import io.github.amichne.kast.workspace.intellij.read.DetachedModuleBoundary
import io.github.amichne.kast.workspace.intellij.read.DetachedSdkBoundary
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootBoundary
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedIdeReadProductionCompositionNegativeTest {
    @Test
    fun `partial runtime and mismatched cached model reject before endpoint generation`() {
        val project = hostedProject(ROOT)
        assertEquals(
            HostedIdeReadRuntimePreparation.Rejected(
                HostedIdeReadRuntimePreparationFailure.PARTIAL_RUNTIME,
            ),
            HostedIdeReadRuntime.prepare(project),
        )
        assertEquals(
            HostedIdeReadProductionCompositionPreparation.Rejected(
                HostedIdeReadProductionCompositionFailure.MODEL_UNAVAILABLE,
            ),
            HostedIdeReadProductionComposition.prepare(
                project,
                detachedModel(OTHER_ROOT, project),
            ),
        )
    }
}

class HostedIdeReadProductionCompositionAcceptanceTest {
    @Test
    fun `exact cached model activates exactly four route runtime after generation issuance`() {
        val project = hostedProject(ROOT)
        val prepared = assertInstanceOf(
            HostedIdeReadProductionCompositionPreparation.Prepared::class.java,
            HostedIdeReadProductionComposition.prepare(project, detachedModel(ROOT, project)),
        )
        val runtime = prepared.composition.activate(refined(EvidenceGeneration.parse(31)))

        assertInstanceOf(HostedIdeReadRuntimePreparation.Prepared::class.java, runtime)
    }

    @Test
    fun `cached generated-source provenance survives native scope compilation`() {
        val project = hostedProject(ROOT)
        val compiled = assertInstanceOf(
            WorkspaceSearchScopeModelCompilation.Compiled::class.java,
            detachedModel(
                ROOT,
                project,
                DetachedSourceRootProvenance.GENERATED,
            ).compileHostedSearchScope(),
        )

        assertEquals(
            WorkspaceSourceRootProvenance.GENERATED,
            compiled.model.sourceRoots.single().provenance,
        )
    }
}

private fun hostedProject(root: String): HostedIdeReadProject {
    val candidate = IdeHostCompatibilityCandidate(
        ideBuild = "262.9437.185",
        kotlinPluginBuild = "262.9437.185-IJ",
        kastPluginVersion = "1.2.3",
        runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
        operationRegistryDigest = "sha256:" + "1".repeat(64),
        wireSchemaDigest = "sha256:" + "2".repeat(64),
        capabilities = listOf(
            "workspace.inspect",
            "symbol.discover",
            "symbol.resolve",
            "symbol.describe",
        ),
    )
    val policy = refined(IdeHostCompatibilityPolicy.define(candidate))
    val compatibility = when (val admission = policy.admit(candidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> admission.compatibility
        is IdeHostCompatibilityAdmission.Rejected -> error("fixture rejected: ${admission.failure}")
    }
    return HostedIdeReadProject.testing(
        refined(IdeEndpointCanonicalRoot.parse(root)),
        compatibility,
        HostedIdeReadProjectTestRead.CURRENT,
    )
}

private fun detachedModel(
    root: String,
    project: HostedIdeReadProject,
    provenance: DetachedSourceRootProvenance = DetachedSourceRootProvenance.AUTHORED,
): DetachedIdeWorkspaceModel {
    val canonicalRoot = refined(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)))
    val capture = DetachedIdeWorkspaceModel.admit(
        canonicalRoot,
        project.compatibility,
        DetachedModelObservation.Observed(
            DetachedModelBoundary(
                disposed = false,
                smart = true,
                projectRoot = root,
                gradleModelComplete = true,
                modules = listOf(
                    DetachedModuleBoundary(
                        disposed = false,
                        name = "kast.main",
                        gradleOwned = true,
                        gradleBuildRoot = root,
                        gradleProjectRoot = root,
                        gradleProjectIdentity = ":",
                        sourceRoots = listOf(
                            DetachedSourceRootBoundary(
                                path = "$root/src/main/kotlin",
                                kind = DetachedSourceRootKind.PRODUCTION,
                                provenance = provenance,
                            ),
                        ),
                        sdk = DetachedSdkBoundary("JDK 25", "JavaSDK", "25.0.3"),
                        classpath = listOf(DetachedClasspathBoundary("file://$root/build/classes")),
                    ),
                ),
            ),
        ),
    )
    return (capture as DetachedModelCapture.Captured).model
}

private fun <Value, Failure> refined(result: Refinement<Value, Failure>): Value = when (result) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> error("fixture rejected: ${result.failure}")
}

private const val ROOT = "/workspace/kast"
private const val OTHER_ROOT = "/workspace/other"
