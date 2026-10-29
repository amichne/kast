package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactCapturedBodyDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedFunctionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedSourceLinesDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument

internal fun reviewedTransferSchemaFixture(): ImpactReviewedValueTransferDocument {
    val enclosing =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value()),
            text("/workspace/File.kt"),
            range(0, 100),
            text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )
    val binding =
        ImpactReviewedValueBindingDocument(
            ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1,
            enclosing.basis,
            enclosing,
            ImpactCapturedBodyDocument(
                enclosing.file,
                range(40, 80),
                text("canonical-signature-sha256-v1|" + "c".repeat(64)),
            ),
            range(30, 90),
            selected(enclosing),
            artifacts(),
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
            ImpactActualExecutionDocument.NOT_ESTABLISHED,
            ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED,
        )
    val field = ImpactValuePathSegmentDocument.Field(enclosing.copy(range = range(5, 15)))
    val source =
        ImpactValueSiteReferenceDocument(
            enclosing,
            range(50, 60),
            ImpactValueRoleDocument.Return,
            ImpactExecutionOwnerDocument.ReviewedCallback(binding),
            bounded(listOf(field)),
        )
    val target =
        ImpactValueSiteReferenceDocument(
            enclosing,
            binding.supplierRange,
            ImpactValueRoleDocument.ExpressionResult,
            valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Element, field)),
        )
    return ImpactReviewedValueTransferDocument(
        source,
        target,
        ImpactReviewedValueRelationshipDocument.fromBinding(binding, binding.semantics),
    )
}

private fun selected(enclosing: ImpactDeclarationReferenceDocument): ImpactReviewedFunctionDocument {
    val signature =
        CompilerSignatureDocument.Function(
            text("kotlin.collections.map"),
            CompilerReceiverDocument.Present(text("kotlin/collections/Iterable<T>")),
            bounded(emptyList()),
            bounded(listOf(text("(T)->R"))),
            CompilerTypeParameterCountDocument.parse(2).value(),
        )
    return ImpactReviewedFunctionDocument(
        enclosing.copy(
            file = text("jar:///library/kotlin-stdlib-2.4.21.jar!/kotlin/collections/CollectionsKt.class"),
            compilerIdentity = CompilerSymbolEvidenceDocument.fromSignature(signature).value().identity,
        ),
        signature.qualifiedIdentity,
        text("kotlin/collections/Iterable<T>"),
        signature.contextReceivers,
        signature.valueParameters,
        QueryDiscoveryCountDocument.parse(2).value(),
        offset(0),
    )
}

private fun artifacts() =
    ImpactReviewedMapArtifactsDocument(
        binaryArtifactSha256 = text("bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"),
        sourceArtifactSha256 = text("1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"),
        sourceEntrySha256 = text("aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c"),
        facadeSha256 = text("7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878"),
        implementationSha256 = text("e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296"),
        mapLines = ImpactReviewedSourceLinesDocument(offset(1744), offset(1746)),
        mapToLines = ImpactReviewedSourceLinesDocument(offset(1819), offset(1823)),
    )

private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(offset(start), offset(end))

private fun offset(value: Int) = ProtocolOffset.parse(value).value()

private fun text(value: String) = ProtocolText.parse(value).value()

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
