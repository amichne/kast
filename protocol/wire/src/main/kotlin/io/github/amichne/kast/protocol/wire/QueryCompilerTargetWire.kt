package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument

internal fun QueryExcludedCompilerTargetDocument.callbackWire() =
    QueryExcludedCompilerTargetWireDocument(
        file.value,
        range.toWireDocument(),
        name.value,
        kind.toWireDocument(),
        compilerEvidence.toWireDocument(),
    )

internal fun QueryExcludedCompilerTargetWireDocument.toContract():
    WireDocumentConversion<QueryExcludedCompilerTargetDocument> =
    combineConverted(
            ProtocolText.parse(file).toWireDocumentConversion(),
            range.toContract(),
            ProtocolText.parse(name).toWireDocumentConversion(),
            compilerEvidence.toContract(),
        ) { file, range, name, evidence ->
            QueryExcludedCompilerTargetDocument.create(file, range, name, kind.toContract(), evidence)
                .toWireDocumentConversion()
        }
        .flattenConverted()
