package io.github.amichne.kast.relation.contract

internal fun ValueFootprintMeter.knownDeclarations(known: ValueKnownDeclarations): Long =
    payload(known) {
        VALUE_RECORD_OVERHEAD.addBytes(
            known.declarations.fold(0L) { bytes, declaration ->
                bytes.addBytes(payload(declaration) { VALUE_RECORD_OVERHEAD.addBytes(endpoint(declaration.endpoint)) })
            }
        )
    }
