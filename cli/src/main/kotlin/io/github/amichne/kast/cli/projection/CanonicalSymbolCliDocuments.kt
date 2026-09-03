package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object CanonicalSymbolCliDocuments {
    fun projectDiscovery(
        outcome: OperationOutcome<
            SymbolDiscoverResult,
            SymbolDiscoverQualification,
            SymbolDiscoverRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            discoveryCompleteFactory.create(
                SymbolDiscoveryCompleteCliDocument(
                    operation = CanonicalOperation.SYMBOL_DISCOVER.id.value,
                    status = "complete",
                    items = result.items.values.map { it.toCliDocument() },
                ),
            )
        },
        qualified = { result, qualification ->
            discoveryQualifiedFactory.create(
                SymbolDiscoveryQualifiedCliDocument(
                    operation = CanonicalOperation.SYMBOL_DISCOVER.id.value,
                    status = "qualified",
                    items = result.items.values.map { it.toCliDocument() },
                    qualification = qualification.limitations.joinToString(
                        prefix = "[",
                        postfix = "]",
                    ) { limitation -> limitation.cliName() },
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.SYMBOL_DISCOVER, rejection.cliName())
        },
    )

    fun projectInspection(
        outcome: OperationOutcome<
            SymbolInspectResult,
            SymbolInspectQualification,
            SymbolInspectRejection,
            >,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            descriptionCompleteFactory.create(
                SymbolDescriptionCompleteCliDocument(
                    operation = CanonicalOperation.SYMBOL_INSPECT.id.value,
                    status = "complete",
                    symbol = result.symbol.toCliDocument(),
                ),
            )
        },
        qualified = { result, qualification ->
            descriptionQualifiedFactory.create(
                SymbolDescriptionQualifiedCliDocument(
                    operation = CanonicalOperation.SYMBOL_INSPECT.id.value,
                    status = "qualified",
                    symbol = result.symbol.toCliDocument(),
                    qualification = qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.SYMBOL_INSPECT, rejection.cliName())
        },
    )
}

@Serializable
private data class SymbolDiscoveryCompleteCliDocument(
    val operation: String,
    val status: String,
    val items: List<SymbolDiscoveryCliDocument>,
)

@Serializable
private data class SymbolDiscoveryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val items: List<SymbolDiscoveryCliDocument>,
    val qualification: String,
)

@Serializable
internal sealed interface SymbolDiscoveryCliDocument {
    @Serializable
    @SerialName("file")
    data class File(
        val candidateSelector: String,
        val name: String,
        val file: String,
    ) : SymbolDiscoveryCliDocument

    @Serializable
    @SerialName("declaration")
    data class Declaration(
        val candidateSelector: String,
        val kind: String,
        val name: String,
        val file: String,
        val offset: Int,
    ) : SymbolDiscoveryCliDocument

    @Serializable
    @SerialName("text-match")
    data class TextMatch(
        val candidateSelector: String,
        val query: String,
        val file: String,
        val range: SourceRangeCliDocument,
    ) : SymbolDiscoveryCliDocument
}

@Serializable
private data class SymbolDescriptionCompleteCliDocument(
    val operation: String,
    val status: String,
    val symbol: SymbolCliDocument,
)

@Serializable
private data class SymbolDescriptionQualifiedCliDocument(
    val operation: String,
    val status: String,
    val symbol: SymbolCliDocument,
    val qualification: String,
)

@Serializable
internal data class SymbolCliDocument(
    val selector: String,
    val kind: String,
    val name: String,
    val qualifiedIdentity: String?,
    val file: String,
    val range: SourceRangeCliDocument,
    val compilerEvidence: CompilerSymbolEvidenceCliDocument,
)

@Serializable
internal data class CompilerSymbolEvidenceCliDocument(
    val identity: String,
    val signature: CompilerSignatureCliDocument,
)

@Serializable
internal sealed interface CompilerSignatureCliDocument {
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
internal sealed interface CompilerReceiverCliDocument {
    @Serializable
    @SerialName("absent")
    data object Absent : CompilerReceiverCliDocument

    @Serializable
    @SerialName("present")
    data class Present(val compilerType: String) : CompilerReceiverCliDocument
}

@Serializable
internal data class SourceRangeCliDocument(
    val startInclusive: Int,
    val endExclusive: Int,
)

internal fun SymbolDocument.toCliDocument(): SymbolCliDocument = SymbolCliDocument(
    selector = selector.value,
    kind = kind.cliName(),
    name = name.value,
    qualifiedIdentity = when (val identity = qualifiedIdentity) {
        is SymbolQualifiedIdentityDocument.Available -> identity.value.value
        SymbolQualifiedIdentityDocument.Unavailable -> null
    },
    file = file.value,
    range = range.toCliDocument(),
    compilerEvidence = compilerEvidence.toCliDocument(),
)

internal fun CompilerSymbolEvidenceDocument.toCliDocument(): CompilerSymbolEvidenceCliDocument =
    CompilerSymbolEvidenceCliDocument(identity.value, signature.toCliDocument())

private fun CompilerSignatureDocument.toCliDocument(): CompilerSignatureCliDocument = when (this) {
    is CompilerSignatureDocument.Function -> CompilerSignatureCliDocument.Function(
        qualifiedIdentity.value,
        receiver.toCliDocument(),
        contextReceivers.values.map { it.value },
        valueParameters.values.map { it.value },
        typeParameterCount.value,
    )
    is CompilerSignatureDocument.Property -> CompilerSignatureCliDocument.Property(
        qualifiedIdentity.value,
        receiver.toCliDocument(),
        contextReceivers.values.map { it.value },
        returnType.value,
    )
    is CompilerSignatureDocument.TypeAlias ->
        CompilerSignatureCliDocument.TypeAlias(qualifiedIdentity.value)
    is CompilerSignatureDocument.ClassLike ->
        CompilerSignatureCliDocument.ClassLike(qualifiedIdentity.value)
}

private fun CompilerReceiverDocument.toCliDocument(): CompilerReceiverCliDocument = when (this) {
    CompilerReceiverDocument.Absent -> CompilerReceiverCliDocument.Absent
    is CompilerReceiverDocument.Present -> CompilerReceiverCliDocument.Present(compilerType.value)
}

private fun SymbolDiscoveryDocument.toCliDocument(): SymbolDiscoveryCliDocument = when (this) {
    is SymbolDiscoveryDocument.File -> SymbolDiscoveryCliDocument.File(
        candidateSelector = candidateSelector.value,
        name = name.value,
        file = file.value,
    )
    is SymbolDiscoveryDocument.Declaration -> SymbolDiscoveryCliDocument.Declaration(
        candidateSelector = candidateSelector.value,
        kind = kind.cliName(),
        name = name.value,
        file = file.value,
        offset = offset.value,
    )
    is SymbolDiscoveryDocument.TextMatch -> SymbolDiscoveryCliDocument.TextMatch(
        candidateSelector = candidateSelector.value,
        query = query.value,
        file = file.value,
        range = range.toCliDocument(),
    )
}

private fun SourceRangeDocument.toCliDocument(): SourceRangeCliDocument =
    SourceRangeCliDocument(startInclusive.value, endExclusive.value)

private val discoveryCompleteFactory =
    CliJsonDocument.generated(SymbolDiscoveryCompleteCliDocument.serializer())
private val discoveryQualifiedFactory =
    CliJsonDocument.generated(SymbolDiscoveryQualifiedCliDocument.serializer())
private val descriptionCompleteFactory =
    CliJsonDocument.generated(SymbolDescriptionCompleteCliDocument.serializer())
private val descriptionQualifiedFactory =
    CliJsonDocument.generated(SymbolDescriptionQualifiedCliDocument.serializer())
