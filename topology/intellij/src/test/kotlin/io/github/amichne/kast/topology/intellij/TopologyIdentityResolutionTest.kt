package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationRuntimeType
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyCompilerProjectionComponent
import io.github.amichne.kast.topology.contract.TopologyCompilerProjectionEvidence
import io.github.amichne.kast.topology.contract.TopologyIdentityStage
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TopologyIdentityResolutionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `mismatch preserves exact comparison evidence and structural delta`() {
        val candidates = candidates()
        val file = candidates.files.single()
        val registrySymbol = symbol(file, listOf("kotlin.String"))
        val liveSymbol = symbol(file, listOf("kotlin.Int"))
        val registry = TopologyProjectionRegistry.from(
            TopologyProjectionRegistryKey.from(candidates),
            listOf(registrySymbol),
        ).refined()
        val source = TopologyIdentitySource(
            TopologyIdentityStage.REFERENCE_TARGET,
            file,
            registrySymbol.evidence.range,
        )

        val mismatch = assertInstanceOf(
            TopologyIdentityResolution.Mismatched::class.java,
            registry.resolveIdentity(
                source = source,
                targetFile = file,
                targetDeclarationRange = registrySymbol.evidence.range,
                liveProjection = TopologyCompilerProjectionEvidence.from(liveSymbol.evidence),
                liveSymbolRuntimeType = runtimeType("org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol"),
                psiDeclarationRuntimeType = runtimeType("org.jetbrains.kotlin.psi.KtNamedFunction"),
            ),
        )

        assertEquals(TopologyIdentityStage.REFERENCE_TARGET, mismatch.evidence.stage)
        assertEquals(file, mismatch.evidence.sourceFile)
        assertEquals(registrySymbol.evidence.range, mismatch.evidence.sourceOccurrence)
        assertEquals(file, mismatch.evidence.targetFile)
        assertEquals(registrySymbol.evidence.range, mismatch.evidence.targetDeclarationRange)
        assertEquals(
            TopologyCompilerProjectionEvidence.from(registrySymbol.evidence),
            mismatch.evidence.registryProjection,
        )
        assertEquals(
            TopologyCompilerProjectionEvidence.from(liveSymbol.evidence),
            mismatch.evidence.liveProjection,
        )
        assertEquals(
            setOf(
                TopologyCompilerProjectionComponent.VALUE_PARAMETERS,
                TopologyCompilerProjectionComponent.IDENTITY,
            ),
            mismatch.evidence.delta.components,
        )
        assertEquals(
            "org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol",
            mismatch.evidence.liveSymbolRuntimeType.value,
        )
        assertEquals(
            "org.jetbrains.kotlin.psi.KtNamedFunction",
            mismatch.evidence.psiDeclarationRuntimeType.value,
        )
    }

    @Test
    fun `equivalent projection returns the registry owned symbol`() {
        val candidates = candidates()
        val file = candidates.files.single()
        val registrySymbol = symbol(file, listOf("kotlin.String"))
        val registry = TopologyProjectionRegistry.from(
            TopologyProjectionRegistryKey.from(candidates),
            listOf(registrySymbol),
        ).refined()

        val matched = assertInstanceOf(
            TopologyIdentityResolution.Matched::class.java,
            registry.resolveIdentity(
                source = TopologyIdentitySource(
                    TopologyIdentityStage.REFERENCE_TARGET,
                    file,
                    registrySymbol.evidence.range,
                ),
                targetFile = file,
                targetDeclarationRange = registrySymbol.evidence.range,
                liveProjection = TopologyCompilerProjectionEvidence.from(
                    registrySymbol.evidence,
                ),
                liveSymbolRuntimeType = runtimeType("KaNamedFunctionSymbol"),
                psiDeclarationRuntimeType = runtimeType("KtNamedFunction"),
            ),
        )

        assertSame(registrySymbol, matched.symbol)
    }

    @Test
    fun `structural difference cannot widen the compiler identity rejection rule`() {
        val candidates = candidates()
        val file = candidates.files.single()
        val registrySymbol = symbol(file, listOf("kotlin.String"))
        val sameIdentityDifferentKind = symbol(
            file,
            listOf("kotlin.String"),
            CompilerSymbolKind.CONSTRUCTOR,
        )
        val registry = TopologyProjectionRegistry.from(
            TopologyProjectionRegistryKey.from(candidates),
            listOf(registrySymbol),
        ).refined()

        val matched = assertInstanceOf(
            TopologyIdentityResolution.Matched::class.java,
            registry.resolveIdentity(
                source = TopologyIdentitySource(
                    TopologyIdentityStage.REFERENCE_TARGET,
                    file,
                    registrySymbol.evidence.range,
                ),
                targetFile = file,
                targetDeclarationRange = registrySymbol.evidence.range,
                liveProjection = TopologyCompilerProjectionEvidence.from(
                    sameIdentityDifferentKind.evidence,
                ),
                liveSymbolRuntimeType = runtimeType("KaConstructorSymbol"),
                psiDeclarationRuntimeType = runtimeType("KtNamedFunction"),
            ),
        )

        assertSame(registrySymbol, matched.symbol)
    }

    private fun candidates(): TopologyCandidateSet {
        val root = SourceRoot.admit(
            GradleSourceRootEvidence(
                ideaModuleName = "topology.main",
                workspaceRelativeBuildRoot = ".",
                gradleProjectPath = ":topology",
                sourceSetName = "main",
                workspaceRelativeSourceRoot = "src/main/kotlin",
                provenance = SourceRootProvenance.Authored,
            ),
        ).refined()
        val workspace = workspace(root)
        val file = TopologySourceFile.admit(
            workspace,
            root,
            WorkspaceSourcePath.parse("src/main/kotlin/Example.kt").refined(),
            WorkspaceSourceContentHash.parse("a".repeat(64)).refined(),
        ).refined()
        return TopologyCandidateSet.admit(workspace, listOf(file)).refined()
    }

    private fun symbol(
        file: TopologySourceFile,
        parameterTypes: List<String>,
        kind: CompilerSymbolKind = CompilerSymbolKind.FUNCTION,
    ): TopologySymbol {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            file.workspace.lease.workspaceRoot,
            absolute,
            absolute.toUri().toString(),
        ).refined()
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.example",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = parameterTypes,
            rawTypeParameterCount = 0,
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            0,
            7,
            "example",
            "sample.example",
            kind,
            signature,
        ).refined()
        return TopologySymbol.admit(file, evidence).refined()
    }

    private fun workspace(root: SourceRoot): PublishedWorkspace {
        val canonical = CanonicalWorkspaceRoot.fromCanonicalPath(tempDir.toRealPath()).refined()
        val candidate = WorkspaceCandidate(
            canonical,
            WorkspaceStateIdentity.parse("identity-resolution-state").refined(),
        )
        val reconciled = ReconciledWorkspace.admit(
            candidate,
            WorkspaceEvidenceKind.entries.toSet(),
            listOf(root),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(1).refined())
    }

    private fun runtimeType(raw: String): ExactDeclarationRuntimeType =
        ExactDeclarationRuntimeType.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
