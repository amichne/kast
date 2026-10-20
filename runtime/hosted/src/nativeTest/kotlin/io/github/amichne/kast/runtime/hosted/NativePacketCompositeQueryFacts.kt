package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument

/** The source adapter must resolve these facts before the value-flow capture starts. */
internal data class NativePacketCompositeQueryFacts(
    val executeReceiver: ImpactValueSiteReferenceDocument,
    val successType: ImpactDeclarationReferenceDocument,
    val errorType: ImpactDeclarationReferenceDocument,
    val mapImplicitBody: ImpactSourceRangeDocument,
    val transformInvoke: ImpactSourceRangeDocument,
    val transformArgument: ImpactSourceRangeDocument,
    val mapInvocation: ImpactInvocationReferenceDocument,
    val mapErrorInvocation: ImpactInvocationReferenceDocument,
    val mapper: ImpactDeclarationReferenceDocument,
    val envelopeBody: ImpactDeclarationReferenceDocument,
    val wireLinks: ImpactDeclarationReferenceDocument,
    val clientLinks: ImpactDeclarationReferenceDocument,
    val successValue: ImpactDeclarationReferenceDocument,
    val clientMethod: ImpactDeclarationReferenceDocument,
    val clientReturn: ImpactSourceRangeDocument,
    val callerFrame: NativePacketQueryFrameOracle,
    val sequentialBody: ImpactSourceRangeDocument,
    val responseBinding: ImpactSourceRangeDocument,
    val component1: ImpactDeclarationReferenceDocument,
    val bind: ImpactDeclarationReferenceDocument,
    val firstOrNullResult: ImpactSourceRangeDocument,
    val featureConstructor: ImpactDeclarationReferenceDocument,
    val featureLink: ImpactDeclarationReferenceDocument,
    val featureResult: ImpactSourceRangeDocument,
    val getOfferReturn: ImpactValueSiteReferenceDocument,
    val apiReadOffers: ImpactDeclarationReferenceDocument,
    val wireConstructor: ImpactDeclarationReferenceDocument,
    val headerBuild: ImpactDeclarationReferenceDocument,
)

internal data class CompositeRequiredRow(val id: String, val passed: Boolean)
