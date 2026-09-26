package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object KastObserverFixtures {
    val changePlan =
        """
        {
          "status": "completed",
          "document": {
            "operation": "change.plan",
            "status": "complete",
            "planIdentity": "plan:opaque",
            "changes": [{
              "path": "cli/src/main/kotlin/sample/EventConsumer.kt",
              "kind": "update",
              "diff": "@@ class EventConsumer @@\n-    fun consume() = old()\n+    fun consume() = new()"
            }]
          }
        }
        """
            .trimIndent()

    val changeApply =
        """
        {
          "status": "completed",
          "document": {
            "operation": "change.apply",
            "status": "complete",
            "receiptIdentity": "receipt:opaque",
            "changes": [{
              "path": "cli/src/main/kotlin/sample/EventConsumer.kt",
              "kind": "update",
              "diff": "@@ class EventConsumer @@\n-    fun consume() = old()\n+    fun consume() = new()"
            }]
          }
        }
        """
            .trimIndent()

    val changeRecover =
        """
        {
          "status": "completed",
          "document": {
            "operation": "change.recover",
            "status": "complete",
            "state": "rolled-back"
          }
        }
        """
            .trimIndent()

    val diagnosticCheck =
        """
        {
          "status": "completed",
          "document": {
            "operation": "diagnostic.check",
            "status": "complete",
            "diagnostics": [{
              "severity": "warning",
              "code": "UNUSED_PARAMETER",
              "message": "Parameter event is never used",
              "location": {
                "candidateSelector": "candidate:v2:hidden",
                "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                "range": {"startInclusive": 72, "endExclusive": 77}
              }
            }]
          }
        }
        """
            .trimIndent()

    val symbolDiscovery =
        """
        {
          "status": "completed",
          "document": {
            "operation": "symbol.discover",
            "status": "complete",
            "items": [{
              "type": "declaration",
              "candidateSelector": "candidate:v2:opaque",
              "kind": "class",
              "name": "EventConsumer",
              "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
              "offset": 17
            }]
          }
        }
        """
            .trimIndent()

    val symbolInspection = symbolInspectionObserverFixture()

    val sourceRead =
        """
        {
          "status": "completed",
          "document": {
            "operation": "source.read",
            "status": "complete",
            "snapshot": {
              "canonicalRoot": "/workspace",
              "generation": 42,
              "sourceState": "sha256:hidden",
              "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
              "textIdentity": "sha256:hidden",
              "coordinateUnit": "utf16-code-unit",
              "length": 154
            },
            "region": {
              "kind": "declaration",
              "selection": {
                "selector": "source-selector-v1:opaque",
                "range": {"startInclusive": 17, "endExclusive": 154}
              }
            },
            "entities": [],
            "text": {
              "type": "returned",
              "selection": {
                "selector": "source-selector-v1:opaque",
                "range": {"startInclusive": 17, "endExclusive": 154}
              },
              "text": "class EventConsumer(\n    private val source: EventSource,\n) {\n    fun consume(event: Event) = source.publish(event)\n}"
            }
          }
        }
        """
            .trimIndent()

    val queryOccurrences = queryOccurrenceOutcome(RelationKindDocument.CALLERS, qualified = false)
    val qualifiedQueryOccurrences = queryOccurrenceOutcome(RelationKindDocument.CALLERS, qualified = true)
    val mixedQueryOccurrences = queryOccurrenceOutcome(RelationKindDocument.CALLEES, qualified = false)
    val emptyQuery = queryOutcome(emptyList(), qualified = false)

    val impactAnalysis =
        """
        {
          "status": "completed",
          "document": {
            "operation": "traversal.run",
            "status": "complete",
            "graph": {
              "snapshot": {"canonicalRoot": "/workspace", "generation": 42},
              "nodes": [
                {
                  "id": 0,
                  "selector": "exact:v2:event-consumer",
                  "kind": "classlike",
                  "name": "EventConsumer",
                  "qualifiedIdentity": "sample.events.EventConsumer",
                  "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                  "range": {"startInclusive": 12, "endExclusive": 140},
                  "proof": 0
                },
                {
                  "id": 1,
                  "selector": "exact:v2:checkout-service",
                  "kind": "classlike",
                  "name": "CheckoutService",
                  "qualifiedIdentity": "sample.checkout.CheckoutService",
                  "file": "checkout/core/src/main/kotlin/sample/CheckoutService.kt",
                  "range": {"startInclusive": 20, "endExclusive": 180},
                  "proof": 1
                },
                {
                  "id": 2,
                  "selector": "exact:v2:audit-sink",
                  "kind": "function",
                  "name": "recordEvent",
                  "qualifiedIdentity": "sample.audit.AuditSink.recordEvent",
                  "file": "audit/src/main/kotlin/sample/AuditSink.kt",
                  "range": {"startInclusive": 30, "endExclusive": 96},
                  "proof": 2
                }
              ],
              "edges": [
                {
                  "depth": 1,
                  "meaning": "callers",
                  "source": 1,
                  "target": 0,
                  "occurrence": {
                    "candidateSelector": "candidate:v2:checkout-call",
                    "file": "checkout/core/src/main/kotlin/sample/CheckoutService.kt",
                    "range": {"startInclusive": 88, "endExclusive": 101}
                  },
                  "provenance": "k2-authored-source",
                  "coverage": "exact-compiler-confirmed"
                },
                {
                  "depth": 2,
                  "meaning": "callers",
                  "source": 2,
                  "target": 1,
                  "occurrence": {
                    "candidateSelector": "candidate:v2:audit-call",
                    "file": "audit/src/main/kotlin/sample/AuditSink.kt",
                    "range": {"startInclusive": 62, "endExclusive": 75}
                  },
                  "provenance": "k2-authored-source",
                  "coverage": "exact-compiler-confirmed"
                }
              ],
              "proofs": [
                {"id": 0, "identity": "canonical-signature-sha256-v1|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
                {"id": 1, "identity": "canonical-signature-sha256-v1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                {"id": 2, "identity": "canonical-signature-sha256-v1|cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
              ]
            }
          }
        }
        """
            .trimIndent()

    private fun queryOccurrenceOutcome(firstMeaning: RelationKindDocument, qualified: Boolean): String {
        val items: List<QueryResultItemDocument> =
            queryOccurrenceRelations(firstMeaning).map { fact ->
                QueryResultItemDocument.Occurrence(QueryReferenceDocument.ExactSymbol(fact.source.selector), fact)
            }
        return queryOutcome(items, qualified)
    }

    private fun queryOutcome(items: List<QueryResultItemDocument>, qualified: Boolean): String {
        val result = QueryRunResult(bounded(items), bounded(emptyList()), bounded(emptyList()))
        val envelope =
            EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, EvidenceGeneration.parse(17).required(), result)
        val outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> =
            if (qualified) {
                OperationOutcome.Qualified(
                    envelope,
                    QueryRunQualification.create(
                            QueryKnownMinimum.parse(2).required(),
                            listOf(QueryLimitationDocument.RELATION_INCOMPLETE),
                            QueryQualifiedProgressDocument.TerminalIncomplete(
                                QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                            ),
                        )
                        .required(),
                )
            } else OperationOutcome.Complete(envelope)
        val encoded =
            when (val projected = CanonicalQueryCliDocuments.project(outcome)) {
                is ProjectedOperationOutcome.Complete -> projected.document
                is ProjectedOperationOutcome.Qualified -> projected.document
                is ProjectedOperationOutcome.Rejected -> error("Successful observer fixture projected as rejected")
            }
        // The provider envelope defines its canonical operation document as an opaque payload.
        return invocationJson.encodeToString(KastCompletedDocument(Json.parseToJsonElement(encoded.value)))
    }

    private fun queryOccurrenceRelations(firstMeaning: RelationKindDocument): List<RelationFactDocument> {
        val checkout =
            symbol(
                "CheckoutService",
                "sample.checkout.CheckoutService",
                "checkout/core/src/main/kotlin/sample/CheckoutService.kt",
                "exact:v2:checkout-service",
                20,
                180,
            )
        val audit =
            symbol(
                "recordEvent",
                "sample.audit.AuditSink.recordEvent",
                "audit/src/main/kotlin/sample/AuditSink.kt",
                "exact:v2:audit-sink",
                30,
                96,
                function = true,
            )
        val consumer =
            symbol(
                "EventConsumer",
                "sample.events.EventConsumer",
                "events/core/src/main/kotlin/sample/EventConsumer.kt",
                "exact:v2:event-consumer",
                12,
                140,
            )
        val first = relation(firstMeaning, checkout, consumer, "candidate:v2:checkout-call", 88, 101)
        val second = relation(RelationKindDocument.CALLERS, audit, consumer, "candidate:v2:audit-call", 62, 75)
        return listOf(first, second)
    }

    private fun symbol(
        name: String,
        identity: String,
        file: String,
        selector: String,
        start: Int,
        end: Int,
        function: Boolean = false,
    ): SymbolDocument {
        val qualified = text(identity)
        val signature =
            if (function)
                CompilerSignatureDocument.Function(
                    qualified,
                    CompilerReceiverDocument.Absent,
                    bounded(emptyList()),
                    bounded(emptyList()),
                    CompilerTypeParameterCountDocument.parse(0).required(),
                )
            else CompilerSignatureDocument.ClassLike(qualified)
        return SymbolDocument.create(
                selector = text(selector),
                kind = if (function) SymbolKindDocument.FUNCTION else SymbolKindDocument.CLASSLIKE,
                name = text(name),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(qualified),
                file = text(file),
                range = range(start, end),
                compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).required(),
            )
            .required()
    }

    private fun relation(
        meaning: RelationKindDocument,
        source: SymbolDocument,
        target: SymbolDocument,
        candidate: String,
        start: Int,
        end: Int,
    ) =
        RelationFactDocument(
            meaning,
            source,
            target,
            RelationOccurrenceDocument(text(candidate), source.file, range(start, end)),
            RelationProvenanceDocument.K2_AUTHORED_SOURCE,
            RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
        )

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(ProtocolOffset.parse(start).required(), ProtocolOffset.parse(end).required())
            .required()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).required()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).required()

    private fun <Value, Failure> Refinement<Value, Failure>.required(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid typed observer fixture: $failure")
        }
}
