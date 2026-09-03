package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest

internal object CanonicalReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val symbolDiscoverRequest = CanonicalSymbolSerializers.discoverRequest
    val symbolDiscoverResult = CanonicalSymbolSerializers.discoverResult
    val symbolDiscoverQualification = factory.create(
        SymbolDiscoverQualificationDocument.serializer(),
        SymbolDiscoverQualification::toReadDocument,
        SymbolDiscoverQualificationDocument::toContract,
    )
    val symbolDiscoverRejection = factory.create(
        SymbolDiscoverRejectionWireDocument.serializer(),
        SymbolDiscoverRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

    val symbolInspectRequest = factory.create(
        SymbolInspectRequestDocument.serializer(),
        SymbolInspectRequest::toReadDocument,
        SymbolInspectRequestDocument::toContract,
    )
    val symbolInspectResult = CanonicalSymbolSerializers.describeResult
    val symbolInspectQualification = factory.create(
        SymbolInspectQualificationWireDocument.serializer(),
        SymbolInspectQualification::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
    val symbolInspectRejection = factory.create(
        SymbolInspectRejectionWireDocument.serializer(),
        SymbolInspectRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

    val relationReadRequest = factory.create(
        RelationReadRequestDocument.serializer(),
        RelationReadRequest::toReadDocument,
        RelationReadRequestDocument::toContract,
    )
    val relationReadResult = CanonicalSymbolSerializers.relationResult
    val relationReadQualification = factory.create(
        RelationReadQualificationWireDocument.serializer(),
        RelationReadQualification::toWireDocument,
        RelationReadQualificationWireDocument::toContract,
    )
    val relationReadRejection = factory.create(
        RelationReadRejectionWireDocument.serializer(),
        RelationReadRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

    val traversalRunRequest = factory.create(
        TraversalRunRequestDocument.serializer(),
        TraversalRunRequest::toReadDocument,
        TraversalRunRequestDocument::toContract,
    )
    val traversalRunResult = CanonicalSymbolSerializers.traversalResult
    val traversalRunQualification = factory.create(
        TraversalRunQualificationWireDocument.serializer(),
        TraversalRunQualification::toWireDocument,
        TraversalRunQualificationWireDocument::toContract,
    )
    val traversalRunRejection = factory.create(
        TraversalRunRejectionWireDocument.serializer(),
        TraversalRunRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

    val diagnosticCheckRequest = factory.create(
        DiagnosticCheckRequestDocument.serializer(),
        DiagnosticCheckRequest::toReadDocument,
        DiagnosticCheckRequestDocument::toContract,
    )
    val diagnosticCheckResult = factory.create(
        DiagnosticCheckResultDocument.serializer(),
        DiagnosticCheckResult::toReadDocument,
        DiagnosticCheckResultDocument::toContract,
    )
    val diagnosticCheckQualification = factory.create(
        DiagnosticCheckQualificationWireDocument.serializer(),
        DiagnosticCheckQualification::toWireDocument,
        DiagnosticCheckQualificationWireDocument::toContract,
    )
    val diagnosticCheckRejection = factory.create(
        DiagnosticCheckRejectionWireDocument.serializer(),
        DiagnosticCheckRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
}
