package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.query.contract.QueryImpactClassification
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRelationship
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueStringContent
import io.github.amichne.kast.relation.contract.ValueStringContentInput
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactStringContentProjectionTest {
    @Test
    fun `derived relationship survives an exact argument edge and encoded retention`() {
        val path = derivedPath()
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.DERIVED), path.classification)
        val document = path.impactDocument().contentRefined()
        val encoded = Json.encodeToString(ImpactPathDocument.serializer(), document)
        assertTrue(encoded.contains("\"classification\":{\"type\":\"ESTABLISHED\",\"relationship\":\"DERIVED\"}"))
        assertTrue(encoded.contains("\"input\":\"STRING\",\"null_alternative\":\"NOT_NULLABLE\""))
        assertTrue(encoded.contains("\"type\":\"STRING_CONTENT\""))
        assertEquals(document, Json.decodeFromString(ImpactPathDocument.serializer(), encoded))
    }

    @Test
    fun `encoded exact label cannot erase a retained derived step`() {
        val encoded =
            Json.encodeToString(ImpactPathDocument.serializer(), derivedPath().impactDocument().contentRefined())
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactPathDocument.serializer(), encoded.replace("\"DERIVED\"", "\"EXACT\""))
        }
    }

    @Test
    fun `unknown relationship and missing classification reject`() {
        val encoded =
            Json.encodeToString(ImpactPathDocument.serializer(), derivedPath().impactDocument().contentRefined())
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactPathDocument.serializer(), encoded.replace("\"DERIVED\"", "\"UNKNOWN\""))
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactPathDocument.serializer(), encoded.substringBefore(",\"classification\"") + "}")
        }
    }

    private fun derivedPath(): QueryImpactPath {
        val fixture = ImpactPathProjectionFixture()
        val template = fixture.site(28, 42, ValueRole.ExpressionResult)
        val evidence =
            ValueStringContent.fromCompiler(
                    templateRange = range(28, 42),
                    entryRange = range(29, 41),
                    inputRange = range(30, 40),
                    input = ValueStringContentInput.STRING,
                )
                .contentRefined()
        val content =
            ValueTransfer.fromCompiler(fixture.producer, template, ValueTransferKind.STRING_CONTENT, evidence)
                .contentRefined()
        val invocation =
            ValueInvocation.fromCompiler(fixture.owner, range(20, 80), fixture.call.callable).contentRefined()
        val argument =
            fixture.site(
                28,
                42,
                ValueRole.Argument(
                    invocation,
                    io.github.amichne.kast.relation.contract.ValueArgumentPosition.parse(0).contentRefined(),
                ),
            )
        val exact = ValueTransfer.fromCompiler(template, argument, ValueTransferKind.ARGUMENT).contentRefined()
        val domain = domain(fixture)
        val observation =
            ValueFlowStep.fromCompiler(
                    source = argument,
                    transfers = emptyList(),
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain = domain,
                    examinedWorkUnits = RelationWorkCount.parse(1).contentRefined(),
                )
                .contentRefined()
        return QueryImpactPath.fromEvidence(
                producer = fixture.producer,
                steps = listOf(QueryImpactStep.Compiler(content), QueryImpactStep.Compiler(exact)),
                representation = QueryImpactRepresentation.NotModeled,
                terminal = QueryImpactTerminal.SupportedDomainEnd.admit(observation).contentRefined(),
            )
            .contentRefined()
    }

    private fun domain(fixture: ImpactPathProjectionFixture): RelationRequest {
        val domain =
            RelationRequest.start(
                subject = fixture.owner,
                meaning = RelationMeaning.References,
                budget =
                    RelationBudget(
                        io.github.amichne.kast.kernel.ResourceBudget(
                            io.github.amichne.kast.kernel.ResultLimit.parse(16).contentRefined(),
                            io.github.amichne.kast.kernel.WorkUnitLimit.parse(100).contentRefined(),
                            io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1000).contentRefined(),
                        ),
                        RelationByteLimit.parse(1_000_000).contentRefined(),
                    ),
                boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        return domain
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).contentRefined()
}

private fun <T, F> Refinement<T, F>.contentRefined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid independent content fixture: $failure")
    }
