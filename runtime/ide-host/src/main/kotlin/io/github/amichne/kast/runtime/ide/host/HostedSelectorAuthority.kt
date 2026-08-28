package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReader
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Closed endpoint-scoped exact-token issuance owned by the hosted-effects runtime. */
internal sealed interface HostedExactIssuance {
    data class Issued(val token: ProtocolText) : HostedExactIssuance
    data object Rejected : HostedExactIssuance
}

/** Closed exact-token lookup with topology absence distinct from stale token authority. */
internal sealed interface HostedExactLookup {
    data class Found(val selector: SymbolSelector) : HostedExactLookup
    data object Missing : HostedExactLookup
    data object TopologyUnavailable : HostedExactLookup
}

/** Exact selector proof available only to the thin hosted topology and mutation handlers. */
internal interface HostedExactSelectorOperations {
    fun issueExact(selector: SymbolSelector): HostedExactIssuance

    suspend fun exact(token: ProtocolText): HostedExactLookup
}

internal sealed interface HostedSymbolDescription {
    data class Described(
        val evidence: EvidenceEnvelope<SymbolDescribeResult>,
    ) : HostedSymbolDescription

    data object Rejected : HostedSymbolDescription
}

internal fun interface HostedSymbolDescriptionOperations {
    suspend fun describe(token: ProtocolText): HostedSymbolDescription
}

/**
 * Endpoint-scoped selector owner for hosted effects.
 *
 * Read-issued opaque tokens cross into effect authority only after the unchanged read runtime
 * describes them and an eligible, digest-checked topology snapshot supplies the matching detached
 * compiler identity. Effect-issued traversal tokens remain private capabilities of this endpoint.
 */
internal class HostedSelectorAuthority(
    private val descriptions: HostedSymbolDescriptionOperations,
    private val workspace: HostedWorkspaceOperations,
    private val snapshotReader: TopologySnapshotReader,
    private val contentReader: TopologySnapshotContentReader,
) : HostedExactSelectorOperations {
    private var sequence: HostedSelectorSequence = HostedSelectorSequence.Available(1)
    private val exact = linkedMapOf<ProtocolText, SymbolSelector>()

    override suspend fun exact(token: ProtocolText): HostedExactLookup {
        val current = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> return HostedExactLookup.Missing
        }
        val retained = retained(token)
        if (retained != null) {
            return if (retained.lease == current.readLease) {
                HostedExactLookup.Found(retained)
            } else {
                HostedExactLookup.Missing
            }
        }
        val described = when (val result = descriptions.describe(token)) {
            is HostedSymbolDescription.Described -> result.evidence
            HostedSymbolDescription.Rejected -> return HostedExactLookup.Missing
        }
        if (
            described.generation != current.readLease.generation ||
            described.payload.symbol.selector != token
        ) {
            return HostedExactLookup.Missing
        }
        val snapshot = when (
            val eligibility = snapshotReader.eligible(TopologyWorkspaceIdentity.from(current))
        ) {
            is TopologySnapshotEligibility.Eligible -> eligibility.snapshot
            TopologySnapshotEligibility.Unavailable,
            is TopologySnapshotEligibility.Stale,
            is TopologySnapshotEligibility.Rejected,
            -> return HostedExactLookup.TopologyUnavailable
        }
        val content = when (val read = contentReader.read(snapshot)) {
            is TopologySnapshotContentRead.Loaded -> read.content
            is TopologySnapshotContentRead.Rejected -> return HostedExactLookup.TopologyUnavailable
        }
        val symbol = content.symbols.singleOrNull { candidate ->
            described.payload.symbol.matches(candidate)
        } ?: return HostedExactLookup.Missing
        val selector = SymbolSelector.issue(current.readLease, HOSTED_READ_SCOPE, symbol.evidence)
        retain(token, selector)
        return HostedExactLookup.Found(selector)
    }

    @Synchronized
    override fun issueExact(selector: SymbolSelector): HostedExactIssuance {
        val current = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
            ?: return HostedExactIssuance.Rejected
        if (selector.lease != current.readLease) return HostedExactIssuance.Rejected
        val token = nextToken(selector.lease.generation.value)
            ?: return HostedExactIssuance.Rejected
        exact[token] = selector
        return HostedExactIssuance.Issued(token)
    }

    @Synchronized
    private fun retained(token: ProtocolText): SymbolSelector? = exact[token]

    @Synchronized
    private fun retain(token: ProtocolText, selector: SymbolSelector) {
        exact[token] = selector
    }

    private fun nextToken(generation: Long): ProtocolText? {
        val current = sequence as? HostedSelectorSequence.Available ?: return null
        sequence = if (current.next == Long.MAX_VALUE) {
            HostedSelectorSequence.Exhausted
        } else {
            HostedSelectorSequence.Available(current.next + 1)
        }
        return when (val parsed = ProtocolText.parse(
            "hosted-exact:v1:$generation:${current.next}",
        )) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> null
        }
    }

    companion object {
        fun from(
            reads: HostedReadRuntimeOperations,
            workspace: HostedWorkspaceOperations,
            snapshotReader: TopologySnapshotReader,
            contentReader: TopologySnapshotContentReader,
        ): HostedSelectorAuthority = HostedSelectorAuthority(
            HostedReadSymbolDescriptionOperations(reads),
            workspace,
            snapshotReader,
            contentReader,
        )
    }
}

private sealed interface HostedSelectorSequence {
    data class Available(val next: Long) : HostedSelectorSequence
    data object Exhausted : HostedSelectorSequence
}

private class HostedReadSymbolDescriptionOperations(
    private val reads: HostedReadRuntimeOperations,
) : HostedSymbolDescriptionOperations {
    override suspend fun describe(token: ProtocolText): HostedSymbolDescription {
        val request = when (val encoded = CanonicalOperationWireBindings.symbolDescribe.encodeRequest(
            SymbolDescribeRequest(token),
        )) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return HostedSymbolDescription.Rejected
        }
        val response = when (val dispatch = reads.dispatch(request)) {
            is IdeReadRuntimeDispatchResult.Responded -> dispatch.document
            is IdeReadRuntimeDispatchResult.Rejected -> return HostedSymbolDescription.Rejected
        }
        return when (val decoded = CanonicalOperationWireBindings.symbolDescribe.decodeOutcome(
            response,
        )) {
            is WireDecoding.Decoded -> when (val outcome = decoded.value) {
                is OperationOutcome.Complete -> HostedSymbolDescription.Described(outcome.evidence)
                is OperationOutcome.Qualified,
                is OperationOutcome.Rejected,
                -> HostedSymbolDescription.Rejected
            }
            is WireDecoding.Rejected -> HostedSymbolDescription.Rejected
        }
    }
}

private fun SymbolDocument.matches(symbol: TopologySymbol): Boolean =
    file.value == symbol.evidence.file.stableValue &&
        range.startInclusive.value == symbol.evidence.range.startInclusive &&
        range.endExclusive.value == symbol.evidence.range.endExclusive &&
        name.value == symbol.evidence.name.value &&
        kind.matches(symbol.evidence.kind) &&
        qualifiedIdentity.matches(symbol.evidence)

private fun SymbolKindDocument.matches(kind: CompilerSymbolKind): Boolean = when (this) {
    SymbolKindDocument.CLASSLIKE -> kind == CompilerSymbolKind.CLASSLIKE
    SymbolKindDocument.CONSTRUCTOR -> kind == CompilerSymbolKind.CONSTRUCTOR
    SymbolKindDocument.FUNCTION -> kind == CompilerSymbolKind.FUNCTION
    SymbolKindDocument.PROPERTY -> kind == CompilerSymbolKind.PROPERTY
    SymbolKindDocument.TYPE_ALIAS -> kind == CompilerSymbolKind.TYPE_ALIAS
}

private fun SymbolQualifiedIdentityDocument.matches(
    evidence: CompilerGroundedSymbolEvidence,
): Boolean = when (this) {
    is SymbolQualifiedIdentityDocument.Available ->
        (evidence.qualifiedIdentity as? ExactDeclarationQualifiedIdentity.Available)?.value ==
            value.value
    SymbolQualifiedIdentityDocument.Unavailable ->
        evidence.qualifiedIdentity == ExactDeclarationQualifiedIdentity.Unavailable
}

private val HOSTED_READ_SCOPE = SymbolSearchScope.Workspace(
    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
    SymbolGeneratedSourcePolicy.INCLUDE,
    SymbolLibraryPolicy.INCLUDE,
)
