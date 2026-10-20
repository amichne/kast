package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalParameterDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument

/** These source facts precede capture and never use emitted transfers as expected values. */
internal data class NativePacketCompositeErrorReturnFacts(
    val common: NativePacketCompositeQueryFacts,
    val error: NativePacketCompositeQueryErrorFacts,
    val errorConstructor: ImpactDeclarationReferenceDocument,
    val callbackOwner: ImpactExecutionOwnerDocument,
    val callbackParameter: ImpactFormalParameterDocument,
    val transformInvoke: ImpactSourceRangeDocument,
    val transformParameter: ImpactSourceRangeDocument,
)

internal suspend fun NativePacketCompositeQueryCase.errorReturnFacts(): NativePacketCompositeErrorReturnFacts {
    val common = successFacts()
    val literal = source.mapErrorBody()
    val host = source.declaration(CLIENT, "OffersClientImpl", "readOffers")
    val owner = source.anonymousOwner(host, literal)
    val parameter =
        ReadAction.compute<ImpactFormalParameterDocument, RuntimeException> {
            val actual = literal.valueParameters.single()
            check(actual.name == "error")
            ImpactFormalParameterDocument(
                callable = common.clientMethod,
                index = packetQueryOffset(0),
                name = packetQueryText("error"),
                range =
                    ImpactSourceRangeDocument(
                        packetQueryOffset(actual.textRange.startOffset),
                        packetQueryOffset(actual.textRange.endOffset),
                    ),
                executionOwner = owner,
            )
        }
    return NativePacketCompositeErrorReturnFacts(
        common = common,
        error = clientErrorFacts(),
        errorConstructor = source.reference(source.declaration(OUTCOME, "Error", "<init>")),
        callbackOwner = owner,
        callbackParameter = parameter,
        transformInvoke =
            source.invocation(path = OUTCOME, owner = "Outcome", method = "mapError", callee = "transform"),
        transformParameter = source.parameterRange(path = OUTCOME, owner = "Outcome", method = "mapError", index = 0),
    )
}

private const val CLIENT = NativePacketCompositeQuerySource.CLIENT
private const val OUTCOME = NativePacketCompositeQuerySource.OUTCOME
