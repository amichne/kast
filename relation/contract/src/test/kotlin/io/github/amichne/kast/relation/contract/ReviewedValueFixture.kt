package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Explicit compiler facts constrain correspondence. Native fixtures own source and dependency admission. */
internal class ReviewedValueFixture(generation: Long = 1) {
    val enclosing = flowEndpoint("convert", 356, 459, generation = generation) as RelationEndpoint.Resolved
    val supplier = RelationCallableBody.Named.fromCompiler(enclosing.evidence).flowRefined()
    val body = body(434, 459)
    val occurrence = occurrence(424, 459)
    val field =
        ValueField.fromCompiler(
                flowEndpoint(
                    "ClientEnvelope.payload",
                    151,
                    171,
                    generation = generation,
                    propertyType = "kast/repro/ClientValue",
                )
            )
            .flowRefined()
    val fieldPath = flowPath(ValuePathSegment.Field(field))
    val selected = ReviewedLibraryCallable.admit(target(), position(0)).flowRefined()
    val artifact = ReviewedLibraryArtifact.admit(digest(BINARY_HASH), digest(SOURCE_HASH)).flowRefined()
    val binding = binding().flowRefined()

    fun binding(
        body: RelationCallableBody.Anonymous = this.body,
        occurrence: RelationOccurrence = this.occurrence,
        supplier: RelationCallableBody.Named = this.supplier,
        selected: ReviewedLibraryCallable = this.selected,
    ) =
        ReviewedCallbackValueBinding.admit(
            enclosing = enclosing,
            body = body,
            occurrence = occurrence,
            supplier = supplier,
            selected = selected,
            artifact = artifact,
        )

    fun source(
        role: ValueRole = ValueRole.Return,
        path: ValueAccessPath = fieldPath,
        owner: ValueExecutionOwner = ValueExecutionOwner.reviewedCallback(binding),
    ): ValueSite =
        ValueSite.fromCompiler(
                enclosing = enclosing,
                range = flowRange(436, 457),
                role = role,
                executionOwner = owner,
                valuePath = path,
            )
            .flowRefined()

    fun transfer(): ReviewedValueTransfer = ReviewedValueTransfer.callbackResult(source(), binding).flowRefined()

    fun body(
        start: Int,
        end: Int,
        parameterType: String = "kast/repro/WireValue",
    ): RelationCallableBody.Anonymous {
        val range = flowRange(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(enclosing.file, range),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf(parameterType),
                    rawTypeParameterCount = 0,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(enclosing.file, range, signature).flowRefined()
    }

    fun occurrence(start: Int, end: Int): RelationOccurrence =
        RelationOccurrence.fromBoundary(enclosing.file, start, end).flowRefined()

    fun target(
        qualified: String = "kotlin.collections.map",
        receiver: String? = "kotlin/collections/Iterable<T>",
        parameters: List<String> = listOf("(T)->R"),
        typeParameters: Int = 2,
        contextReceivers: List<String> = emptyList(),
        file: SymbolDiscoveryFileIdentity =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///library.jar!/kotlin/collections/CollectionsKt.class").flowRefined()
            ),
    ): CompilerGroundedSymbolEvidence {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = qualified,
                    rawReceiverType = receiver,
                    rawContextReceiverTypes = contextReceivers,
                    rawValueParameterTypes = parameters,
                    rawTypeParameterCount = typeParameters,
                )
                .flowRefined()
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file = file,
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = qualified.substringAfterLast('.'),
                rawQualifiedIdentity = qualified,
                kind = CompilerSymbolKind.FUNCTION,
                signature = signature,
            )
            .flowRefined()
    }

    fun domain(results: Int = 100, bytes: Long = 1_048_576): RelationRequest =
        RelationRequest.start(
            subject = enclosing,
            meaning = RelationMeaning.References,
            budget =
                RelationBudget(
                    ResourceBudget(
                        ResultLimit.parse(results).flowRefined(),
                        WorkUnitLimit.parse(4096).flowRefined(),
                        ElapsedTimeLimitMillis.parse(1000).flowRefined(),
                    ),
                    RelationByteLimit.parse(bytes).flowRefined(),
                ),
            boundary = RelationSearchBoundary.RETAINED_SUBJECT,
        )

    fun position(index: Int): ValueArgumentPosition = ValueArgumentPosition.parse(index).flowRefined()

    fun digest(raw: String): DependencyArtifactDigest = DependencyArtifactDigest.parse(raw).flowRefined()

    companion object {
        const val BINARY_HASH = "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"
        const val SOURCE_HASH = "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
    }
}

internal fun <V, F> Refinement<V, F>.reviewedFailure(): F =
    when (this) {
        is Refinement.Refined -> error("Expected rejection")
        is Refinement.Rejected -> failure
    }
