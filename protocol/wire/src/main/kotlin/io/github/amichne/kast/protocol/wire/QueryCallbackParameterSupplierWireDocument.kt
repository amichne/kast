@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackSupplierSelectionDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackSupplierSelectionWireDocument {
    @Serializable
    @SerialName("EXPLICIT")
    data class Explicit(val argument: ImpactValueSiteReferenceDocument) : QueryCallbackSupplierSelectionWireDocument

    @Serializable
    @SerialName("DEFAULT")
    data class Default(val declaration: QueryCallbackBindingWireDocument) : QueryCallbackSupplierSelectionWireDocument
}

@Serializable
internal data class QueryCallbackParameterSupplierWireDocument(
    val binding: QueryCallbackBindingWireDocument,
    val selection: QueryCallbackSupplierSelectionWireDocument,
    val value: QueryImmutableCallbackValueWireDocument,
)

internal fun QueryCallbackParameterSupplierDocument.callbackSupplierWire() =
    QueryCallbackParameterSupplierWireDocument(
        binding.callbackWire(),
        when (val selected = selection) {
            is QueryCallbackSupplierSelectionDocument.Explicit ->
                QueryCallbackSupplierSelectionWireDocument.Explicit(selected.argument)
            is QueryCallbackSupplierSelectionDocument.Default ->
                QueryCallbackSupplierSelectionWireDocument.Default(selected.declaration.callbackWire())
        },
        value.immutableCallbackWire(),
    )

internal fun QueryCallbackSupplierSelectionWireDocument.toContract():
    WireDocumentConversion<QueryCallbackSupplierSelectionDocument> =
    when (this) {
        is QueryCallbackSupplierSelectionWireDocument.Explicit ->
            WireDocumentConversion.Converted(QueryCallbackSupplierSelectionDocument.Explicit(argument))
        is QueryCallbackSupplierSelectionWireDocument.Default ->
            declaration.toContract().flatMapConverted {
                if (it is QueryCallbackBindingDocument.Default)
                    WireDocumentConversion.Converted(QueryCallbackSupplierSelectionDocument.Default(it))
                else WireDocumentConversion.Rejected
            }
    }

internal fun QueryCallbackParameterSupplierWireDocument.toContract():
    WireDocumentConversion<QueryCallbackParameterSupplierDocument> =
    combineConverted(binding.toContract(), selection.toContract(), value.toContract()) { binding, selection, value ->
            Triple(binding, selection, value)
        }
        .flatMapConverted { (binding, selection, value) ->
            if (binding is QueryCallbackBindingDocument.Bound)
                QueryCallbackParameterSupplierDocument.create(binding, selection, value).toWireDocumentConversion()
            else WireDocumentConversion.Rejected
        }
