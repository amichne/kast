package io.github.amichne.kast.protocol.contract

fun QueryExcludedCompilerTargetDocument.callbackWire() =
    QueryExcludedCompilerTargetWireDocument(
        file.value,
        range.toWireDocument(),
        name.value,
        kind.toWireDocument(),
        compilerEvidence.toWireDocument(),
    )

fun QueryExcludedCompilerTargetWireDocument.toContract(): WireDocumentConversion<QueryExcludedCompilerTargetDocument> =
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
