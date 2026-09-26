package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.reason

internal object CanonicalReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

    val symbolDiscoverRequest = CanonicalSymbolSerializers.discoverRequest
    val symbolDiscoverResult = CanonicalSymbolSerializers.discoverResult
    val symbolDiscoverQualification =
        factory.create(
            SymbolDiscoverQualificationDocument.serializer(),
            SymbolDiscoverQualification::toReadDocument,
            SymbolDiscoverQualificationDocument::toContract,
        )
    val symbolDiscoverRejection =
        factory.create(
            SymbolDiscoverRejectionWireDocument.serializer(),
            SymbolDiscoverRejection::toWireDocument,
            { document -> WireDocumentConversion.Converted(document.toContract()) },
        )

    val symbolInspectRequest = factory.create(SymbolInspectRequest.serializer())
    val symbolInspectResult = CanonicalSymbolSerializers.describeResult
    val symbolInspectQualification =
        factory.create(
            SymbolInspectQualificationWireDocument.serializer(),
            SymbolInspectQualification::toWireDocument,
            { document -> WireDocumentConversion.Converted(document.toContract()) },
        )
    val symbolInspectRejection =
        factory.create(
            SymbolInspectRejectionWireDocument.serializer(),
            SymbolInspectRejection::toWireDocument,
            { document -> WireDocumentConversion.Converted(document.toContract()) },
        )

    val diagnosticCheckRequest = factory.create(DiagnosticCheckRequest.serializer())
    val diagnosticCheckResult =
        factory.create(
            DiagnosticCheckResultDocument.serializer(),
            DiagnosticCheckResult::toReadDocument,
            DiagnosticCheckResultDocument::toContract,
        )
    val diagnosticCheckQualification =
        factory.create(
            DiagnosticCheckQualificationWireDocument.serializer(),
            DiagnosticCheckQualification::toWireDocument,
            DiagnosticCheckQualificationWireDocument::toContract,
        )
    val diagnosticCheckRejection: WireValueCodec<DiagnosticCheckFailure> =
        factory.create(
            DiagnosticCheckRejectionWireDocument.serializer(),
            { value: DiagnosticCheckFailure -> value.reason().toWireDocument() },
            { document -> WireDocumentConversion.Converted(document.toContract()) },
        )
}
