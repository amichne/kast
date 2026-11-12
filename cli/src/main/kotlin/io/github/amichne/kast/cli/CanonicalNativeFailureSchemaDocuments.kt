package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import kotlinx.serialization.KSerializer

/** Share the exact typed failure schemas instead of duplicating them at every retained evidence site. */
internal object CanonicalNativeFailureSchemaDocuments {
    val serializers: Map<String, KSerializer<*>> =
        mapOf(
            "impactReadRejection" to ImpactReadRejectionDocument.serializer(),
            "impactNativeReadRejection" to ImpactNativeReadRejectionDocument.serializer(),
            "impactNativeOperationalFailure" to ImpactNativeReadRejectionDocument.Operational.serializer(),
        )
}
