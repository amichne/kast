package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.wire.QueryCallbackBindingWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackBodyWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackCallableWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackFactoryBodyCallWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackFactoryBodyCallsWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackFactoryCaptureWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackFactoryWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackForwardingEvidenceWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackInvocationWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackParameterIdentityWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackParameterSupplierWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackSupplierInventoryWireDocument
import io.github.amichne.kast.protocol.wire.QueryCallbackSupplierPartitionWireDocument
import io.github.amichne.kast.protocol.wire.QueryImmutableCallbackFlowWireDocument
import io.github.amichne.kast.protocol.wire.QueryImmutableCallbackValueNodeWireDocument
import io.github.amichne.kast.protocol.wire.QueryImmutableCallbackValueWireDocument
import io.github.amichne.kast.protocol.wire.QueryNamedCallbackReferenceWireDocument
import kotlinx.serialization.KSerializer

/** Stable local schema addresses reuse the exact serializer contracts, including all nested qualifications. */
object CanonicalCallbackSchemaDocuments {
    val serializers: Map<String, KSerializer<*>>
        get() =
            mapOf(
                "compilerSymbolEvidence" to
                    io.github.amichne.kast.protocol.wire.CompilerSymbolEvidenceWireDocument.serializer(),
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
                "callbackCompilerTarget" to
                    io.github.amichne.kast.protocol.wire.QueryExcludedCompilerTargetWireDocument.serializer(),
                "callbackInvocation" to QueryCallbackInvocationWireDocument.serializer(),
                "callbackFormal" to QueryCallbackParameterIdentityWireDocument.serializer(),
                "callbackForwardingEvidence" to QueryCallbackForwardingEvidenceWireDocument.serializer(),
            )
}
