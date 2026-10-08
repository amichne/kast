@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

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
