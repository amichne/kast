@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCallbackInputFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactCapturedBodyDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationDispatchDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalCallbackSchemaDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationAddressCliDocument
import java.util.IdentityHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nonNullOriginal

/** Only the registry's actual serializer descriptors establish a reusable definition address. */
internal class CanonicalSchemaReferences
private constructor(private val names: IdentityHashMap<SerialDescriptor, String>) {
    fun name(descriptor: SerialDescriptor): String? = names[descriptor.nonNullOriginal]

    companion object {
        val None = from(emptyMap())
        val Installed by lazy {
            from(
                CanonicalCallbackSchemaDocuments.serializers +
                    mapOf(
                        "callbackObservation" to CanonicalQueryCliDocuments.callbackObservationSerializer,
                        "callableObservation" to CanonicalQueryCliDocuments.callableObservationSerializer,
                        "queryResultItem" to CanonicalQueryCliDocuments.itemSerializer,
                        "localDeclarationAddress" to LocalDeclarationAddressCliDocument.serializer(),
                        "impactSemanticBasis" to ImpactSemanticBasisDocument.serializer(),
                        "impactDeclarationReference" to ImpactDeclarationReferenceDocument.serializer(),
                        "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
                        "impactExecutionOwner" to ImpactExecutionOwnerDocument.serializer(),
                        "impactCapturedBody" to ImpactCapturedBodyDocument.serializer(),
                        "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
                        "impactCallFrame" to ImpactCallFrameDocument.serializer(),
                        "impactCallTarget" to ImpactCallTargetDocument.serializer(),
                        "impactValueRole" to ImpactValueRoleDocument.serializer(),
                        "impactValueSiteReference" to ImpactValueSiteReferenceDocument.serializer(),
                        "impactCompilerTransfer" to ImpactCompilerTransferDocument.serializer(),
                        "impactReviewedValueBinding" to ImpactReviewedValueBindingDocument.serializer(),
                        "impactReviewedValueTransfer" to ImpactReviewedValueTransferDocument.serializer(),
                        "impactImplementationDispatch" to ImpactImplementationDispatchDocument.serializer(),
                        "impactCallbackInputFailure" to ImpactCallbackInputFailureDocument.serializer(),
                        "impactFlowDomain" to ImpactFlowDomainDocument.serializer(),
                    )
            )
        }

        fun from(serializers: Map<String, KSerializer<*>>): CanonicalSchemaReferences {
            val names = IdentityHashMap<SerialDescriptor, String>()
            serializers.forEach { (name, serializer) ->
                check(names.put(serializer.descriptor, name) == null) { "Conflicting canonical schema address: $name" }
            }
            return CanonicalSchemaReferences(names)
        }
    }
}
