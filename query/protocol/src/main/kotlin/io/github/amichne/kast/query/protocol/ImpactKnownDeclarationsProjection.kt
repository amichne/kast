package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactKnownDeclarationDocument
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations

internal fun ValueKnownDeclarations.knownDeclarationDocuments():
    ImpactProjected<BoundedProtocolList<ImpactKnownDeclarationDocument>> = declarations.impactEach { proof ->
    proof.endpoint.impactModelEndpoint().impactMap { endpoint ->
        when (proof.endpoint) {
            is RelationEndpoint.Subject -> ImpactKnownDeclarationDocument.Subject(endpoint)
            is RelationEndpoint.Resolved -> ImpactKnownDeclarationDocument.Resolved(endpoint)
        }
    }
}
