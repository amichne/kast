package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal data class MethodTraceSymbols(
    val seed: SymbolSelector,
    val adapter: SymbolSelector,
    val client: SymbolSelector,
    val override: SymbolSelector,
)

internal fun methodTraceService(
    fixture: QueryServiceTest,
    symbols: MethodTraceSymbols,
    reads: MutableList<Pair<String, RelationMeaning>>,
    incompleteReferences: Boolean = false,
    referenceStarts: List<Int> = listOf(200, 201),
): QueryService {
    val seed = symbols.seed
    val adapter = symbols.adapter
    val client = symbols.client
    val override = symbols.override
    return traceService(
        fixture,
        RelationOperations { read ->
            reads += read.subject.name.value to read.meaning
            val targets =
                when (read.subject.name.value to read.meaning) {
                    "execute" to RelationMeaning.References -> referenceStarts.map { adapter to it }
                    "execute" to RelationMeaning.Overrides -> listOf(override to 210)
                    "execute" to RelationMeaning.Callers -> listOf(adapter to 220)
                    "overrideExecute" to RelationMeaning.References -> emptyList()
                    "overrideExecute" to RelationMeaning.Callers -> listOf(adapter to 230)
                    "executeWithCache" to RelationMeaning.Callers -> listOf(client to 240)
                    else -> error("Unexpected trace read: ${read.subject.name.value} / ${read.meaning}")
                }
            val facts = methodTraceFacts(read, seed, targets)
            val batch = traceBatch(read, facts)
            if (
                incompleteReferences &&
                    read.subject.name.value == "execute" &&
                    read.meaning == RelationMeaning.References
            ) {
                val partial =
                    RelationCompilation.qualifiedTerminal(batch, setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined()
                return@RelationOperations RelationReadResult.Qualified(partial.batch, partial.coverage)
            }
            val completed = RelationCompilation.complete(batch)
            RelationReadResult.Complete(completed.batch, completed.coverage)
        },
    )
}

private fun methodTraceFacts(
    read: RelationRequest,
    seed: SymbolSelector,
    targets: List<Pair<SymbolSelector, Int>>,
): List<RelationFact> {
    return targets
        .map { (selected, offset) ->
            val related =
                RelationEndpoint.resolve(
                        read.subject.lease,
                        read.searchScope,
                        CompilerGroundedSymbolEvidence.fromSelector(selected),
                        read.searchConstraints,
                    )
                    .refined()
            RelationFact.create(
                    read,
                    related,
                    read.subject,
                    RelationOccurrence.fromBoundary(seed.file, offset, offset + 1).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        }
        .sorted()
}

internal fun traceFunction(
    basis: io.github.amichne.kast.symbol.contract.SymbolSelector,
    name: String,
    offset: Int,
): io.github.amichne.kast.symbol.contract.SymbolSelector {
    val signature =
        io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.function(
                "sample.$name",
                null,
                emptyList(),
                emptyList(),
                0,
            )
            .refined()
    val evidence =
        io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromBoundary(
                basis.file,
                offset,
                offset + 20,
                name,
                "sample.$name",
                io.github.amichne.kast.symbol.contract.CompilerSymbolKind.FUNCTION,
                signature,
            )
            .refined()
    return io.github.amichne.kast.symbol.contract.SymbolSelector.issue(
        basis.lease,
        basis.scope,
        evidence,
        basis.constraints,
    )
}

internal fun traceService(fixture: QueryServiceTest, relations: RelationOperations) =
    QueryService(
        fixture.discoveryEmpty(false),
        fixture.exactOperations(
            describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
            resolve = { error("No discovery expected") },
        ),
        SourceReadOperations { request ->
            traceMemberSourceRead(
                (request.anchor as io.github.amichne.kast.source.contract.SourceReadAnchor.Symbol).selector,
                emptyList(),
            )
        },
        relations,
        unexpectedQueryTraversal(),
        queryTestTraversalCeiling(),
    )

internal fun traceFact(read: RelationRequest, start: Int) =
    RelationFact.create(
            read,
            read.subject,
            read.subject,
            RelationOccurrence.fromBoundary(read.subject.file, start, start + 1).refined(),
            RelationProvenance.K2_AUTHORED_SOURCE,
        )
        .refined()

internal fun traceBatch(read: RelationRequest, facts: List<RelationFact>) =
    RelationBatch.create(
            read,
            facts,
            RelationByteCount.parse(facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() })
                .refined(),
            RelationWorkCount.parse(facts.size.toLong()).refined(),
            RelationResultCount.parse(facts.size).refined(),
        )
        .refined()

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
