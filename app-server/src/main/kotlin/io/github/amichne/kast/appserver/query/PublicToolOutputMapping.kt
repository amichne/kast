package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument

/** Closed public row presentations lower to their corresponding canonical evidence types. */
internal fun PublicToolOutput.lower(): QueryOutputDocument =
    when (this) {
        is PublicToolSymbolsOutput -> symbolsOutput()
        PublicToolOccurrencesOutput -> QueryOutputDocument.Occurrences
        PublicToolTraversalRecordsOutput -> QueryOutputDocument.TraversalRecords
        PublicToolBindingRowsOutput -> QueryOutputDocument.BindingRows
        PublicToolValuePathsOutput -> QueryOutputDocument.ValuePaths
    }

internal fun PublicToolReadResultOutput.lower(): QueryOutputDocument =
    when (this) {
        is PublicToolImpactWitnessOutput -> QueryOutputDocument.ImpactWitness(section)
        is PublicToolSymbolsOutput -> symbolsOutput()
        PublicToolOccurrencesOutput -> QueryOutputDocument.Occurrences
        PublicToolTraversalRecordsOutput -> QueryOutputDocument.TraversalRecords
        PublicToolBindingRowsOutput -> QueryOutputDocument.BindingRows
        PublicToolValuePathsOutput -> QueryOutputDocument.ValuePaths
    }

private fun PublicToolSymbolsOutput.symbolsOutput(): QueryOutputDocument.Symbols =
    QueryOutputDocument.Symbols(
        proven(
            BoundedProtocolList.create(
                fields.values.map { field ->
                    when (field) {
                        PublicToolFields.NAME -> QuerySymbolFieldDocument.NAME
                        PublicToolFields.LOCATION -> QuerySymbolFieldDocument.LOCATION
                        PublicToolFields.SIGNATURE -> QuerySymbolFieldDocument.SIGNATURE
                        PublicToolFields.SOURCE -> QuerySymbolFieldDocument.SOURCE
                    }
                }
            )
        )
    )
