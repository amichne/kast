@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RelationOccurrenceWireDocument(
    val candidateSelector: String,
    val file: String,
    val range: SourceRangeWireDocument,
)

@Serializable
data class CompilerSymbolEvidenceWireDocument(
    val identity: String,
    val signature: CompilerSignatureWireDocument,
)

@Serializable
sealed interface CompilerSignatureWireDocument {
    @Serializable
    @SerialName("function")
    data class Function(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverWireDocument,
        val contextReceivers: List<String>,
        val valueParameters: List<String>,
        val typeParameterCount: Int,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("property")
    data class Property(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverWireDocument,
        val contextReceivers: List<String>,
        val returnType: String,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("LOCAL_FUNCTION")
    data class LocalFunction(
        val address: LocalDeclarationAddressWireDocument,
        val receiver: CompilerReceiverWireDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val contextReceivers: List<String>,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val valueParameters: List<String>,
        @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
        val typeParameterCount: Int,
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(minimumLength = 1, maximumLength = 1048576)
        val returnType: String,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("LOCAL_PROPERTY")
    data class LocalProperty(
        val address: LocalDeclarationAddressWireDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(minimumLength = 1, maximumLength = 1048576)
        val returnType: String,
        val mutability: LocalPropertyMutabilityWireDocument,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("ANONYMOUS_OBJECT")
    data class AnonymousObject(
        val address: LocalDeclarationAddressWireDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val supertypes: List<String>,
    ) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("type-alias")
    data class TypeAlias(val qualifiedIdentity: String) : CompilerSignatureWireDocument

    @Serializable
    @SerialName("class-like")
    data class ClassLike(val qualifiedIdentity: String) : CompilerSignatureWireDocument
}

@Serializable
data class LocalDeclarationAddressWireDocument(
    val file: LocalDeclarationFileWireDocument,
    val kind: LocalDeclarationKindWireDocument,
    val range: SourceRangeWireDocument,
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = 4096,
        pattern = "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$",
    )
    val ownerIdentity: String,
    val ownerRange: SourceRangeWireDocument,
    @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 32)
    val lexicalOwners: List<SourceRangeWireDocument>,
)

@Serializable
sealed interface LocalDeclarationFileWireDocument {
    @Serializable @SerialName("WORKSPACE") data class Workspace(val path: String) : LocalDeclarationFileWireDocument

    @Serializable @SerialName("EXTERNAL") data class External(val url: String) : LocalDeclarationFileWireDocument
}

@Serializable
enum class LocalDeclarationKindWireDocument {
    FUNCTION,
    PROPERTY,
    ANONYMOUS_OBJECT,
}

@Serializable
enum class LocalPropertyMutabilityWireDocument {
    VAL,
    VAR,
}

@Serializable
sealed interface CompilerReceiverWireDocument {
    @Serializable @SerialName("absent") data object Absent : CompilerReceiverWireDocument

    @Serializable @SerialName("present") data class Present(val compilerType: String) : CompilerReceiverWireDocument
}

@Serializable
data class SourceRangeWireDocument(
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
    val startInclusive: Int,
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 1, maximum = 2147483647)
    val endExclusive: Int,
)

@Serializable
enum class SymbolKindWireDocument {
    @SerialName("classlike") CLASSLIKE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("function") FUNCTION,
    @SerialName("property") PROPERTY,
    @SerialName("type-alias") TYPE_ALIAS,
}
