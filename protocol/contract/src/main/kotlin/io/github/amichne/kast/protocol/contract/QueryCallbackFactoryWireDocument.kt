@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class QueryCallbackFactoryWireDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val callable: QueryCallbackCallableWireDocument,
    @SerialName("returned_value") val returnedValue: QueryImmutableCallbackValueNodeWireDocument,
    @ProtocolCollectionConstraint(maximumItems = 1000) val captures: List<QueryCallbackFactoryCaptureWireDocument>,
    @SerialName("body_calls") val bodyCalls: QueryCallbackFactoryBodyCallsWireDocument,
)

@Serializable
data class QueryCallbackFactoryCaptureWireDocument(
    val binding: QueryCallbackBindingWireDocument,
    val selection: QueryCallbackSupplierSelectionWireDocument,
    val content: QueryCallbackFactoryCaptureContentWireDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackFactoryCaptureContentWireDocument {
    @Serializable @SerialName("SCALAR") data object Scalar : QueryCallbackFactoryCaptureContentWireDocument

    @Serializable
    @SerialName("CALLABLE")
    data class Callable(
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val values: List<QueryImmutableCallbackValueNodeWireDocument>,
        @ProtocolCollectionConstraint(maximumItems = 1000) val invocations: List<QueryCallbackInvocationWireDocument>,
    ) : QueryCallbackFactoryCaptureContentWireDocument
}
