package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilerPort
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationPolicy
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.ExactRevalidationResult
import io.github.amichne.kast.symbol.contract.ExactRevalidationTextIdentity
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure service/content-admission rules; this is not compiler or saved-file adapter evidence. */
class LocalExactRevalidationTest {
    @Test
    fun `current declaration policy cannot authorize a changed local owning document`() {
        val fixture = Fixture()
        assertEquals(
            Refinement.Refined(Unit),
            fixture.locator.admitContent(
                fixture.sourceRoot,
                digest("old document"),
                ExactRevalidationPolicy.CURRENT_DECLARATION,
            ),
        )
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CONTENT_CHANGED),
            fixture.locator.admitContent(
                fixture.sourceRoot,
                digest("new document"),
                ExactRevalidationPolicy.CURRENT_DECLARATION,
            ),
        )
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CONTENT_CHANGED),
            fixture.locator.admitContent(
                fixture.sourceRoot,
                digest("changed outside the local range"),
                ExactRevalidationPolicy.CURRENT_DECLARATION,
            ),
        )
    }

    @Test
    fun `service preserves content rejection and admits unchanged local identity in current authority`() {
        val fixture = Fixture()
        val current = fixture.owner.advance()
        val script =
            ArrayDeque(
                listOf(
                    ExactRevalidationCompilation.Rejected(ExactRevalidationRejection.CONTENT_CHANGED),
                    ExactRevalidationCompilation.Confirmed(fixture.evidence),
                )
            )
        var compilerCalls = 0
        var validations = 0
        val service =
            ExactRevalidationService(
                SemanticReadValidationPort { authority ->
                    assertSame(current, authority)
                    validations++
                    SemanticReadValidation.CURRENT
                },
                ExactRevalidationCompilerPort { locator, authority ->
                    assertSame(fixture.locator, locator)
                    assertSame(current, authority)
                    compilerCalls++
                    assertTrue(script.isNotEmpty(), "Unexpected compiler confirmation")
                    script.removeFirst()
                },
                ExactRevalidationPolicy.CURRENT_DECLARATION,
            )
        assertEquals(
            ExactRevalidationResult.Rejected(ExactRevalidationRejection.CONTENT_CHANGED),
            runSuspend { service.revalidate(fixture.locator, current) },
        )
        assertEquals(1, validations)
        val result = runSuspend { service.revalidate(fixture.locator, current) } as ExactRevalidationResult.Reacquired
        assertSame(current, result.selector.lease)
        assertEquals(fixture.evidence.signature, result.selector.signature)
        assertEquals(fixture.evidence.compilerIdentity, result.selector.compilerIdentity)
        assertEquals(fixture.evidence.range, result.selector.range)
        assertEquals(2, compilerCalls)
        assertEquals(3, validations)
        assertTrue(script.isEmpty(), "Unconsumed compiler confirmation")
    }

    private class Fixture {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
        val owner = MovingLiveReadAuthorityFixture(root)
        val authority = owner.admit()
        val sourceRoot =
            (WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            "main",
                            Path.of("/workspace"),
                            ":",
                            "main",
                            Path.of("/workspace/src"),
                            WorkspaceSourceRootKind.PRODUCTION,
                            WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                ) as WorkspaceSearchScopeModelCompilation.Compiled)
                .model
                .sourceRoots
                .single()
        val file =
            SymbolDiscoveryFileIdentity.fromBoundary(
                    root,
                    Path.of("/workspace/src/Local.kt"),
                    "file:///workspace/src/Local.kt",
                )
                .value()
        val address =
            LocalDeclarationAddress.create(
                    file,
                    LocalDeclarationKind.PROPERTY,
                    ExactDeclarationTextRange.parse(20, 30).value(),
                    CompilerSymbolIdentity.fromCanonicalSignature(
                        CanonicalCompilerSignature.function("sample.owner", null, emptyList(), emptyList(), 0).value()
                    ),
                    ExactDeclarationTextRange.parse(0, 100).value(),
                    emptyList(),
                )
                .value()
        val signature =
            CanonicalCompilerSignature.localProperty(address, "kotlin.Int", LocalPropertyMutability.VAL).value()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    20,
                    30,
                    "local",
                    null,
                    CompilerSymbolKind.PROPERTY,
                    signature,
                )
                .value()
        val selector =
            SymbolSelector.issue(
                authority,
                SymbolSearchScope.ExactFile(
                    (file as SymbolDiscoveryFileIdentity.Workspace).path,
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                ),
                evidence,
            )
        val locator = ExactRevalidationLocator.capture(selector, sourceRoot, digest("old document")).value()
    }

    companion object {
        private fun digest(text: String) =
            ExactRevalidationTextIdentity.parse(
                    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
                        "%02x".format(it)
                    }
                )
                .value()

        private fun <V, F> Refinement<V, F>.value(): V =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Rejected fixture: $failure")
            }

        private fun <V> runSuspend(block: suspend () -> V): V {
            var result: Result<V>? = null
            block.startCoroutine(
                object : Continuation<V> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(outcome: Result<V>) {
                        result = outcome
                    }
                }
            )
            return checkNotNull(result).getOrThrow()
        }
    }
}
