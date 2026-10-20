package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** This exclusion concerns emitted input provenance. It does not establish a source-known-empty fact. */
internal class NativePacketNoOfferQueryOracle(
    private val common: NativePacketCompositeQueryFacts,
    private val reason: ImpactDeclarationReferenceDocument,
    private val constantConstructor: ImpactSourceRangeDocument,
) {
    fun assertNoOriginalS2InputIsStoredAsTheConstantNoOfferReason(paths: List<ImpactPathDocument>) {
        val producer = common.executeReceiver.copy(role = ImpactValueRoleDocument.ExpressionResult)
        assertTrue(
            "The forbidden-witness assessment needs admitted original-S2 evidence",
            paths.any { it.producer == producer },
        )
        val forbidden =
            paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .filter { step ->
                    val storage =
                        step.transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField
                            ?: return@filter false
                    step.transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
                        storage.invocation.range == constantConstructor &&
                        storage.storage.field == reason
                }
        assertTrue(
            "Original S2 input acquired the constant NO_OFFER field at ${forbidden.size} sites",
            forbidden.isEmpty(),
        )
        println(
            "NO_OFFER_QUERY emitted_input_to_constant=FORBIDDEN " +
                "empty_satisfiability=NOT_ASSERTED literal_return=NOT_ASSERTED"
        )
    }
}

internal suspend fun NativePacketCompositeQueryCase.assertNoS2InputAtTheNoOfferConstant() {
    val common = successFacts()
    val actual = source.noOfferBody()
    val constructor =
        ReadAction.compute<ImpactSourceRangeDocument, RuntimeException> {
            val call = actual.bodyExpression?.statements?.single() as KtCallExpression
            val literal = call.valueArguments.single().getArgumentExpression() as KtStringTemplateExpression
            check(literal.entries.single().text == "NO_OFFER")
            ImpactSourceRangeDocument(
                packetQueryOffset(call.textRange.startOffset),
                packetQueryOffset(call.textRange.endOffset),
            )
        }
    val reason = source.field(NativePacketCompositeQuerySource.FEATURE, "FeatureError", "reason")
    val paths =
        NativePacketQueryCapture(services)
            .paths(
                producer = source.producerS2(),
                targets = listOf(common.getOfferReturn),
                outgoing = false,
                declarations = source.requestedDeclarations(),
            )
    NativePacketNoOfferQueryOracle(common, reason, constructor)
        .assertNoOriginalS2InputIsStoredAsTheConstantNoOfferReason(paths)
}
