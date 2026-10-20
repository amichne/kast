package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalParameterDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument

/** Exact original source and issued symbols determine every expected request field and position before capture. */
internal data class NativePacketS1QueryFacts(
    val common: NativePacketCompositeQueryFacts,
    val origin: ImpactValueSiteReferenceDocument,
    val requestArgument: ImpactSourceRangeDocument,
    val requestFormal: ImpactFormalParameterDocument,
    val mapper: ImpactDeclarationReferenceDocument,
    val mapperInvocation: ImpactInvocationReferenceDocument,
    val mapperReturn: ImpactSourceRangeDocument,
    val destination: ImpactDeclarationReferenceDocument,
    val token: ImpactDeclarationReferenceDocument,
    val wireDestination: ImpactDeclarationReferenceDocument,
    val wireChannel: ImpactDeclarationReferenceDocument,
    val apiBody: ImpactValueSiteReferenceDocument,
    val loggerInfo: ImpactDeclarationReferenceDocument,
    val loggerWarn: ImpactDeclarationReferenceDocument,
)

internal suspend fun NativePacketCompositeQueryCase.s1Facts(): NativePacketS1QueryFacts {
    val common = successFacts()
    val mapper = source.reference(source.declaration(CLIENT, "", "toWireRequest"))
    return NativePacketS1QueryFacts(
        common = common,
        origin = source.s1Origin(common),
        requestArgument = source.s1RequestArgument(),
        requestFormal = source.s1RequestFormal(common),
        mapper = mapper,
        mapperInvocation = ImpactInvocationReferenceDocument(source.clientCall("toWireRequest"), mapper),
        mapperReturn = source.returnRange(CLIENT, "", "toWireRequest"),
        destination = source.field(CLIENT, "ClientRequest", "destination"),
        token = source.field(CLIENT, "ClientRequest", "sessionToken"),
        wireDestination = source.field(WIRE, "WireRequest", "destination"),
        wireChannel = source.field(WIRE, "WireRequest", "channel"),
        apiBody = source.s1ApiBody(common),
        loggerInfo = source.reference(source.declaration(WIRE, "Logger", "info")),
        loggerWarn = source.reference(source.declaration(WIRE, "Logger", "warn")),
    )
}

private fun NativePacketCompositeQuerySource.s1Origin(common: NativePacketCompositeQueryFacts) =
    ImpactValueSiteReferenceDocument(
        enclosing = common.getOfferReturn.enclosing,
        range =
            invocation(path = FEATURE, owner = "OffersProcessorImpl", method = "getOffer", callee = "ClientRequest"),
        role = ImpactValueRoleDocument.ExpressionResult,
        executionOwner = common.callerFrame.invocation.executionOwner,
    )

private fun NativePacketCompositeQuerySource.s1RequestArgument() =
    argumentRange(path = FEATURE, owner = "OffersProcessorImpl", method = "getOffer", callee = "readOffers", index = 0)

private fun NativePacketCompositeQuerySource.s1RequestFormal(common: NativePacketCompositeQueryFacts) =
    ImpactFormalParameterDocument(
        callable = common.clientMethod,
        index = packetQueryOffset(0),
        name = packetQueryText("request"),
        range = parameterRange(path = CLIENT, owner = "OffersClientImpl", method = "readOffers", index = 0),
    )

private fun NativePacketCompositeQuerySource.s1ApiBody(common: NativePacketCompositeQueryFacts) =
    ImpactValueSiteReferenceDocument(
        enclosing = common.clientMethod,
        range =
            argumentRange(
                path = CLIENT,
                owner = "OffersClientImpl",
                method = "readOffers",
                callee = "readOffers",
                index = 1,
            ),
        role =
            ImpactValueRoleDocument.Argument(
                ImpactInvocationReferenceDocument(clientCall("readOffers"), common.apiReadOffers),
                packetQueryOffset(1),
            ),
    )

private fun NativePacketCompositeQuerySource.clientCall(callee: String) =
    invocation(path = CLIENT, owner = "OffersClientImpl", method = "readOffers", callee = callee)

private const val CLIENT = NativePacketCompositeQuerySource.CLIENT
private const val WIRE = NativePacketCompositeQuerySource.WIRE
private const val FEATURE = NativePacketCompositeQuerySource.FEATURE
