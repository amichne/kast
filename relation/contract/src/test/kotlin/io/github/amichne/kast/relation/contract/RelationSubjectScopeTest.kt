package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A relation subject is admitted from authored source, whatever destination scope found it. */
class RelationSubjectScopeTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19L).refined(),
        )
    private val file =
        CanonicalWorkspaceFilePath.fromCanonicalPath(lease.workspaceRoot, Path.of("/workspace/impl/Impl.kt")).refined()

    @Test
    fun `an endpoint found under generated sources issues a selector that excludes them`() {
        val destination =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.Workspace(file),
                    0,
                    10,
                    "execute",
                    "impl.Impl.execute",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("impl.Impl.execute", null, emptyList(), emptyList(), 0)
                        .refined(),
                )
                .refined()
        val endpoint = RelationEndpoint.resolve(lease, destination, evidence).refined()
        // The endpoint keeps the destination scope, so the request that found it still admits it as a fact endpoint.
        assertEquals(destination, endpoint.scope)

        val issued = endpoint.selector()
        assertEquals(destination.copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE), issued.scope)
        assertEquals(endpoint.lease, issued.lease)
        assertEquals(endpoint.constraints, issued.constraints)
        assertEquals(evidence, CompilerGroundedSymbolEvidence.fromSelector(issued))
    }

    @Test
    fun `the subject scope changes only the generated source policy`() {
        val exactFile =
            SymbolSearchScope.ExactFile(
                file,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            )
        val workspace =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.TEST_ONLY,
                SymbolGeneratedSourcePolicy.INCLUDE,
                SymbolLibraryPolicy.INCLUDE,
            )
        assertEquals(exactFile.copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE), exactFile.subjectScope())
        assertEquals(workspace.copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE), workspace.subjectScope())
        // An authored scope is already a subject scope.
        val authored = workspace.copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE)
        assertEquals(authored, authored.subjectScope())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
