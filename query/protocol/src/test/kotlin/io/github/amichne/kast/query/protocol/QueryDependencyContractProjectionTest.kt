package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** The real projection must never request an authored source handle for a binary target. */
internal class QueryDependencyContractProjectionTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `dependency target projection preserves evidence when external source handles are forbidden`() {
        val external = externalEvidence()
        val source = observation(true).callbackObservations.single()
        val previous = (source.flow as CallbackInvocationFlowRead.Observed).flow
        val binding =
            CallbackDependencyContract.fromCompiler(
                    fixture.authority.identity,
                    occurrence(0, 5),
                    RelationCallableBody.Named.fromCompiler(source.lexicalOwner).refined(),
                    external,
                    ValueArgumentPosition.parse(0).refined(),
                    DependencyClassDigest.parse("a".repeat(64)).refined(),
                    CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    CallbackDependencyInvocationKind.EXACTLY_ONCE,
                )
                .refined()
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    fixture.authority.identity,
                    previous.body,
                    CallbackBindingEvidence.DependencyContract(binding),
                    emptyList(),
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
        val forbidden =
            object : QueryReferenceAuthority by fixture.references {
                override fun issueRangeCandidate(
                    lease: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
                    file: SymbolDiscoveryFileIdentity,
                    rawStartInclusive: Int,
                    rawEndExclusive: Int,
                ): CandidateSelectorTokenIssuance {
                    check(file !is SymbolDiscoveryFileIdentity.External) {
                        "External declaration cannot receive an authored source handle"
                    }
                    return fixture.references.issueRangeCandidate(lease, file, rawStartInclusive, rawEndExclusive)
                }
            }
        val projection =
            CallbackProjection(forbidden, fixture.authority).flow(CallbackInvocationFlowRead.Observed(flow))
        assertNotNull(projection)
        val projected =
            (projection as QueryCallbackFlowDocument.Observed).binding
                as QueryCallbackBindingDocument.DependencyContract
        assertEquals("jar:///lib.jar!/sample/Wrapper.class", projected.target.file.value)
        assertEquals(external.compilerIdentity.value, projected.target.compilerEvidence.identity.value)
        assertEquals("a".repeat(64), projected.classDigest.value)
    }

    private fun externalEvidence(): CompilerGroundedSymbolEvidence {
        val externalFile =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///lib.jar!/sample/Wrapper.class").refined()
            )
        val old = wrapperEvidence()
        return CompilerGroundedSymbolEvidence.fromBoundary(
                externalFile,
                100,
                200,
                "wrapper",
                "sample.wrapper",
                old.kind,
                old.signature,
            )
            .refined()
    }
}
