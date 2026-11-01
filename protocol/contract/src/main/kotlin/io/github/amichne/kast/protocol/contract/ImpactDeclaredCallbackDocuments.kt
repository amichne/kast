@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactDeclaredCallbackResultEffectDocument {
    RETURNS_RESULT_OF,
    UNSPECIFIED,
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactDeclaredCallbackReceiverDocument {
    @Serializable @SerialName("ABSENT") data object Absent : ImpactDeclaredCallbackReceiverDocument

    @Serializable
    @SerialName("PRESENT")
    data class Present(val compilerType: ProtocolText) : ImpactDeclaredCallbackReceiverDocument
}

/** Structured compiler signature of the exact selected binary declaration. */
@Serializable
data class ImpactDeclaredCallbackTargetDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    val qualifiedIdentity: ProtocolText,
    val receiver: ImpactDeclaredCallbackReceiverDocument,
    val contextReceivers: BoundedProtocolList<ProtocolText>,
    val valueParameters: BoundedProtocolList<ProtocolText>,
    val typeParameterCount: QueryDiscoveryCountDocument,
)

/** The occurrence belongs to source; no range inside the dependency is invented. */
@Serializable
data class ImpactCallbackDependencyContractDocument(
    val basis: ImpactSemanticBasisDocument,
    val occurrenceFile: ProtocolText,
    val occurrenceRange: ImpactSourceRangeDocument,
    val owner: ImpactCapturedBodyDocument,
    val target: ImpactDeclaredCallbackTargetDocument,
    val position: ProtocolOffset,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val classDigest: ProtocolText,
    val provenance: QueryCallbackDependencyContractProvenanceDocument,
    val invocationKind: QueryCallbackDependencyInvocationKindDocument,
)

@Serializable
data class ImpactDeclaredCallbackValueBindingDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val body: ImpactCapturedBodyDocument,
    val contract: ImpactCallbackDependencyContractDocument,
    val resultEffect: ImpactDeclaredCallbackResultEffectDocument,
)
