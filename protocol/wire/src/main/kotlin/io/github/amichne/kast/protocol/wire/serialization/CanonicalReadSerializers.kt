package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult

internal object CanonicalReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val workspaceInspectRequest = factory.create(
        WorkspaceInspectRequestDocument.serializer(),
        { WorkspaceInspectRequestDocument },
        { WireDocumentConversion.Converted(WorkspaceInspectRequest) },
    )
    val workspaceInspectResult = factory.create(
        WorkspaceInspectResultDocument.serializer(),
        WorkspaceInspectResult::toReadDocument,
        WorkspaceInspectResultDocument::toContract,
    )
    val workspaceInspectQualification = factory.create(
        WorkspaceInspectQualificationWireDocument.serializer(),
        WorkspaceInspectQualification::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
    val workspaceInspectRejection = factory.create(
        WorkspaceInspectRejectionWireDocument.serializer(),
        WorkspaceInspectRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

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

    val symbolResolveRequest = factory.create(
        SymbolResolveRequestDocument.serializer(),
        SymbolResolveRequest::toReadDocument,
        SymbolResolveRequestDocument::toContract,
    )
    val symbolResolveResult = factory.create(
        SymbolResolveResultDocument.serializer(),
        SymbolResolveResult::toReadDocument,
        SymbolResolveResultDocument::toContract,
    )
    val symbolResolveQualification = factory.create(
        SymbolResolveQualificationWireDocument.serializer(),
        SymbolResolveQualification::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
    val symbolResolveRejection = factory.create(
        SymbolResolveRejectionWireDocument.serializer(),
        SymbolResolveRejection::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )

    val symbolDescribeRequest = factory.create(
        SymbolDescribeRequestDocument.serializer(),
        SymbolDescribeRequest::toReadDocument,
        SymbolDescribeRequestDocument::toContract,
    )
    val symbolDescribeResult = CanonicalSymbolSerializers.describeResult
    val symbolDescribeQualification = factory.create(
        SymbolDescribeQualificationWireDocument.serializer(),
        SymbolDescribeQualification::toWireDocument,
        { document -> WireDocumentConversion.Converted(document.toContract()) },
    )
    val symbolDescribeRejection = factory.create(
        SymbolDescribeRejectionWireDocument.serializer(),
        SymbolDescribeRejection::toWireDocument,
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
