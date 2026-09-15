package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolInspectAcquisition
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecodingFailure
import io.github.amichne.kast.query.protocol.CanonicalSymbolInspectProtocol
import io.github.amichne.kast.query.protocol.ExactSelectorIssuance
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilerPort
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.ExactRevalidationResult
import io.github.amichne.kast.symbol.contract.ExactRevalidationTextIdentity
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.service.ExactRevalidationService
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class HostedExactRevalidationTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
    private val owner = MovingLiveReadAuthorityFixture(root)
    private val original = owner.admit()
    private val fixture = RelationPagingFixture(original)
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "main",
                        Path.of("/workspace"),
                        ":",
                        "main",
                        Path.of("/workspace"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    private val locator =
        ExactRevalidationLocator.capture(
                fixture.selector,
                model.sourceRoots.single(),
                ExactRevalidationTextIdentity.parse("a".repeat(64)).value(),
            )
            .value()

    @Test
    fun `explicit reacquisition after unrelated movement leaves strict old reference stale`() = runBlocking {
        val strict = HostedReferenceStore()
        val records = ExactRevalidationRecords()
        val first = references(strict, original)
        val token = (first.issueExact(fixture.selector) as ExactSelectorIssuance.Issued).selector
        records.retain(token, fixture.exact, locator).value()
        val current = owner.advance()
        val references = references(strict, current)
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (references.restoreExact(token, current) as CanonicalSelectorDecoding.Rejected).failure,
        )
        var compilations = 0
        val service =
            ExactRevalidationService(
                SemanticReadValidationPort { SemanticReadValidation.CURRENT },
                ExactRevalidationCompilerPort { retained, admitted ->
                    assertSame(current, admitted)
                    assertSame(locator, retained)
                    compilations++
                    ExactRevalidationCompilation.Confirmed(
                        CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
                    )
                },
            )
        val exact =
            object : SymbolExactOperations {
                override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                    error("No rediscovery")

                override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                    error("No old description")
            }
        val protocol = CanonicalSymbolInspectProtocol(exact, references, records, service)
        assertEquals(
            OperationOutcome.Rejected(SymbolInspectRejection.EXACT_SELECTOR_STALE),
            protocol.execute(SymbolInspectRequest(SymbolInspectTarget.Exact(token)), current),
        )
        val result =
            protocol.execute(SymbolInspectRequest(SymbolInspectTarget.RevalidateExact(token)), current)
                as OperationOutcome.Complete
        assertEquals(SymbolInspectAcquisition.REACQUIRED, result.evidence.payload.acquisition)
        assertEquals(1, compilations)
        val reacquired = result.evidence.payload.symbol.selector
        assertTrue(references.restoreExact(reacquired, current) is CanonicalSelectorDecoding.Decoded)
        assertTrue(references.restoreExact(token, current) is CanonicalSelectorDecoding.Rejected)
    }

    @Test
    fun `finite compiler failures and owner mismatch never issue authority`() = runBlocking {
        val current = owner.advance()
        for (failure in ExactRevalidationRejection.entries) {
            val service =
                ExactRevalidationService(
                    SemanticReadValidationPort { SemanticReadValidation.CURRENT },
                    ExactRevalidationCompilerPort { _, _ -> ExactRevalidationCompilation.Rejected(failure) },
                )
            assertEquals(ExactRevalidationResult.Rejected(failure), service.revalidate(locator, current))
        }
        var called = false
        val service =
            ExactRevalidationService(
                SemanticReadValidationPort { SemanticReadValidation.CURRENT },
                ExactRevalidationCompilerPort { _, _ ->
                    called = true
                    error("Foreign owner admitted")
                },
            )
        val foreign = MovingLiveReadAuthorityFixture(root).admit()
        assertEquals(
            ExactRevalidationResult.Rejected(ExactRevalidationRejection.OWNER_MISMATCH),
            service.revalidate(locator, foreign),
        )
        assertFalse(called)
    }

    @Test
    fun `movement after fresh compiler work prevents publication`() = runBlocking {
        val current = owner.advance()
        var validations = 0
        val service =
            ExactRevalidationService(
                SemanticReadValidationPort {
                    if (validations++ == 0) SemanticReadValidation.CURRENT else SemanticReadValidation.MOVED
                },
                ExactRevalidationCompilerPort { _, _ -> ExactRevalidationCompilation.Confirmed(locator.evidence) },
            )
        assertEquals(
            ExactRevalidationResult.Rejected(ExactRevalidationRejection.BASIS_MOVED),
            service.revalidate(locator, current),
        )
    }

    @Test
    fun `changed overload evidence is rejected and cancellation propagates`() = runBlocking {
        val current = owner.advance()
        val previous = locator.evidence
        val changed =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    previous.file,
                    previous.range.startInclusive,
                    previous.range.endExclusive,
                    previous.name.value,
                    "sample.subject",
                    previous.kind,
                    CanonicalCompilerSignature.function("sample.subject", null, emptyList(), listOf("kotlin.String"), 0)
                        .value(),
                )
                .value()
        val changedService =
            ExactRevalidationService(
                SemanticReadValidationPort { SemanticReadValidation.CURRENT },
                ExactRevalidationCompilerPort { _, _ -> ExactRevalidationCompilation.Confirmed(changed) },
            )
        assertEquals(
            ExactRevalidationResult.Rejected(ExactRevalidationRejection.COMPILER_IDENTITY_CHANGED),
            changedService.revalidate(locator, current),
        )
        val cancelled = java.util.concurrent.CancellationException("test cancellation")
        val service =
            ExactRevalidationService(
                SemanticReadValidationPort { SemanticReadValidation.CURRENT },
                ExactRevalidationCompilerPort { _, _ -> throw cancelled },
            )
        try {
            service.revalidate(locator, current)
            fail<Unit>("Cancellation was swallowed")
        } catch (actual: java.util.concurrent.CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test
    fun `retention bounds expiry replay and disposal are deterministic`() {
        var tick = 0L
        val records = ExactRevalidationRecords({ tick }, maxEntries = 1, maxAgeNanos = 10)
        records.retain(fixture.exact, fixture.exact, locator).value()
        tick = 9
        records.locate(fixture.exact).value()
        records.retain(fixture.exact, fixture.exact, locator).value()
        tick = 10
        assertEquals(Refinement.Rejected(ExactRevalidationRejection.EXPIRED), records.locate(fixture.exact))
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.EXPIRED),
            records.retain(fixture.exact, fixture.exact, locator),
        )
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CAPACITY),
            records.retain(text("exact:other"), fixture.exact, locator),
        )
        assertEquals(Refinement.Rejected(ExactRevalidationRejection.WRONG_KIND), records.locate(text("candidate:x")))
        assertEquals(Refinement.Rejected(ExactRevalidationRejection.UNRETAINED), records.locate(text("exact:unknown")))
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CAPACITY),
            ExactRevalidationRecords(maxBytes = 1).retain(fixture.exact, fixture.exact, locator),
        )
        records.dispose()
        assertEquals(Refinement.Rejected(ExactRevalidationRejection.RETIRED), records.locate(fixture.exact))
    }

    private fun references(store: HostedReferenceStore, current: LiveSemanticReadAuthority) =
        CanonicalQueryReferences(
            model,
            store.transport(
                current.reference,
                ReadLimits.Default,
                io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None,
            ),
        )

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture: $failure")
        }
}
