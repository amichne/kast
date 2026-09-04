package io.github.amichne.kast.cli.broker.provider

internal object KastObserverFixtures {
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
        """.trimIndent()

    val symbolInspection =
        """
        {
          "status": "completed",
          "document": {
            "operation": "symbol.inspect",
            "status": "complete",
            "symbol": {
              "selector": "exact:v2:opaque",
              "kind": "classlike",
              "name": "EventConsumer",
              "qualifiedIdentity": "com.aexp.mobile.one.streaming.events.core.EventConsumer",
              "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
              "range": {"startInclusive": 17, "endExclusive": 140},
              "compilerEvidence": {
                "identity": "canonical-signature-sha256-v1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "signature": {
                  "type": "class-like",
                  "qualifiedIdentity": "com.aexp.mobile.one.streaming.events.core.EventConsumer"
                }
              }
            }
          }
        }
        """.trimIndent()

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
        """.trimIndent()

    val semanticQuery =
        """
        {
          "status": "completed",
          "document": {
            "operation": "relation.read",
            "status": "complete",
            "relations": [
              {
                "meaning": "callers",
                "source": {
                  "selector": "exact:v2:checkout-service",
                  "kind": "classlike",
                  "name": "CheckoutService",
                  "qualifiedIdentity": "sample.checkout.CheckoutService",
                  "file": "checkout/core/src/main/kotlin/sample/CheckoutService.kt",
                  "range": {"startInclusive": 20, "endExclusive": 180},
                  "compilerEvidence": {
                    "identity": "canonical-signature-sha256-v1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "signature": {"type": "class-like", "qualifiedIdentity": "sample.checkout.CheckoutService"}
                  }
                },
                "target": {
                  "selector": "exact:v2:event-consumer",
                  "kind": "classlike",
                  "name": "EventConsumer",
                  "qualifiedIdentity": "sample.events.EventConsumer",
                  "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                  "range": {"startInclusive": 12, "endExclusive": 140},
                  "compilerEvidence": {
                    "identity": "canonical-signature-sha256-v1|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "signature": {"type": "class-like", "qualifiedIdentity": "sample.events.EventConsumer"}
                  }
                },
                "occurrence": {
                  "candidateSelector": "candidate:v2:checkout-call",
                  "file": "checkout/core/src/main/kotlin/sample/CheckoutService.kt",
                  "range": {"startInclusive": 88, "endExclusive": 101}
                },
                "provenance": "k2-authored-source",
                "coverage": "exact-compiler-confirmed"
              },
              {
                "meaning": "callers",
                "source": {
                  "selector": "exact:v2:audit-sink",
                  "kind": "function",
                  "name": "recordEvent",
                  "qualifiedIdentity": "sample.audit.AuditSink.recordEvent",
                  "file": "audit/src/main/kotlin/sample/AuditSink.kt",
                  "range": {"startInclusive": 30, "endExclusive": 96},
                  "compilerEvidence": {
                    "identity": "canonical-signature-sha256-v1|cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "signature": {"type": "function", "qualifiedIdentity": "sample.audit.AuditSink.recordEvent"}
                  }
                },
                "target": {
                  "selector": "exact:v2:event-consumer",
                  "kind": "classlike",
                  "name": "EventConsumer",
                  "qualifiedIdentity": "sample.events.EventConsumer",
                  "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                  "range": {"startInclusive": 12, "endExclusive": 140},
                  "compilerEvidence": {
                    "identity": "canonical-signature-sha256-v1|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "signature": {"type": "class-like", "qualifiedIdentity": "sample.events.EventConsumer"}
                  }
                },
                "occurrence": {
                  "candidateSelector": "candidate:v2:audit-call",
                  "file": "audit/src/main/kotlin/sample/AuditSink.kt",
                  "range": {"startInclusive": 62, "endExclusive": 75}
                },
                "provenance": "k2-authored-source",
                "coverage": "exact-compiler-confirmed"
              }
            ]
          }
        }
        """.trimIndent()

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
        """.trimIndent()
}
