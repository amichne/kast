package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.RevalidatedSymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceReadPortTest {
    private val text = """/** documentation */
        |@Deprecated("sample")
        |public fun subject(): Int = 1
        |""".trimMargin()

    @Test
    fun `exact symbol capture returns complete declaration text and reusable source selector`() {
        val fixture = fixture()
        val port = IntellijSourceReadPort(
            IntellijSourceReadAccess { context, selector ->
                IntellijSourceReadAccessResult.Captured(
                    IntellijCommittedSourceCapture.create(
                        context,
                        fixture.revalidated,
                        text,
                    ).refined(),
                )
            },
        )

        val result = runSuspend { port.read(fixture.context, request(fixture.selector, 65_536)) }
        val complete = result as SourceReadResult.Complete
        val returned = complete.text as SourceTextProjection.Returned

        assertEquals(text, returned.text)
        assertEquals(complete.region.selector.fingerprint, returned.selector.fingerprint)
        assertEquals(fixture.selector.lease, complete.snapshot.lease)
        assertTrue(complete.entities.isEmpty())
    }

    @Test
    fun `byte bound qualifies without emitting a misleading source fragment`() {
        val fixture = fixture()
        val port = IntellijSourceReadPort(
            IntellijSourceReadAccess { context, _ ->
                IntellijSourceReadAccessResult.Captured(
                    IntellijCommittedSourceCapture.create(
                        context,
                        fixture.revalidated,
                        text,
                    ).refined(),
                )
            },
        )

        val result = runSuspend { port.read(fixture.context, request(fixture.selector, 8)) }
        val qualified = result as SourceReadResult.Qualified

        assertEquals(
            listOf(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED),
            qualified.qualification.limitations,
        )
        assertEquals(
            io.github.amichne.kast.source.contract.SourceTextWithheldReason.BYTE_LIMIT_REACHED,
            (qualified.text as SourceTextProjection.Withheld).reason,
        )
    }

    @Test
    fun `native anchor rejection remains closed public source read rejection`() {
        val fixture = fixture()
        val port = IntellijSourceReadPort(
            IntellijSourceReadAccess { _, _ ->
                IntellijSourceReadAccessResult.Rejected(
                    IntellijSourceReadRejection.DOCUMENT_DIRTY,
                )
            },
        )

        assertEquals(
            SourceReadResult.Rejected(
                io.github.amichne.kast.source.contract.SourceReadRejection.DOCUMENT_DIRTY,
            ),
            runSuspend { port.read(fixture.context, request(fixture.selector, 65_536)) },
        )
    }

    private fun request(selector: SymbolSelector, byteLimit: Long): SourceReadRequest =
        SourceReadRequest(
            SourceReadAnchor.Symbol(selector),
            RegionSelection.Anchor,
            EntitySelection.None,
            TextProjection.Complete,
            SourceEntityLimit.parse(250).refined(),
            SourceTextByteLimit.parse(byteLimit).refined(),
            SourceReadPage.First,
        )

    private fun fixture(): Fixture {
        val lease = SemanticReadLease(root(), EvidenceGeneration.parse(42).refined())
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "subject",
            lease,
            Path.of("/workspace/src/Subject.kt"),
            "file:///workspace/src/Subject.kt",
            0,
        ).refined()
        val discoveryRequest = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("subject").refined(),
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000).refined(),
            ),
        )
        val batch = SymbolDiscoveryBatch.create(
            discoveryRequest,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
            SymbolDiscoveryWorkCount.parse(1).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1).refined(),
            ),
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            candidate.location.file,
            0,
            text.length,
            "subject",
            "sample.subject",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                "sample.subject",
                null,
                emptyList(),
                emptyList(),
                0,
            ).refined(),
        ).refined()
        val selector = SymbolSelector.issue(selection, evidence).refined()
        return Fixture(
            selector,
            RevalidatedSymbolSelector.validate(selector, evidence).refined(),
            SourceReadContext(
                lease,
                WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            ),
        )
    }

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            },
        )
        return checkNotNull(completion).getOrThrow()
    }

    private data class Fixture(
        val selector: SymbolSelector,
        val revalidated: RevalidatedSymbolSelector,
        val context: SourceReadContext,
    )
}
