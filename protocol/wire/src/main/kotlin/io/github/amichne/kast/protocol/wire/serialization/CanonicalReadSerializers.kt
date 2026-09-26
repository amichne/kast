package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.reason

internal object CanonicalReadSerializers {
    private val factory = GeneratedWireCodecFactory(wireJson)

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
