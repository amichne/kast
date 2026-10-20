@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuerySourceLessCallableWireDocument(
    @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
    val kind: SymbolKindWireDocument,
    val origin: QuerySourceLessCallableOriginDocument,
    @SerialName("module_kind") val moduleKind: QuerySourceLessCallableModuleKindDocument,
    @SerialName("module_name")
    @ProtocolStringConstraint(minimumLength = 1, maximumLength = 512, pattern = "^[^\\x00-\\x1F\\x7F-\\x9F]+$")
    val moduleName: String,
)

fun io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument.invocationWire() =
    QueryCallbackInvocationWireDocument(
        occurrence.callbackWire(),
        owner.callbackWire(),
        callableTransfers.values,
        forwardings.values.map { it.callbackWire() },
    )

fun QuerySourceLessCallableDocument.toWireDocument(): QuerySourceLessCallableWireDocument =
    QuerySourceLessCallableWireDocument(
        compilerEvidence.toWireDocument(),
        kind.toWireDocument(),
        origin,
        moduleKind,
        moduleName.value,
    )

fun QuerySourceLessCallableWireDocument.toContract(): WireDocumentConversion<QuerySourceLessCallableDocument> =
    combineConverted(compilerEvidence.toContract(), ProtocolText.parse(moduleName).toWireDocumentConversion()) {
            evidence,
            name ->
            QuerySourceLessCallableDocument.create(evidence, kind.toContract(), origin, moduleKind, name)
                .toWireDocumentConversion()
        }
        .flattenConverted()
