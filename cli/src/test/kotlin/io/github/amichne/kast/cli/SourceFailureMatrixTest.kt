package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedSourceReadRejection
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceRequestExpectation
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceFailureMatrixTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }
    // This syntactically valid unknown handle is deliberately not treated as issued authority.
    private val unknown = "candidate:v4:" + "a".repeat(64)

    private fun request(format: SourceReadFormatDocument) =
        SourceReadRequest(
            SourceReadAnchorDocument.Candidate(text(unknown)),
            SourceRegionSelectionDocument.Anchor,
            SourceEntitySelectionDocument.None,
            SourceTextRequestDocument.Complete,
            SourceEntityLimitDocument.parse(10).value(),
            SourceTextByteLimitDocument.parse(4096).value(),
            SourceReadPageDocument.First,
            format = format,
        )

    @Test
    fun `physical source ingress retains fields rules bounds and origin in both formats`() {
        for (format in SourceReadFormatDocument.entries) {
            val valid = json.encodeToString(SourceReadRequest.serializer(), request(format))
            val cases = malformedCases(valid)
            for ((raw, path, rule) in cases) {
                val parsed =
                    commandGraphFactory().parse(listOf("source", "read"), CliRequestDocumentInput.Provided(raw))
                val failure =
                    (parsed as CliCommandParsing.SourceRejected).failure as SourceReadFailureDetail.RequestRejected
                assertEquals(SourceRequestField(path), failure.field)
                assertEquals(rule, failure.reason)
                val output = CanonicalSourceReadCliDocuments.project(OperationOutcome.Rejected(failure)).document()
                LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.SOURCE_READ, output)
                assertEquals("correct_request", output.getValue("next_action").jsonPrimitive.content)
                assertEquals(
                    "request-rejected",
                    output.getValue("reason").jsonObject.getValue("type").jsonPrimitive.content,
                )
                assertFalse(output.toString().contains(unknown))
                assertTrue(output.toString().length < 1024)
            }
        }
    }

    @Test
    fun `wrong family and long malformed tokens retain reference origin without leaking input`() {
        val encoded = json.encodeToString(SourceReadRequest.serializer(), request(SourceReadFormatDocument.COMPACT))
        for ((raw, expected) in
            listOf(
                encoded.replace("\"type\":\"candidate\"", "\"type\":\"symbol\"") to SourceReferenceFailure.WRONG_FAMILY,
                encoded.replace(unknown, "secret-source".repeat(100000)) to SourceReferenceFailure.MALFORMED,
            )) {
            val result =
                commandGraphFactory().parse(listOf("source", "read"), CliRequestDocumentInput.Provided(raw))
                    as CliCommandParsing.SourceRejected
            val failure = result.failure as SourceReadFailureDetail.ReferenceRejected
            assertEquals(expected, failure.reason)
            val document = CanonicalSourceReadCliDocuments.project(OperationOutcome.Rejected(failure)).document()
            assertTrue(document.toString().length < 500)
            assertFalse(document.toString().contains("secret-source"))
        }
    }

    @Test
    fun `all precise causes survive admitted wire and CLI layers with finite recovery`() {
        val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
        val causes: List<SourceReadCause> =
            SourceInternalObligation.entries.map { SourceReadFailureDetail.InternalContractFailure(it) } +
                SourceReferenceFailure.entries.map {
                    SourceReadFailureDetail.ReferenceRejected(SourceReferenceRole.SYMBOL, it)
                } +
                SourceRequestRule.entries.map {
                    SourceReadFailureDetail.RequestRejected(SourceRequestField(SourceRequestPath.DOCUMENT), it)
                }
        for (cause in causes) {
            val outcome = OperationOutcome.Rejected(AdmittedSourceReadRejection(cause, report))
            val wire =
                (CanonicalOperationWireBindings.sourceRead.encodeOutcome(outcome) as WireEncoding.Encoded).document
            assertEquals(WireDecoding.Decoded(outcome), CanonicalOperationWireBindings.sourceRead.decodeOutcome(wire))
            val cli = CanonicalSourceReadCliDocuments.project(outcome).document()
            LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.SOURCE_READ, cli)
            val reason = cli.getValue("reason").jsonObject
            assertEquals(json.encodeToJsonElement(SourceReadCause.serializer(), cause), reason)
            assertEquals(
                cause.recoveryAction(),
                json.decodeFromJsonElement(ReadRecoveryAction.serializer(), cli.getValue("next_action")),
            )
            LiveReadOutputSchemaTest()
                .assertRejects(
                    CanonicalOperation.SOURCE_READ,
                    cli.changed("reason", reason.changed("type", JsonPrimitive("unknown-failure"))),
                )
            assertTrue(
                CanonicalOperationWireBindings.sourceRead.decodeOutcome(
                    wire.replace("internal-contract-failure", "unknown-failure")
                ) is WireDecoding.Rejected || cause !is SourceReadFailureDetail.InternalContractFailure
            )
        }
    }

    @Test
    fun `schema admitted unordered declaration constraints remain accepted`() {
        val requested =
            request(SourceReadFormatDocument.EXPANDED)
                .copy(
                    entities =
                        SourceEntitySelectionDocument.Matching(
                            SourceContainmentDocument.DIRECT,
                            listOf(
                                SourceEntityFilterDocument.Declarations(
                                    listOf(
                                        SourceDeclarationKindDocument.FUNCTION,
                                        SourceDeclarationKindDocument.CLASSLIKE,
                                    ),
                                    SourceVisibilitySelectionDocument.Exact(
                                        listOf(
                                            SourceDeclarationVisibilityDocument.PRIVATE,
                                            SourceDeclarationVisibilityDocument.PUBLIC,
                                        )
                                    ),
                                )
                            ),
                        )
                )
        assertEquals(
            requested,
            json.decodeFromString(
                SourceReadRequest.serializer(),
                json.encodeToString(SourceReadRequest.serializer(), requested),
            ),
        )
    }

    @Test
    fun `invalid declaration enum retains both collection positions`() {
        val typed =
            request(SourceReadFormatDocument.COMPACT)
                .copy(
                    entities =
                        SourceEntitySelectionDocument.Matching(
                            SourceContainmentDocument.DIRECT,
                            listOf(
                                SourceEntityFilterDocument.Declarations(
                                    listOf(
                                        SourceDeclarationKindDocument.CLASSLIKE,
                                        SourceDeclarationKindDocument.FUNCTION,
                                    ),
                                    SourceVisibilitySelectionDocument.Any,
                                )
                            ),
                        )
                )
        val malformed =
            json.encodeToString(SourceReadRequest.serializer(), typed).replace("\"function\"", "\"unknown-kind\"")
        val rejected =
            commandGraphFactory().parse(listOf("source", "read"), CliRequestDocumentInput.Provided(malformed))
                as CliCommandParsing.SourceRejected
        val cause = rejected.failure as SourceReadFailureDetail.RequestRejected
        assertEquals(SourceRequestPath.DECLARATION_KINDS, cause.field.path)
        assertEquals(0, cause.field.index?.value)
        assertEquals(1, cause.field.elementIndex?.value)
        assertEquals(SourceRequestRule.DECLARATION_KIND, cause.reason)
        assertEquals(
            SourceRequestExpectation.Alternatives(
                listOf("classlike", "constructor", "function", "property", "type-alias")
            ),
            cause.expected,
        )
    }

    @Test
    fun `source budget failures retain exact supplied field and accepted bound`() {
        val valid =
            json.encodeToString(
                SourceReadRequest.serializer(),
                request(SourceReadFormatDocument.COMPACT).copy(executionBudget = ExecutionBudgetDocument()),
            )
        for ((field, path) in
            listOf(
                "max_elapsed_ms" to SourceRequestPath.BUDGET_ELAPSED,
                "max_work_units" to SourceRequestPath.BUDGET_WORK,
                "max_results" to SourceRequestPath.BUDGET_RESULTS,
                "max_returned_bytes" to SourceRequestPath.BUDGET_BYTES,
            )) {
            // Deliberately invalid supplied budget; surrounding request is a serialized DTO.
            val malformed = valid.replace("\"execution_budget\":{}", "\"execution_budget\":{\"$field\":0}")
            assertFalse(valid == malformed)
            val result =
                commandGraphFactory().parse(listOf("source", "read"), CliRequestDocumentInput.Provided(malformed))
                    as CliCommandParsing.SourceRejected
            val cause = result.failure as SourceReadFailureDetail.RequestRejected
            assertEquals(path, cause.field.path)
            assertEquals(
                SourceRequestExpectation.Bounds(
                    1,
                    if (path == SourceRequestPath.BUDGET_RESULTS) Int.MAX_VALUE.toLong() else Long.MAX_VALUE,
                ),
                cause.expected,
            )
        }
    }

    private fun ProjectedOperationOutcome.document(): JsonObject =
        Json.parseToJsonElement((this as ProjectedOperationOutcome.Rejected).document.value).jsonObject

    private fun JsonObject.changed(key: String, value: JsonElement): JsonObject =
        with(LiveReadOutputSchemaTest()) { this@changed.with(key, value) }

    private fun commandGraphFactory(): io.github.amichne.kast.cli.command.CliCommandGraphFactory =
        when (
            val built =
                io.github.amichne.kast.cli.command.CliCommandGraphFactory.create(
                    io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers()
                )
        ) {
            is io.github.amichne.kast.cli.command.CliCommandGraphConstruction.Created -> built.factory
            is io.github.amichne.kast.cli.command.CliCommandGraphConstruction.Rejected ->
                error("Command graph rejected")
        }

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun <T, F> Refinement<T, F>.value(): T = (this as Refinement.Refined).value
}

private fun malformedCases(valid: String): List<Triple<String, SourceRequestPath, SourceRequestRule>> =
    listOf(
        missingAnchorType(valid),
        Triple(
            valid.replace("\"type\":\"candidate\"", "\"type\":\"unknown\""),
            SourceRequestPath.ANCHOR_TYPE,
            SourceRequestRule.ANCHOR_TYPE,
        ),
        Triple(
            valid.replace("\"entityLimit\":10", "\"entityLimit\":\"ten\""),
            SourceRequestPath.ENTITY_LIMIT,
            SourceRequestRule.INTEGER_REQUIRED,
        ),
        Triple(
            valid.replace("\"entityLimit\":10", "\"entityLimit\":1001"),
            SourceRequestPath.ENTITY_LIMIT,
            SourceRequestRule.ENTITY_COUNT,
        ),
        Triple(
            valid.replace("\"textByteLimit\":4096", "\"textByteLimit\":0"),
            SourceRequestPath.TEXT_BYTE_LIMIT,
            SourceRequestRule.BYTE_COUNT,
        ),
        Triple(
            valid.replace(
                "\"text\":{\"type\":\"complete\"}",
                "\"text\":{\"type\":\"window\",\"beforeLines\":-1,\"afterLines\":0}",
            ),
            SourceRequestPath.BEFORE_LINES,
            SourceRequestRule.LINE_COUNT,
        ),
        Triple(
            valid.replace(
                "\"entities\":{\"type\":\"none\"}",
                "\"entities\":{\"type\":\"matching\",\"containment\":\"direct\",\"filters\":[]}",
            ),
            SourceRequestPath.FILTERS,
            SourceRequestRule.FILTER_COUNT,
        ),
        Triple(
            valid.replace(
                "\"region\":{\"type\":\"anchor\"}",
                "\"region\":{\"type\":\"body\",\"kind\":\"unknown\"}",
            ),
            SourceRequestPath.REGION_KIND,
            SourceRequestRule.BODY_KIND,
        ),
    )

private fun missingAnchorType(valid: String): Triple<String, SourceRequestPath, SourceRequestRule> =
    Triple(
        valid.replace("\"type\":\"candidate\",", ""),
        SourceRequestPath.ANCHOR_TYPE,
        SourceRequestRule.REQUIRED,
    )
