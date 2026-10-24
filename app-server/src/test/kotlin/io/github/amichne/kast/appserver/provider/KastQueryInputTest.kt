package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicToolCheckDiagnostics
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolEmit
import io.github.amichne.kast.appserver.query.PublicToolExecutionBudget
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.query.PublicToolQuerySymbols
import io.github.amichne.kast.appserver.query.PublicToolReferenceSource
import io.github.amichne.kast.appserver.query.PublicToolRunAction
import io.github.amichne.kast.appserver.query.explanation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KastQueryInputTest {
    @Test
    fun `retired canonical query route rejects admitted current tool syntax`() {
        val name = (ProtocolText.parse("Order") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(name)) as Refinement.Refined).value
        val raw =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolRunAction(PublicToolReferenceSource(refs), null, PublicToolEmit())),
            )
        val admitted =
            when (val result = PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS).admit(raw)) {
                is Validation.Validated -> result.value
                is Validation.Rejected -> error("Current tool fixture must satisfy its schema")
            }

        val rejection =
            assertInstanceOf(Validation.Rejected::class.java, admitKastInput(CanonicalOperation.QUERY_RUN, admitted))
        assertEquals(KastToolInputFailure.SchemaMismatch, rejection.failures.first())
    }

    @Test
    fun `facade input preserves a cross field constraint and actionable guidance`() {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val raw =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        source = PublicToolReferenceSource(refs),
                        terminalStep = PublicToolEmit(),
                        executionBudget = PublicToolExecutionBudget(maxWorkUnits = 1),
                    )
                )
            )
        val admitted =
            when (val result = PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS).admit(raw)) {
                is Validation.Validated -> result.value
                is Validation.Rejected -> error("Cross-field constraint fixture must satisfy the public schema")
            }
        val rejection =
            assertInstanceOf(
                Validation.Rejected::class.java,
                admitKastInput(
                    CanonicalOperation.QUERY_RUN,
                    admitted,
                    AgentToolInputBinding.Facade(PublicToolIdentity.QUERY_SYMBOLS),
                ),
            )
        val facade = assertInstanceOf(KastToolInputFailure.Facade::class.java, rejection.failures.single())
        val expected = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) as Refinement.Rejected
        assertEquals(expected.failure, facade.reason)
        assertInstanceOf(PublicToolInputFailure.Constraint::class.java, facade.reason)
        assertEquals(
            "executionMode, executionBudget.maxWorkUnits: " +
                "RUNNER_CONTROLLED does not accept maxWorkUnits; " +
                "remove executionBudget.maxWorkUnits or select BOUNDED.",
            facade.reason.explanation(),
        )
    }

    @Test
    fun `diagnostics require the bound public tool presentation`() {
        val path = (ProtocolText.parse("src/Main.kt") as Refinement.Refined).value
        val raw =
            Json.encodeToJsonElement(
                PublicToolCheckDiagnostics.serializer(),
                PublicToolCheckDiagnostics(path, null),
            )
        val admitted =
            when (val result = PublicToolContract.schema(PublicToolIdentity.CHECK_DIAGNOSTICS).admit(raw)) {
                is Validation.Validated -> result.value
                is Validation.Rejected -> error("Diagnostics fixture must satisfy its schema")
            }

        val canonical =
            assertInstanceOf(
                Validation.Rejected::class.java,
                admitKastInput(CanonicalOperation.DIAGNOSTIC_CHECK, admitted),
            )
        assertEquals(KastToolInputFailure.SchemaMismatch, canonical.failures.first())
        val facade =
            admitKastInput(
                CanonicalOperation.DIAGNOSTIC_CHECK,
                admitted,
                AgentToolInputBinding.Facade(PublicToolIdentity.CHECK_DIAGNOSTICS),
            )
        assertInstanceOf(Validation.Validated::class.java, facade)
    }
}
