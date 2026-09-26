@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
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
    @SerialName("type-alias")
    data class TypeAlias(val qualifiedIdentity: String) : CompilerSignatureCliDocument

    @Serializable
    @SerialName("class-like")
    data class ClassLike(val qualifiedIdentity: String) : CompilerSignatureCliDocument
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
        is CompilerSignatureDocument.TypeAlias -> CompilerSignatureCliDocument.TypeAlias(qualifiedIdentity.value)
        is CompilerSignatureDocument.ClassLike -> CompilerSignatureCliDocument.ClassLike(qualifiedIdentity.value)
    }

private fun CompilerReceiverDocument.toCliDocument(): CompilerReceiverCliDocument =
    when (this) {
        CompilerReceiverDocument.Absent -> CompilerReceiverCliDocument.Absent
        is CompilerReceiverDocument.Present -> CompilerReceiverCliDocument.Present(compilerType.value)
    }

private fun SourceRangeDocument.toCliDocument(): SourceRangeCliDocument =
    SourceRangeCliDocument(startInclusive.value, endExclusive.value)
