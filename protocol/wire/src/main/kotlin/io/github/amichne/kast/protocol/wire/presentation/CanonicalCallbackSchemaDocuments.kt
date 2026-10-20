package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryBodyCallWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryBodyCallsWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryCaptureWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterSupplierWireDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetWireDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowWireDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueNodeWireDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackSupplierInventoryWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackSupplierPartitionWireDocument
import io.github.amichne.kast.protocol.wire.QueryNamedCallbackReferenceWireDocument
import kotlinx.serialization.KSerializer

/** Stable local schema addresses reuse the exact serializer contracts, including all nested qualifications. */
object CanonicalCallbackSchemaDocuments {
    val serializers: Map<String, KSerializer<*>>
        get() =
            mapOf(
                "compilerSymbolEvidence" to
                    io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceWireDocument.serializer(),
                "immutableCallbackValue" to QueryImmutableCallbackValueWireDocument.serializer(),
                "immutableCallbackValueNode" to QueryImmutableCallbackValueNodeWireDocument.serializer(),
                "immutableCallbackFlow" to QueryImmutableCallbackFlowWireDocument.serializer(),
                "callbackFactory" to QueryCallbackFactoryWireDocument.serializer(),
                "callbackFactoryCapture" to QueryCallbackFactoryCaptureWireDocument.serializer(),
                "callbackFactoryBodyCalls" to QueryCallbackFactoryBodyCallsWireDocument.serializer(),
                "callbackFactoryBodyCall" to QueryCallbackFactoryBodyCallWireDocument.serializer(),
                "callbackSupplier" to QueryCallbackParameterSupplierWireDocument.serializer(),
                "callbackSupplierInventory" to QueryCallbackSupplierInventoryWireDocument.serializer(),
                "callbackSupplierPartition" to QueryCallbackSupplierPartitionWireDocument.serializer(),
                "namedCallbackReference" to QueryNamedCallbackReferenceWireDocument.serializer(),
                "callbackBody" to QueryCallbackBodyWireDocument.serializer(),
                "callbackCallable" to QueryCallbackCallableWireDocument.serializer(),
                "callbackBinding" to QueryCallbackBindingWireDocument.serializer(),
                "callbackObservedFlow" to QueryCallbackFlowWireDocument.Observed.serializer(),
                "callbackCompilerTarget" to
                    io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetWireDocument.serializer(),
                "callbackInvocation" to QueryCallbackInvocationWireDocument.serializer(),
                "callbackFormal" to QueryCallbackParameterIdentityWireDocument.serializer(),
                "callbackForwardingEvidence" to QueryCallbackForwardingEvidenceWireDocument.serializer(),
            )
}
