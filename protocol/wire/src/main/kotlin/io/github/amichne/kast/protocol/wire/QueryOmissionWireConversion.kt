package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument

internal fun QueryRelationOmissionDocument.toWire(): QueryRelationOmissionWireDocument =
    QueryRelationOmissionWireDocument(
        subject = subject.toWire() as QueryReferenceWireDocument.ExactSymbol,
        relation = relation.toWireDocument(),
        evidence = evidence.toWireDocument(),
    )

internal fun QueryRelationOmissionWireDocument.toContract(): WireDocumentConversion<QueryRelationOmissionDocument> =
    subject.token.queryProtocolText().flatMapConverted { token ->
        evidence.toContract().mapConverted { omission ->
            QueryRelationOmissionDocument(QueryReferenceDocument.ExactSymbol(token), relation.toContract(), omission)
        }
    }

internal fun String.queryProtocolText() = ProtocolText.parse(this).toWireDocumentConversion()
