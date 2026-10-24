package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryRelationOmissionCliDocument(
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val subject: String,
    @Serializable(with = RelationKindCliSerializer::class)
    val relation: io.github.amichne.kast.protocol.contract.RelationKindDocument,
    val evidence: RelationOmissionCliDocument,
)

internal fun QueryRelationOmissionDocument.toCliDocument() =
    QueryRelationOmissionCliDocument(subject.toCliDocument(), relation, evidence.toCliDocument())
