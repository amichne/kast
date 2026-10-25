package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedIterableMapArtifact
import io.github.amichne.kast.relation.contract.ReviewedIterableMapCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueConstructorStorage
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Explicit detached native facts exercise the real query interpreter without compiler or host effects. */
internal class ReviewedImpactExecutionFixture(generation: Long = 1) {
    val base = QueryImpactExecutionFixture(generation)
    private val scope =
        SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.TEST_ONLY,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    private val file =
        SymbolDiscoveryFileIdentity.Workspace(
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                    base.lease.workspaceRoot,
                    Path.of("/fixture/src/test/kotlin/Repro.kt"),
                )
                .reviewedValue()
        )
    val domain =
        RelationSearchBoundary.Explicit(
            scope = scope,
            directory =
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("src/test/kotlin").reviewedValue(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
            sourceSets =
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("test").reviewedValue()))
                    .reviewedValue(),
        )
    private val owner = callable("convert", 0, 200, emptyList(), CompilerSymbolKind.FUNCTION)
    private val envelope =
        callable("ClientEnvelope.<init>", 210, 300, listOf("fixture.ClientValue"), CompilerSymbolKind.CONSTRUCTOR)
    private val clientValue =
        callable("ClientValue.<init>", 310, 350, listOf("kotlin.String?"), CompilerSymbolKind.CONSTRUCTOR)
    val field =
        ValueField.fromCompiler(
                RelationEndpoint.resolve(
                        lease = base.lease,
                        scope = scope,
                        evidence =
                            CompilerGroundedSymbolEvidence.fromBoundary(
                                    file = file,
                                    rawStartInclusive = 230,
                                    rawEndExclusive = 250,
                                    rawName = "payload",
                                    rawQualifiedIdentity = "fixture.ClientEnvelope.payload",
                                    kind = CompilerSymbolKind.PROPERTY,
                                    signature =
                                        CanonicalCompilerSignature.property(
                                                "fixture.ClientEnvelope.payload",
                                                null,
                                                emptyList(),
                                                "fixture.ClientValue",
                                            )
                                            .reviewedValue(),
                                )
                                .reviewedValue(),
                    )
                    .reviewedValue()
            )
            .reviewedValue()
    private val binding = binding()
    private val execution = ValueExecutionOwner.reviewedCallback(binding)
    private val seedCall = ValueInvocation.fromCompiler(owner, range(50, 60), clientValue, execution).reviewedValue()
    val producer = QueryImpactProducer.admit(seedCall.resultSite(), seedCall).reviewedValue()
    private val envelopeCall = ValueInvocation.fromCompiler(owner, range(40, 100), envelope, execution).reviewedValue()
    private val position = ValueArgumentPosition.parse(0).reviewedValue()
    private val argument =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = producer.site.range,
                role = ValueRole.Argument(envelopeCall, position),
                executionOwner = execution,
            )
            .reviewedValue()
    private val fieldPath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).reviewedValue()
    private val envelopeResult = envelopeCall.resultSite().withState(valuePath = fieldPath).reviewedValue()
    private val callbackResult =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = envelopeResult.range,
                role = ValueRole.Return,
                executionOwner = execution,
                valuePath = fieldPath,
            )
            .reviewedValue()
    val reviewed = ReviewedValueTransfer.callbackResult(callbackResult, binding).reviewedValue()
    val output =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = reviewed.target.range,
                role = ValueRole.Return,
                valuePath = reviewed.target.valuePath,
            )
            .reviewedValue()
    private val storage =
        ValueConstructorStorage.fromCompiler(
                ValueFormalParameter.fromCompiler(envelope, position, field.declaration.name, field.declaration.range)
                    .reviewedValue(),
                field,
            )
            .reviewedValue()
    val compiler =
        listOf(
            ValueTransfer.fromCompiler(producer.site, argument, ValueTransferKind.ARGUMENT).reviewedValue(),
            ValueTransfer.fromCompiler(
                    source = argument,
                    target = envelopeResult,
                    kind = ValueTransferKind.CONSTRUCTOR_FIELD,
                    evidence =
                        ValueSourceTransfer.fromCompiler(ValueSourceOperation.ConstructField(envelopeCall, storage)),
                )
                .reviewedValue(),
            ValueTransfer.fromCompiler(envelopeResult, callbackResult, ValueTransferKind.RETURN).reviewedValue(),
            ValueTransfer.fromCompiler(reviewed.target, output, ValueTransferKind.RETURN).reviewedValue(),
        )
    val plan = base.plan(domain = domain, producers = listOf(producer))
    val request =
        QueryExecutionRequest.create(
                plan = plan,
                lease = base.lease,
                budget =
                    QueryBudget(
                        resources = ResourceBudget.RunnerControlled,
                        returnedBytes = QueryByteLimit.parse(1_048_576).reviewedValue(),
                        checkpointBytes = QueryByteLimit.DefaultCheckpoint,
                        pageResults = ResultLimit.parse(100).reviewedValue(),
                    ),
            )
            .reviewedValue()

    fun script(): ReviewedImpactScript =
        ReviewedImpactScript(
            listOf(
                ReviewedImpactRead(producer.site, listOf(compiler[0])),
                ReviewedImpactRead(argument, listOf(compiler[1])),
                ReviewedImpactRead(envelopeResult, listOf(compiler[2])),
                ReviewedImpactRead(callbackResult, reviewed = listOf(reviewed)),
                ReviewedImpactRead(reviewed.target, listOf(compiler[3])),
                ReviewedImpactRead(output),
            ),
            domain,
        )

    private fun callable(name: String, start: Int, end: Int, parameters: List<String>, kind: CompilerSymbolKind) =
        RelationEndpoint.resolve(
                lease = base.lease,
                scope = scope,
                evidence =
                    CompilerGroundedSymbolEvidence.fromBoundary(
                            file = file,
                            rawStartInclusive = start,
                            rawEndExclusive = end,
                            rawName = name.substringAfterLast('.'),
                            rawQualifiedIdentity = "fixture.$name",
                            kind = kind,
                            signature =
                                CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), parameters, 0)
                                    .reviewedValue(),
                        )
                        .reviewedValue(),
            )
            .reviewedValue()

    private fun binding(): ReviewedCallbackValueBinding {
        val bodyRange = range(30, 120)
        val body =
            RelationCallableBody.Anonymous.fromCompiler(
                    file = file,
                    range = bodyRange,
                    signature =
                        CanonicalCompilerSignature.function(
                                rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(file, bodyRange),
                                rawReceiverType = null,
                                rawContextReceiverTypes = emptyList(),
                                rawValueParameterTypes = listOf("fixture.WireValue"),
                                rawTypeParameterCount = 0,
                            )
                            .reviewedValue() as CanonicalCompilerSignature.Function,
                )
                .reviewedValue()
        val target = reviewedMapTarget()
        return ReviewedCallbackValueBinding.admit(
                enclosing = owner,
                body = body,
                occurrence = RelationOccurrence.fromBoundary(file, 20, 140).reviewedValue(),
                supplier = RelationCallableBody.Named.fromCompiler(owner.evidence).reviewedValue(),
                selected =
                    ReviewedIterableMapCallable.admit(target, ValueArgumentPosition.parse(0).reviewedValue())
                        .reviewedValue(),
                artifact =
                    ReviewedIterableMapArtifact.admit(
                            DependencyArtifactDigest.parse(
                                    "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"
                                )
                                .reviewedValue(),
                            DependencyArtifactDigest.parse(
                                    "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
                                )
                                .reviewedValue(),
                        )
                        .reviewedValue(),
            )
            .reviewedValue()
    }

    private fun reviewedMapTarget(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file =
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/collections/CollectionsKt.class")
                            .reviewedValue()
                    ),
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = "map",
                rawQualifiedIdentity = "kotlin.collections.map",
                kind = CompilerSymbolKind.FUNCTION,
                signature =
                    CanonicalCompilerSignature.function(
                            "kotlin.collections.map",
                            "kotlin/collections/Iterable<T>",
                            emptyList(),
                            listOf("(T)->R"),
                            2,
                        )
                        .reviewedValue(),
            )
            .reviewedValue()
}

internal data class ReviewedImpactRead(
    val source: ValueSite,
    val compiler: List<ValueTransfer> = emptyList(),
    val reviewed: List<ReviewedValueTransfer> = emptyList(),
)

internal class ReviewedImpactScript(script: List<ReviewedImpactRead>, private val domain: RelationSearchBoundary) {
    private val expected = ArrayDeque(script)
    private var unexpectedReads = 0
    val examined = mutableListOf<ValueSite>()
    val grants = mutableListOf<RelationBudget>()
    val port = ValueFlowCompilerPort { request ->
        if (expected.isEmpty() || expected.first().source != request.source) {
            unexpectedReads++
            error("Unexpected native read in reviewed fixture")
        }
        val read = expected.removeFirst()
        assertEquals(domain, request.boundary)
        examined += request.source
        grants += request.budget
        ValueFlowRead.Observed(
            ValueFlowStep.fromEvidence(
                    source = read.source,
                    transfers = read.compiler,
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain =
                        RelationRequest.start(
                            read.source.enclosing,
                            RelationMeaning.References,
                            request.budget,
                            request.boundary,
                        ),
                    examinedWorkUnits = RelationWorkCount.parse(1).reviewedValue(),
                    reviewedTransfers = read.reviewed,
                )
                .reviewedValue()
        )
    }

    fun assertConsumed() {
        assertEquals(0, unexpectedReads)
        assertTrue(expected.isEmpty(), "Unconsumed native facts")
    }
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).reviewedValue()

private fun <V, F> Refinement<V, F>.reviewedValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
    }
