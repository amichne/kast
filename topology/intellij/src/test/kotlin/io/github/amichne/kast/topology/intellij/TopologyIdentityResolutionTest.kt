package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.topology.contract.TopologyBindingFailure
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
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
    fun `matching compiler renderings cannot admit a wrong source role`() {
        val file = candidates().files.single()
        val registry = symbol(file, listOf("kotlin.String"))
        val wrong = symbol(file, listOf("kotlin.String"), CompilerSymbolKind.CONSTRUCTOR)
        assertEquals(registry.evidence.compilerIdentity, wrong.evidence.compilerIdentity)
        val result = assertInstanceOf(Refinement.Rejected::class.java, TopologyBindingRole.admit(
            registry.evidence.kind,
            TopologySourceRole.from(registry.evidence.kind),
            TopologySourceRole.from(wrong.evidence.kind),
        ))
        assertEquals(TopologyBindingFailure.ROLE_MISMATCH, result.failure)
    }

    @Test
    fun `every supported matching native role is admitted`() {
        CompilerSymbolKind.entries.forEach { kind ->
            assertInstanceOf(Refinement.Refined::class.java, TopologyBindingRole.admit(
                kind, TopologySourceRole.from(kind), TopologySourceRole.from(kind),
            ))
        }
    }

    @Test
    fun `unsupported native roles never establish binding`() {
        val result = assertInstanceOf(Refinement.Rejected::class.java, TopologyBindingRole.admit(
            CompilerSymbolKind.FUNCTION, TopologySourceRole.FUNCTION, TopologySourceRole.UNSUPPORTED,
        ))
        assertEquals(TopologyBindingFailure.ORIGIN_NOT_ADMITTED, result.failure)
    }

    @Test
    fun `location lookup returns only the exact registry candidate`() {
        val candidates = candidates()
        val file = candidates.files.single()
        val symbol = symbol(file, listOf("T"))
        val key = TopologyProjectionRegistryKey.from(candidates)
        val registry = TopologyProjectionRegistry.from(key, listOf(symbol)).refined()
        val found = assertInstanceOf(TopologyRegistryCandidateLookup.Found::class.java,
            registry.candidateAt(file, 0, 7))
        assertSame(symbol, found.candidate.symbol)
        assertEquals(key, found.candidate.key)
        assertEquals(TopologyRegistryCandidateLookup.Unavailable, registry.candidateAt(file, 1, 7))
        assertEquals(TopologyRegistryCandidateLookup.Rejected, registry.candidateAt(file, -1, 7))
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

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
