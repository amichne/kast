package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactStringConstructionDocument
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueStringConstruction
import io.github.amichne.kast.relation.contract.ValueStringConversionOrigin
import io.github.amichne.kast.relation.contract.ValueStringConversionSelection
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactStringConstructionProjectionTest {
    @Test
    fun `constant null retains its exact construction without a contribution or new result count`() {
        val fixture = ImpactPathProjectionFixture()
        val initial = observation(fixture)
        val record = record(fixture)
        val retained = initial.withStringConstructions(listOf(record)).constructionRefined()
        assertEquals(0, retained.outputCount)
        assertEquals(0, retained.receipts.single().returnedResults.value)
        assertTrue(retained.transfers.isEmpty())
        assertTrue(retained.retainedBytes > initial.retainedBytes)
        val encoded =
            Json.encodeToJsonElement(
                    ImpactStringConstructionDocument.serializer(),
                    record.impactDocument().constructionRefined(),
                )
                .jsonObject
        assertEquals(setOf("source", "templateRange", "entryRange", "inputRange", "kind"), encoded.keys)
        assertEquals("CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT", encoded.getValue("kind").jsonPrimitive.content)
        val template = encoded.getValue("templateRange").jsonObject
        assertEquals(setOf("start", "end"), template.keys)
        assertEquals("28", template.getValue("start").jsonPrimitive.content)
        assertEquals("42", template.getValue("end").jsonPrimitive.content)
        val path =
            QueryImpactPath.fromEvidence(
                    fixture.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.SupportedDomainEnd.admit(retained).constructionRefined(),
                )
                .constructionRefined()
        val wire =
            Json.encodeToJsonElement(ImpactPathDocument.serializer(), path.impactDocument().constructionRefined())
                .jsonObject
        assertEquals(
            encoded,
            wire
                .getValue("terminal")
                .jsonObject
                .getValue("observation")
                .jsonObject
                .getValue("stringConstructions")
                .jsonArray
                .single(),
        )
    }

    @Test
    fun `null construction cannot escape its execution or change the selected source`() {
        val fixture = ImpactPathProjectionFixture()
        assertTrue(
            ValueStringConstruction.constantNullFromCompiler(
                fixture.producer,
                range(28, 201),
                range(29, 41),
                range(30, 40),
            ) is Refinement.Rejected
        )
        val other = ImpactPathProjectionFixture(generation = 2)
        val original = observation(fixture)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.SOURCE_MISMATCH),
            original.withStringConstructions(listOf(record(other))),
        )
        assertTrue(original.stringConstructions.isEmpty())
        assertEquals(fixture.producer, original.source)
    }

    @Test
    fun `explicit byte limit rejects the extra construction without changing the original grant`() {
        val fixture = ImpactPathProjectionFixture()
        val initial = observation(fixture)
        val tight = observation(fixture, initial.retainedBytes)
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED),
            tight.withStringConstructions(listOf(record(fixture))),
        )
        assertEquals(initial.retainedBytes, tight.domain.budget.returnedBytes.value)
        assertTrue(tight.stringConstructions.isEmpty())
    }

    @Test
    fun `every selected conversion origin keeps its canonical identity and encoded failure evidence`() {
        val fixture = ImpactPathProjectionFixture()
        val signature =
            CanonicalCompilerSignature.function("kotlin.Any.toString", null, emptyList(), emptyList(), 0)
                .constructionRefined() as CanonicalCompilerSignature.Function
        for (origin in ValueStringConversionOrigin.entries) {
            val selection = ValueStringConversionSelection.fromCompiler(signature, origin).constructionRefined()
            val obligation =
                ValueFlowObligation.fromStringConversion(
                        fixture.producer,
                        ValueFlowUnsupportedCause.EXTERNAL_CALL,
                        selection,
                        range(29, 41),
                        range(30, 40),
                    )
                    .constructionRefined()
            val document = obligation.evidence.impactDocument().constructionRefined()
            val encoded =
                Json.encodeToJsonElement(ImpactFlowObligationEvidenceDocument.serializer(), document).jsonObject
            assertEquals("STRING_CONVERSION", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(origin.name, encoded.getValue("origin").jsonPrimitive.content)
            assertEquals(selection.compilerIdentity.value, encoded.getValue("compilerIdentity").jsonPrimitive.content)
            assertEquals(signature.canonicalEncoding().value, encoded.getValue("signature").jsonPrimitive.content)
        }
    }

    private fun record(fixture: ImpactPathProjectionFixture) =
        ValueStringConstruction.constantNullFromCompiler(fixture.producer, range(28, 42), range(29, 41), range(30, 40))
            .constructionRefined()

    private fun observation(fixture: ImpactPathProjectionFixture, bytes: Long = 1_000_000): ValueFlowStep {
        val budget =
            RelationBudget(
                ResourceBudget(
                    ResultLimit.parse(8).constructionRefined(),
                    WorkUnitLimit.parse(100).constructionRefined(),
                    ElapsedTimeLimitMillis.parse(1000).constructionRefined(),
                ),
                RelationByteLimit.parse(bytes).constructionRefined(),
            )
        val domain =
            RelationRequest.start(
                fixture.owner,
                RelationMeaning.References,
                budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        return ValueFlowStep.fromCompiler(
                source = fixture.producer,
                transfers = emptyList(),
                obligations = emptyList(),
                terminal = ValueFlowTerminal.SupportedDomainExhausted,
                domain = domain,
                examinedWorkUnits = RelationWorkCount.parse(1).constructionRefined(),
            )
            .constructionRefined()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).constructionRefined()
}

private fun <T, F> Refinement<T, F>.constructionRefined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid construction fixture: $failure")
    }
