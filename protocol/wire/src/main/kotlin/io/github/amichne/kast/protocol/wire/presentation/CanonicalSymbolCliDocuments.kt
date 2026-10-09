@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationAddressDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationFileDocument
import io.github.amichne.kast.protocol.contract.LocalDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.LocalPropertyMutabilityDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SymbolCliDocument(
    val selector: String,
    val kind: String,
    val name: String,
    val qualifiedIdentity: String?,
    val file: String,
    val range: SourceRangeCliDocument,
    val compilerEvidence: CompilerSymbolEvidenceCliDocument,
)

@Serializable
data class CompilerSymbolEvidenceCliDocument(
    val identity: String,
    val signature: CompilerSignatureCliDocument,
)

@Serializable
sealed interface CompilerSignatureCliDocument {
    @Serializable
    @SerialName("function")
    data class Function(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverCliDocument,
        val contextReceivers: List<String>,
        val valueParameters: List<String>,
        val typeParameterCount: Int,
    ) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("property")
    data class Property(
        val qualifiedIdentity: String,
        val receiver: CompilerReceiverCliDocument,
        val contextReceivers: List<String>,
        val returnType: String,
    ) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("LOCAL_FUNCTION")
    data class LocalFunction(
        val address: LocalDeclarationAddressCliDocument,
        val receiver: CompilerReceiverCliDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val contextReceivers: List<String>,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val valueParameters: List<String>,
        @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 0, maximum = 2147483647)
        val typeParameterCount: Int,
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(minimumLength = 1, maximumLength = 1048576)
        val returnType: String,
    ) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("LOCAL_PROPERTY")
    data class LocalProperty(
        val address: LocalDeclarationAddressCliDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(minimumLength = 1, maximumLength = 1048576)
        val returnType: String,
        val mutability: LocalPropertyMutabilityCliDocument,
    ) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("ANONYMOUS_OBJECT")
    data class AnonymousObject(
        val address: LocalDeclarationAddressCliDocument,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 1000)
        val supertypes: List<String>,
    ) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("type-alias")
    data class TypeAlias(val qualifiedIdentity: String) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("class-like")
    data class ClassLike(val qualifiedIdentity: String) : CompilerSignatureCliDocument
}

@Serializable
data class LocalDeclarationAddressCliDocument(
    val file: LocalDeclarationFileCliDocument,
    val kind: LocalDeclarationKindCliDocument,
    val range: SourceRangeCliDocument,
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        minimumLength = 1,
        maximumLength = 4096,
        pattern = "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$",
    )
    val ownerIdentity: String,
    val ownerRange: SourceRangeCliDocument,
    @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(maximumItems = 32)
    val lexicalOwners: List<SourceRangeCliDocument>,
)

@Serializable
sealed interface LocalDeclarationFileCliDocument {
    @Serializable @SerialName("WORKSPACE") data class Workspace(val path: String) : LocalDeclarationFileCliDocument

    @Serializable @SerialName("EXTERNAL") data class External(val url: String) : LocalDeclarationFileCliDocument
}

@Serializable
enum class LocalDeclarationKindCliDocument {
    FUNCTION,
    PROPERTY,
    ANONYMOUS_OBJECT,
}

@Serializable
enum class LocalPropertyMutabilityCliDocument {
    VAL,
    VAR,
}

@Serializable
sealed interface CompilerReceiverCliDocument {
    @Serializable @SerialName("absent") data object Absent : CompilerReceiverCliDocument

    @Serializable @SerialName("present") data class Present(val compilerType: String) : CompilerReceiverCliDocument
}

@Serializable
data class SourceRangeCliDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

fun SymbolDocument.toCliDocument(): SymbolCliDocument =
    SymbolCliDocument(
        selector = selector.value,
        kind = kind.cliName(),
        name = name.value,
        qualifiedIdentity =
            when (val identity = qualifiedIdentity) {
                is SymbolQualifiedIdentityDocument.Available -> identity.value.value
                SymbolQualifiedIdentityDocument.Unavailable -> null
            },
        file = file.value,
        range = range.toCliDocument(),
        compilerEvidence = compilerEvidence.toCliDocument(),
    )

fun CompilerSymbolEvidenceDocument.toCliDocument(): CompilerSymbolEvidenceCliDocument =
    CompilerSymbolEvidenceCliDocument(identity.value, signature.toCliDocument())

fun CompilerSignatureDocument.toCliDocument(): CompilerSignatureCliDocument =
    when (this) {
        is CompilerSignatureDocument.Function ->
            CompilerSignatureCliDocument.Function(
                qualifiedIdentity.value,
                receiver.toCliDocument(),
                contextReceivers.values.map { it.value },
                valueParameters.values.map { it.value },
                typeParameterCount.value,
            )
        is CompilerSignatureDocument.Property ->
            CompilerSignatureCliDocument.Property(
                qualifiedIdentity.value,
                receiver.toCliDocument(),
                contextReceivers.values.map { it.value },
                returnType.value,
            )
        is CompilerSignatureDocument.LocalFunction ->
            CompilerSignatureCliDocument.LocalFunction(
                address.toCliDocument(),
                receiver.toCliDocument(),
                contextReceivers.values.map { it.value },
                valueParameters.values.map { it.value },
                typeParameterCount.value,
                returnType.value,
            )
        is CompilerSignatureDocument.LocalProperty ->
            CompilerSignatureCliDocument.LocalProperty(
                address.toCliDocument(),
                returnType.value,
                when (mutability) {
                    LocalPropertyMutabilityDocument.VAL -> LocalPropertyMutabilityCliDocument.VAL
                    LocalPropertyMutabilityDocument.VAR -> LocalPropertyMutabilityCliDocument.VAR
                },
            )
        is CompilerSignatureDocument.AnonymousObject ->
            CompilerSignatureCliDocument.AnonymousObject(address.toCliDocument(), supertypes.values.map { it.value })
        is CompilerSignatureDocument.TypeAlias -> CompilerSignatureCliDocument.TypeAlias(qualifiedIdentity.value)
        is CompilerSignatureDocument.ClassLike -> CompilerSignatureCliDocument.ClassLike(qualifiedIdentity.value)
    }

fun LocalDeclarationAddressDocument.toCliDocument(): LocalDeclarationAddressCliDocument =
    LocalDeclarationAddressCliDocument(
        when (val location = file) {
            is LocalDeclarationFileDocument.Workspace -> LocalDeclarationFileCliDocument.Workspace(location.value.value)
            is LocalDeclarationFileDocument.External -> LocalDeclarationFileCliDocument.External(location.value.value)
        },
        when (kind) {
            LocalDeclarationKindDocument.FUNCTION -> LocalDeclarationKindCliDocument.FUNCTION
            LocalDeclarationKindDocument.PROPERTY -> LocalDeclarationKindCliDocument.PROPERTY
            LocalDeclarationKindDocument.ANONYMOUS_OBJECT -> LocalDeclarationKindCliDocument.ANONYMOUS_OBJECT
        },
        range.toCliDocument(),
        ownerIdentity.value,
        ownerRange.toCliDocument(),
        lexicalOwners.values.map { it.toCliDocument() },
    )

private fun CompilerReceiverDocument.toCliDocument(): CompilerReceiverCliDocument =
    when (this) {
        CompilerReceiverDocument.Absent -> CompilerReceiverCliDocument.Absent
        is CompilerReceiverDocument.Present -> CompilerReceiverCliDocument.Present(compilerType.value)
    }

private fun SourceRangeDocument.toCliDocument(): SourceRangeCliDocument =
    SourceRangeCliDocument(startInclusive.value, endExclusive.value)
