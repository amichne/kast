package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

private const val IMPLEMENTATION_OMISSION_SAMPLE_LIMIT = 3
private const val IMPLEMENTATION_FINGERPRINT_LENGTH = 64

internal fun ImpactCallFrameDocument.admitsTarget(basis: ImpactSemanticBasisDocument): Boolean =
    when (val proof = target) {
        ImpactCallTargetDocument.Direct ->
            (invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation?.callable == callee.enclosing &&
                callee.executionOwner == ImpactExecutionOwnerDocument.Declaration
        is ImpactCallTargetDocument.Implementation ->
            proof.dispatch.admitsImplementation(basis) &&
                proof.relation in proof.dispatch.facts.values &&
                proof.relation.target.declaration ==
                    (invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation?.callable &&
                proof.relation.source.declaration == callee.enclosing &&
                callee.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
                proof.ownership.declaration == callee.enclosing &&
                proof.ownership.admitsSourceOwnership(proof.dispatch.question.domain, basis)
    }

internal fun ImpactFlowObligationEvidenceDocument.admitsObligation(
    site: ImpactValueSiteReferenceDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    when (this) {
        ImpactFlowObligationEvidenceDocument.None -> true
        is ImpactFlowObligationEvidenceDocument.ImplementationDispatch ->
            dispatch.admitsImplementation(basis) &&
                when (val position = site.role) {
                    is ImpactValueRoleDocument.Argument -> position.invocation.callable == dispatch.question.subject
                    is ImpactValueRoleDocument.Receiver -> position.invocation.callable == dispatch.question.subject
                    ImpactValueRoleDocument.Return -> true
                    is ImpactValueRoleDocument.Formal,
                    ImpactValueRoleDocument.ExpressionResult,
                    ImpactValueRoleDocument.LocalBinding,
                    ImpactValueRoleDocument.LocalRead,
                    ImpactValueRoleDocument.PropertyAssignment -> false
                }
    }

internal fun ImpactImplementationDispatchDocument.admitsImplementation(basis: ImpactSemanticBasisDocument): Boolean =
    admitsQuestion(basis) &&
        facts.values.distinct().size == facts.values.size &&
        facts.values.all { it.admitsFact(this, basis) } &&
        resultCount.value == facts.values.size.toLong() &&
        facts.values.size + scopeExclusions.values.size <= question.budget.maxResults.value &&
        encodedBytes.value <= question.budget.maxReturnedBytes.value &&
        question.budget.permitsWork(examinedWorkUnits.value) &&
        coverage.admitsCoverage(this) &&
        omissions.values.admitOmissions(provider) &&
        scopeExclusions.values.all { it.admitsExclusion(this, basis) }

private fun ImpactImplementationDispatchDocument.admitsQuestion(basis: ImpactSemanticBasisDocument): Boolean =
    question.subject.basis == basis &&
        question.subject.validImplementationDeclaration() &&
        question.meaning == RelationKindDocument.IMPLEMENTATIONS &&
        subjectFingerprint.validFingerprint() &&
        provider in IMPLEMENTATION_PROVIDERS &&
        question.position.admitsImplementationPosition(provider)

private fun ImpactImplementationFactDocument.admitsFact(
    dispatch: ImpactImplementationDispatchDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    meaning == RelationKindDocument.IMPLEMENTATIONS &&
        authority == basis &&
        subject.declaration == dispatch.question.subject &&
        subject.fingerprint == dispatch.subjectFingerprint &&
        target == subject &&
        listOf(subject, source, target).all {
            it.declaration.basis == basis &&
                it.declaration.validImplementationDeclaration() &&
                it.fingerprint.validFingerprint()
        } &&
        occurrence.validImplementationOccurrence() &&
        coverage == RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED

private fun ImpactImplementationCoverageDocument.admitsCoverage(
    dispatch: ImpactImplementationDispatchDocument
): Boolean =
    when (this) {
        is ImpactImplementationCoverageDocument.Exhausted -> exactCount.value == dispatch.facts.values.size.toLong()
        is ImpactImplementationCoverageDocument.TerminalIncomplete ->
            knownMinimum.value == dispatch.facts.values.size.toLong() &&
                limitations.values.validImplementationLimitations()
        is ImpactImplementationCoverageDocument.Resumable ->
            knownMinimum.value == dispatch.facts.values.size.toLong() &&
                limitations.values.validImplementationLimitations() &&
                continuation.admitsImplementationPosition(dispatch.provider) &&
                continuation.position.value >
                    when (val current = dispatch.question.position) {
                        ImpactFlowReadPositionDocument.Start -> 0L
                        is ImpactFlowReadPositionDocument.Resume -> current.position.value
                    }
    }

private fun ImpactFlowReadPositionDocument.admitsImplementationPosition(provider: RelationProviderDocument): Boolean =
    when (this) {
        ImpactFlowReadPositionDocument.Start -> true
        is ImpactFlowReadPositionDocument.Resume ->
            fingerprint.value.validFingerprintText() &&
                consumedPrefix.value.validFingerprintText() &&
                this.provider == provider &&
                position.value > 0L &&
                limitations.values == limitations.values.distinct().sortedBy { it.ordinal }
    }

private fun List<RelationLimitationDocument>.validImplementationLimitations(): Boolean =
    isNotEmpty() && this == distinct().sortedBy { it.ordinal }

private fun List<ImpactImplementationOmissionDocument>.admitOmissions(provider: RelationProviderDocument): Boolean =
    map { it.reason } == map { it.reason }.distinct().sortedBy { it.ordinal } &&
        all { omission ->
            val minimum =
                omission.samples.values.size +
                    if (omission.sampleRetention == RelationOmissionSampleRetentionDocument.TRUNCATED) 1 else 0
            omission.provider == provider &&
                omission.samples.values.size <= IMPLEMENTATION_OMISSION_SAMPLE_LIMIT &&
                omission.samples.values.distinct().size == omission.samples.values.size &&
                omission.samples.values.all { it.validImplementationOccurrence() } &&
                when (val measurement = omission.measurement) {
                    ImpactImplementationOmissionMeasurementDocument.UnmeasuredOnPage -> true
                    is ImpactImplementationOmissionMeasurementDocument.ObservedOnPage ->
                        measurement.items.value >= minimum
                }
        }

private fun ImpactImplementationExclusionDocument.admitsExclusion(
    dispatch: ImpactImplementationDispatchDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    this.basis == basis &&
        subjectFingerprint == dispatch.subjectFingerprint &&
        requestedDomain == dispatch.question.requestedDomain &&
        effectiveDomain == dispatch.question.domain &&
        domainFingerprint == dispatch.question.fingerprint &&
        occurrence.validImplementationOccurrence() &&
        target.declaration.basis == basis &&
        target.declaration.validImplementationDeclaration() &&
        target.name.value.isNotBlank()

private fun ImpactSourceOwnershipDocument.admitsSourceOwnership(
    domain: QueryRelationDomainDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val sourceRoot = root.sourceRoot.value.replace('\\', '/').trimEnd('/')
    val workspace = basis.root.value.replace('\\', '/').trimEnd('/')
    if (sourceRoot != workspace && !sourceRoot.startsWith("$workspace/")) return false
    if (!declaration.file.value.replace('\\', '/').startsWith("$sourceRoot/")) return false
    if (root.module.value.isBlank() || root.sourceSet.value.isBlank() || !root.projectPath.value.startsWith(':'))
        return false
    val kind = root.admitsSourceKind(domain.sourcePolicy)
    val provenance =
        root.provenance == ImpactSourceProvenanceDocument.AUTHORED ||
            domain.generatedSources == QueryDiscoveryInclusionPolicyDocument.INCLUDE
    val owner = admitsScope(domain.scope)
    val sourceSet =
        when (val sets = domain.sourceSets) {
            QueryDiscoverySourceSetsDocument.All -> true
            is QueryDiscoverySourceSetsDocument.Exact -> root.sourceSet in sets.names.values
        }
    return kind && provenance && owner && sourceSet
}

private fun ImpactSourceRootDocument.admitsSourceKind(policy: QueryDiscoverySourcePolicyDocument): Boolean =
    when (policy) {
        QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY -> sourceKind == ImpactSourceKindDocument.PRODUCTION
        QueryDiscoverySourcePolicyDocument.TEST_ONLY -> sourceKind == ImpactSourceKindDocument.TEST
        QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST -> true
    }

private fun ImpactSourceOwnershipDocument.admitsScope(scope: QuerySemanticScopeDocument): Boolean =
    when (scope) {
        QuerySemanticScopeDocument.Workspace -> true
        is QuerySemanticScopeDocument.ExactFile -> declaration.file == scope.file
        is QuerySemanticScopeDocument.Module -> root.module == scope.module
        is QuerySemanticScopeDocument.GradleProject ->
            root.buildRoot == scope.buildRoot && root.projectPath == scope.projectPath
        is QuerySemanticScopeDocument.SourceSet ->
            root.buildRoot == scope.buildRoot &&
                root.projectPath == scope.projectPath &&
                root.sourceSet == scope.sourceSet
    }

private fun ImpactImplementationOccurrenceDocument.validImplementationOccurrence(): Boolean =
    file.value.isNotBlank() && range.end.value > range.start.value

private fun ImpactDeclarationReferenceDocument.validImplementationDeclaration(): Boolean =
    declarationFailure() is Refinement.Refined

private fun ProtocolText.validFingerprint(): Boolean = value.validFingerprintText()

private fun String.validFingerprintText(): Boolean =
    length == IMPLEMENTATION_FINGERPRINT_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private val IMPLEMENTATION_PROVIDERS =
    setOf(RelationProviderDocument.INTELLIJ_DEFINITIONS_V2, RelationProviderDocument.PUBLISHED_TOPOLOGY_V1)
