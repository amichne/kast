package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ImpactReviewedValueFixture {
    private val base = ImpactFindingFixture()
    val binding = binding()
    private val field = ImpactValuePathSegmentDocument.Field(base.declaration.copy(range = range(5, 15)))
    val source =
        ImpactValueSiteReferenceDocument(
            base.declaration,
            range(50, 60),
            ImpactValueRoleDocument.Return,
            ImpactExecutionOwnerDocument.ReviewedCallback(binding),
            base.bounded(listOf(field)),
        )
    val target =
        ImpactValueSiteReferenceDocument(
            base.declaration,
            binding.supplierRange,
            ImpactValueRoleDocument.ExpressionResult,
            valuePath = base.bounded(listOf(ImpactValuePathSegmentDocument.Element, field)),
        )
    val transfer =
        ImpactReviewedValueTransferDocument(
            source,
            target,
            ImpactReviewedValueRelationshipDocument.fromBinding(
                binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
            ),
        )

    fun text(raw: String) = base.text(raw)

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(base.offset(start), base.offset(end))

    fun <T> bounded(values: List<T>) = base.bounded(values)

    private fun binding(): ImpactReviewedValueBindingDocument =
        ImpactReviewedValueBindingDocument(
            rule = ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
            basis = base.declaration.basis,
            enclosing = base.declaration,
            body =
                ImpactCapturedBodyDocument(
                    base.declaration.file,
                    range(40, 80),
                    text("canonical-signature-sha256-v1|" + "c".repeat(64)),
                ),
            supplierRange = range(30, 90),
            selected = selected(),
            artifacts = artifacts(),
            actualExecution = ImpactActualExecutionDocument.NOT_ESTABLISHED,
            invocationCount = ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED,
        )

    private fun selected(): ImpactReviewedFunctionDocument {
        val signature =
            CompilerSignatureDocument.Function(
                text("kotlin.collections.map"),
                CompilerReceiverDocument.Present(text("kotlin/collections/Iterable<T>")),
                bounded(emptyList()),
                bounded(listOf(text("(T)->R"))),
                CompilerTypeParameterCountDocument.parse(2).refined(),
            )
        val identity = CompilerSymbolEvidenceDocument.fromSignature(signature).refined().identity
        return ImpactReviewedFunctionDocument(
            base.declaration.copy(
                file = text("jar:///library/kotlin-stdlib-2.4.21.jar!/kotlin/collections/CollectionsKt.class"),
                compilerIdentity = identity,
            ),
            signature.qualifiedIdentity,
            text("kotlin/collections/Iterable<T>"),
            signature.contextReceivers,
            signature.valueParameters,
            base.count(2),
            base.offset(0),
        )
    }

    private fun artifacts() =
        ImpactReviewedCollectionArtifactsDocument(
            binaryArtifactSha256 = text("bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"),
            sourceArtifactSha256 = text("1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"),
            sourceEntrySha256 = text("aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c"),
            facadeSha256 = text("7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878"),
            implementationSha256 = text("e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296"),
            sections =
                ImpactReviewedCollectionSectionsDocument.Map(
                    ImpactReviewedSourceLinesDocument(base.offset(1744), base.offset(1746)),
                    ImpactReviewedSourceLinesDocument(base.offset(1819), base.offset(1823)),
                ),
            helpers = bounded(emptyList()),
        )
}

private fun <T, F> Refinement<T, F>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid reviewed fixture: $failure")
    }

internal fun ImpactReviewedValueFixture.collectionCapacity(): ImpactReviewedLibraryHelperDocument =
    ImpactReviewedLibraryHelperDocument(
        ImpactReviewedLibraryHelperKindDocument.COLLECTION_CAPACITY,
        text("commonMain/kotlin/collections/Iterables.kt"),
        text("db36d783a35dc062366af184d7e54cbc70f57e3cf570329696931c7008c1e244"),
        bounded(
            listOf(
                ImpactReviewedSourceLinesDocument(
                    (ProtocolOffset.parse(39) as Refinement.Refined).value,
                    (ProtocolOffset.parse(39) as Refinement.Refined).value,
                )
            )
        ),
        bounded(
            listOf(
                ImpactReviewedClassEvidenceDocument(
                    text("kotlin/collections/CollectionsKt__IterablesKt.class"),
                    text("813642e022be0e7327b93fb9eb92ed8c1500059b4b22747525267e9f6bae1154"),
                )
            )
        ),
    )
