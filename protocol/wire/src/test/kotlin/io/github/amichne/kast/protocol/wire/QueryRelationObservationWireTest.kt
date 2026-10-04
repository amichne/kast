package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFailure
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryRelationObservationWireTest {
    @Test
    fun `coverage encodes closed alternatives and rejects missing obligations`() {
        val limitations =
            QueryRelationLimitationsDocument.from(listOf(RelationLimitationDocument.PROVIDER_FAILURE)).refined()
        val cases =
            listOf(
                QueryRelationCoverageDocument.Exhausted to "relation-coverage-exhausted.json",
                QueryRelationCoverageDocument.Resumable(limitations) to "relation-coverage-resumable.json",
                QueryRelationCoverageDocument.TerminalIncomplete(limitations) to "relation-coverage-terminal.json",
            )
        for ((coverage, expected) in cases) {
            assertEquals(
                wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$expected")).readText()),
                wireJson.encodeToJsonElement(QueryRelationCoverageDocument.serializer(), coverage),
            )
        }
        for (resource in
            listOf(
                "relation-coverage-unknown.json",
                "relation-coverage-empty.json",
                "relation-coverage-unknown-limitation.json",
                "relation-coverage-duplicate-limitations.json",
            )) {
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(
                    QueryRelationCoverageDocument.serializer(),
                    checkNotNull(javaClass.getResource("/query/$resource")).readText(),
                )
            }
        }
    }

    @Test
    fun `domain fingerprint accepts only its exact digest format`() {
        assertEquals(
            Refinement.Rejected(QueryRelationDomainFailure.INVALID_FINGERPRINT),
            QueryRelationDomainFingerprint.parse("same display name"),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryRelationDomainFingerprint.serializer(), "\"same display name\"")
        }
    }

    @Test
    fun `proven scope exit encodes compiler and membership authorities without an invented target selector`() {
        val text = ProtocolText.parse("sample.Target").refined()
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(CompilerSignatureDocument.ClassLike(text)).refined()
        val range =
            SourceRangeDocument.create(ProtocolOffset.parse(4).refined(), ProtocolOffset.parse(18).refined()).refined()
        val document =
            QueryScopeExclusionDocument(
                RelationOccurrenceDocument(
                    ProtocolText.parse("candidate:call").refined(),
                    ProtocolText.parse("Seed.kt").refined(),
                    range,
                ),
                QueryExcludedCompilerTargetDocument.create(
                        ProtocolText.parse("Other.kt").refined(),
                        range,
                        ProtocolText.parse("Target").refined(),
                        SymbolKindDocument.CLASSLIKE,
                        evidence,
                    )
                    .refined(),
                QueryScopeExclusionReasonDocument.SOURCE_DOMAIN,
                QueryScopeMembershipAuthorityDocument.IMPORTED_MODEL_NATIVE_SCOPE,
                QueryRelationRequestedDomainDocument.SOURCE_DOMAIN,
                QueryRelationDomainDocument(
                    QuerySemanticScopeDocument.ExactFile(ProtocolText.parse("Seed.kt").refined()),
                    QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoverySourceSetsDocument.All,
                    null,
                    null,
                    BoundedProtocolList.create(emptyList<QueryDeclarationKindDocument>()).refined(),
                ),
                QueryRelationDomainFingerprint.parse("1".repeat(64)).refined(),
            )
        val wire = document.toWireDocument()
        val encoded = wireJson.encodeToJsonElement(QueryScopeExclusionWireDocument.serializer(), wire)
        assertEquals(
            wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/scope-exclusion.json")).readText()),
            encoded,
        )
        assertEquals(WireDocumentConversion.Converted(document), wire.toContract())
        val forgedKind = wire.copy(target = wire.target.copy(kind = SymbolKindWireDocument.FUNCTION))
        assertEquals(WireDocumentConversion.Rejected, forgedKind.toContract())
        val unknown = encoded.toString().replace("IMPORTED_MODEL_NATIVE_SCOPE", "UNKNOWN")
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryScopeExclusionWireDocument.serializer(), unknown)
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
