package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification as DomainQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest as DomainRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal sealed interface HostedDiscoveryRequestAdmission {
    data class Admitted(val request: DomainRequest) : HostedDiscoveryRequestAdmission
    data object Rejected : HostedDiscoveryRequestAdmission
}

/**
 * Proof transition: `(SemanticReadLease, SymbolDiscoverRequest) ->
 * HostedDiscoveryRequestAdmission`.
 *
 * Establishes one bounded name, location, or text query over the exact current
 * workspace and Gradle-owned source roots. Name queries may include readable project libraries;
 * source/location modes cannot widen beyond their workspace or exact-file scope. Every failed
 * primitive refinement remains closed. Raw protocol values leave only at their owning refinements.
 */
internal fun admitHostedDiscoveryRequest(
    lease: SemanticReadLease,
    request: SymbolDiscoverRequest,
): HostedDiscoveryRequestAdmission {
    val meaning = request.target.hostedMeaning(lease)
        ?: return HostedDiscoveryRequestAdmission.Rejected
    val results = refined(ResultLimit.parse(request.limit.value))
        ?: return HostedDiscoveryRequestAdmission.Rejected
    val work = refined(WorkUnitLimit.parse(HOSTED_DISCOVERY_WORK_LIMIT))
        ?: return HostedDiscoveryRequestAdmission.Rejected
    val elapsed = refined(ElapsedTimeLimitMillis.parse(HOSTED_DISCOVERY_TIME_MILLIS))
        ?: return HostedDiscoveryRequestAdmission.Rejected
    val bytes = refined(SymbolDiscoveryByteLimit.parse(HOSTED_DISCOVERY_RETURNED_BYTES))
        ?: return HostedDiscoveryRequestAdmission.Rejected
    return HostedDiscoveryRequestAdmission.Admitted(
        DomainRequest(
            SymbolSearchScopeRequest(
                lease,
                meaning.scope,
            ),
            meaning.target,
            SymbolDiscoveryBudget(ResourceBudget(results, work, elapsed), bytes),
        ),
    )
}

private data class HostedDiscoveryMeaning(
    val scope: SymbolSearchScope,
    val target: SymbolDiscoveryTarget,
)

private fun SymbolDiscoverTargetDocument.hostedMeaning(
    lease: SemanticReadLease,
): HostedDiscoveryMeaning? = when (this) {
    is SymbolDiscoverTargetDocument.Name -> HostedDiscoveryMeaning(
        workspaceScope(SymbolLibraryPolicy.INCLUDE),
        SymbolDiscoveryTarget.Name(
            when (kind) {
                SymbolNameKindDocument.FILE -> SymbolNameDiscoveryKind.FILE
                SymbolNameKindDocument.CLASS -> SymbolNameDiscoveryKind.CLASS
                SymbolNameKindDocument.SYMBOL -> SymbolNameDiscoveryKind.SYMBOL
            },
            refined(SymbolDiscoveryPattern.parse(query.value)) ?: return null,
            when (match) {
                SymbolDiscoveryMatchDocument.FUZZY -> SymbolDiscoveryMatch.FUZZY
                SymbolDiscoveryMatchDocument.EXACT_NAME -> SymbolDiscoveryMatch.EXACT_NAME
            },
        ),
    )
    is SymbolDiscoverTargetDocument.Location -> {
        val exactFile = hostedFile(lease, file.value) ?: return null
        HostedDiscoveryMeaning(
            exactFileScope(exactFile),
            SymbolDiscoveryTarget.Location(
                exactFile,
                refined(SymbolDiscoverySourceOffset.parse(offset.value)) ?: return null,
            ),
        )
    }
    is SymbolDiscoverTargetDocument.Text -> {
        val target = SymbolDiscoveryTarget.Text(
            refined(SymbolDiscoveryPattern.parse(query.value)) ?: return null,
        )
        when (val textScope = scope) {
            SymbolTextScopeDocument.Workspace -> HostedDiscoveryMeaning(
                workspaceScope(SymbolLibraryPolicy.EXCLUDE),
                target,
            )
            is SymbolTextScopeDocument.File -> {
                val exactFile = hostedFile(lease, textScope.file.value) ?: return null
                HostedDiscoveryMeaning(exactFileScope(exactFile), target)
            }
        }
    }
}

private fun workspaceScope(libraries: SymbolLibraryPolicy): SymbolSearchScope.Workspace =
    SymbolSearchScope.Workspace(
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
        libraries,
    )

private fun exactFileScope(file: CanonicalWorkspaceFilePath): SymbolSearchScope.ExactFile =
    SymbolSearchScope.ExactFile(
        file,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
    )

private fun hostedFile(
    lease: SemanticReadLease,
    raw: String,
): CanonicalWorkspaceFilePath? {
    val relative = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return null
    }
    if (relative.isAbsolute) return null
    val root = Path.of(lease.workspaceRoot.value)
    return refined(
        CanonicalWorkspaceFilePath.fromCanonicalPath(
            lease.workspaceRoot,
            root.resolve(relative).normalize(),
        ),
    )
}

internal fun SymbolCompilerRejection.hostedRejection(): SymbolDiscoverRejection = when (this) {
    SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
        SymbolDiscoverRejection.WORKSPACE_NOT_READY
    SymbolCompilerRejection.SCOPE_REJECTED,
    SymbolCompilerRejection.PROVIDER_UNAVAILABLE,
    SymbolCompilerRejection.INTERNAL_INVARIANT,
        -> SymbolDiscoverRejection.QUERY_REJECTED
}

/**
 * Proof transition: `(SymbolDiscoveryOutcome, HostedSelectorAuthority) -> closed protocol outcome`.
 *
 * Preserves the complete or qualified domain outcome, issues candidate authority for every
 * source-located variant, and admits every detached document before it leaves native composition.
 */
internal fun SymbolDiscoveryOutcome.hostedOutcome(
    selectors: HostedSelectorAuthority,
): OperationOutcome<SymbolDiscoverResult, SymbolDiscoverQualification, SymbolDiscoverRejection> {
    val batch = when (this) {
        is SymbolDiscoveryOutcome.Complete -> batch
        is SymbolDiscoveryOutcome.Qualified -> batch
    }
    val documents = batch.candidates.mapIndexed { ordinal, candidate ->
        val token = when (val issued = selectors.issueCandidate(batch, ordinal)) {
            is HostedCandidateIssuance.Issued -> issued.token
            HostedCandidateIssuance.Rejected -> return rejectedDiscovery()
        }
        candidate.hostedDocument(token) ?: return rejectedDiscovery()
    }
    val bounded = refined(BoundedProtocolList.create(documents)) ?: return rejectedDiscovery()
    val evidence = EvidenceEnvelope(
        CanonicalOperation.SYMBOL_DISCOVER.id,
        batch.lease.generation,
        SymbolDiscoverResult(bounded),
    )
    return when (this) {
        is SymbolDiscoveryOutcome.Complete -> OperationOutcome.Complete(evidence)
        is SymbolDiscoveryOutcome.Qualified -> {
            val qualification = refined(
                SymbolDiscoverQualification.from(
                    qualifications.values.map(DomainQualification::hostedLimitation).toSet(),
                ),
            ) ?: return rejectedDiscovery()
            OperationOutcome.Qualified(evidence, qualification)
        }
    }
}

private fun SymbolDiscoveryCandidate.hostedDocument(
    candidateSelector: ProtocolText,
): SymbolDiscoveryDocument? {
    val admittedName = refined(ProtocolText.parse(name.value)) ?: return null
    val admittedFile = refined(ProtocolText.parse(location.file.stableValue)) ?: return null
    return when (val candidateLocation = location) {
        is SymbolDiscoveryCandidateLocation.File ->
            SymbolDiscoveryDocument.File(candidateSelector, admittedName, admittedFile)
        is SymbolDiscoveryCandidateLocation.Declaration -> SymbolDiscoveryDocument.Declaration(
            candidateSelector,
            kind.hostedKind() ?: return null,
            admittedName,
            admittedFile,
            refined(ProtocolOffset.parse(candidateLocation.offset.value)) ?: return null,
        )
        is SymbolDiscoveryCandidateLocation.Text -> SymbolDiscoveryDocument.TextMatch(
            candidateSelector,
            admittedName,
            admittedFile,
            SourceRangeDocument.create(
                refined(ProtocolOffset.parse(candidateLocation.range.startInclusive.value))
                    ?: return null,
                refined(ProtocolOffset.parse(candidateLocation.range.endExclusive.value))
                    ?: return null,
            ).let(::refined) ?: return null,
        )
    }
}

private fun SymbolDiscoveryKind.hostedKind(): SymbolDiscoveryKindDocument? = when (this) {
    SymbolDiscoveryKind.FILE -> SymbolDiscoveryKindDocument.FILE
    SymbolDiscoveryKind.CLASS -> SymbolDiscoveryKindDocument.CLASS
    SymbolDiscoveryKind.SYMBOL -> SymbolDiscoveryKindDocument.SYMBOL
    SymbolDiscoveryKind.TEXT -> null
}

private fun DomainQualification.hostedLimitation(): SymbolDiscoverLimitation = when (this) {
    DomainQualification.RESULT_LIMIT_REACHED -> SymbolDiscoverLimitation.RESULT_LIMIT
    DomainQualification.BYTE_LIMIT_REACHED -> SymbolDiscoverLimitation.BYTE_LIMIT
    DomainQualification.WORK_LIMIT_REACHED -> SymbolDiscoverLimitation.WORK_LIMIT
    DomainQualification.TIME_LIMIT_REACHED -> SymbolDiscoverLimitation.TIME_LIMIT
    DomainQualification.DUMB_MODE_TRANSITION -> SymbolDiscoverLimitation.DUMB_MODE_TRANSITION
    DomainQualification.PROVIDER_FAILURE -> SymbolDiscoverLimitation.PROVIDER_FAILURE
    DomainQualification.UNSCOPED_PROVIDER -> SymbolDiscoverLimitation.UNSCOPED_PROVIDER
    DomainQualification.UNSUPPORTED_ITEM -> SymbolDiscoverLimitation.UNSUPPORTED_ITEM
    DomainQualification.EXACT_DEFINITION_UNAVAILABLE ->
        SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE
}

internal fun rejectedDiscovery(
    reason: SymbolDiscoverRejection = SymbolDiscoverRejection.QUERY_REJECTED,
): OperationOutcome<SymbolDiscoverResult, SymbolDiscoverQualification, SymbolDiscoverRejection> =
    OperationOutcome.Rejected(reason)

private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value? = when (value) {
    is Refinement.Refined -> value.value
    is Refinement.Rejected -> null
}

private const val HOSTED_DISCOVERY_WORK_LIMIT = 100_000L
private const val HOSTED_DISCOVERY_TIME_MILLIS = 30_000L
private const val HOSTED_DISCOVERY_RETURNED_BYTES = 1_048_576L
