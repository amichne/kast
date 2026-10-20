package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.query.protocol.ExactSelectorIssuance

/** These exact source facts complete before the original S2 value-flow capture. */
internal data class NativePacketCompositeQueryErrorFacts(
    val executeReceiver: ImpactValueSiteReferenceDocument,
    val errorType: ImpactDeclarationReferenceDocument,
    val successType: ImpactDeclarationReferenceDocument,
    val errorValue: ImpactDeclarationReferenceDocument,
    val wireCode: ImpactDeclarationReferenceDocument,
    val wireDebugMessage: ImpactDeclarationReferenceDocument,
    val clientCode: ImpactDeclarationReferenceDocument,
    val mapper: ImpactDeclarationReferenceDocument,
    val mapperInvocation: ImpactSourceRangeDocument,
    val constructor: ImpactDeclarationReferenceDocument,
    val mapperReturn: ImpactValueSiteReferenceDocument,
    val mapperDeclaration: QueryImpactDeclarationDocument,
)

internal suspend fun NativePacketCompositeQueryCase.clientErrorFacts(): NativePacketCompositeQueryErrorFacts {
    val common = successFacts()
    val mapper = source.declaration(CLIENT, "", "toClientError")
    val reference = source.reference(mapper)
    val issued = services.references.issueExact(mapper)
    check(issued is ExactSelectorIssuance.Issued) { "C2.4 exact mapper selector prerequisite: $issued" }
    return NativePacketCompositeQueryErrorFacts(
        executeReceiver = common.executeReceiver,
        errorType = common.errorType,
        successType = common.successType,
        errorValue = source.field(OUTCOME, "Error", "value"),
        wireCode = source.field(WIRE, "WireError", "code"),
        wireDebugMessage = source.field(WIRE, "WireError", "debugMessage"),
        clientCode = source.field(CLIENT, "ClientError", "code"),
        mapper = reference,
        mapperInvocation =
            source.invocation(
                path = CLIENT,
                owner = "OffersClientImpl",
                method = "readOffers",
                callee = "toClientError",
            ),
        constructor = source.reference(source.declaration(CLIENT, "ClientError", "<init>")),
        mapperReturn =
            ImpactValueSiteReferenceDocument(
                reference,
                source.returnRange(CLIENT, "", "toClientError"),
                ImpactValueRoleDocument.Return,
            ),
        mapperDeclaration = QueryImpactDeclarationDocument(issued.selector, reference),
    )
}

private const val CLIENT = NativePacketCompositeQuerySource.CLIENT
private const val WIRE = NativePacketCompositeQuerySource.WIRE
private const val OUTCOME = NativePacketCompositeQuerySource.OUTCOME
