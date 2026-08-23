package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CanonicalTopologySerializers {
    val request = jsonContractSerializer<TopologyBuildRequest>(
        "kast.topology.build.request.v1",
        encode = { JsonObject(emptyMap()) },
        decode = { it.objectWithFields(); TopologyBuildRequest },
    )

    val result = jsonContractSerializer<TopologyBuildResult>(
        "kast.topology.build.result.v1",
        encode = { value ->
            buildJsonObject {
                put("status", value.status.name.lowercase())
                put("digest", value.digest.asJson())
            }
        },
        decode = { element ->
            val value = element.objectWithFields("status", "digest")
            val status = try {
                enumValueOf<TopologyBuildStatus>(value.getValue("status").stringValue().uppercase())
            } catch (_: IllegalArgumentException) {
                throw SerializationException("Invalid topology status")
            }
            TopologyBuildResult(status, value.protocolText("digest"))
        },
    )

    val qualification = canonicalEnumSerializer<TopologyBuildQualification>(
        "kast.topology.build.qualification.v1",
    )
    val rejection = canonicalEnumSerializer<TopologyBuildRejection>(
        "kast.topology.build.rejection.v1",
    )
}
