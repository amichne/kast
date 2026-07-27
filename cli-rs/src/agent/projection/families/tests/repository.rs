    #[test]
    fn context_diagnostics_and_ambiguity_remain_valid_projection_evidence() {
        for status in ["EMPTY", "QUALIFIED_EMPTY"] {
            let mut result = repository_result("context_relationship", status);
            result["unresolvedReferences"] = json!(["MissingSymbol"]);
            result["contextFindings"] = json!([{
                "type": "PUBLIC_API_DOCUMENTATION_GAP",
                "targetKey": "callable:sample.missing",
                "targetName": "missing",
                "trigger": "no linked documentation",
                "evidenceClass": "derived"
            }]);
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Compact,
            );

            assert!(projected.ok, "{status} rejected context diagnostics");
        }

        let mut ambiguous = repository_result("context_relationship", "AMBIGUOUS");
        ambiguous["ambiguousReferences"] = json!([{
            "reference": "parse",
            "candidates": [
                repository_node("callable:one.parse", "parse"),
                repository_node("callable:two.parse", "parse")
            ],
            "truncated": false
        }]);
        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), ambiguous),
            AgentResultView::Compact,
        );

        assert!(projected.ok, "rejected context ambiguity evidence");
    }

    #[test]
    fn repository_selected_views_preserve_closed_outcome() {
        for status in ["EMPTY", "QUALIFIED_EMPTY", "AMBIGUOUS"] {
            let mut result = repository_result("resolve", status);
            if status == "AMBIGUOUS" {
                result["candidates"] =
                    json!([repository_candidate("callable:one.answer", "answer")]);
            }
            let selected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Fields(vec![AgentRepositoryField::Continuation]),
            )
            .result
            .expect("selected repository projection");

            assert_eq!(selected["status"], status, "{selected}");
            assert_eq!(selected["intent"], "resolve", "{selected}");
            assert_eq!(selected["truncated"], false, "{selected}");
            if status == "QUALIFIED_EMPTY" {
                assert!(
                    selected["qualification"]
                        .as_str()
                        .is_some_and(|qualification| !qualification.is_empty()),
                    "{selected}"
                );
            } else {
                assert!(selected.get("qualification").is_none(), "{selected}");
            }
            assert!(selected.get("identities").is_none(), "{selected}");
            assert!(selected.get("coverage").is_none(), "{selected}");
        }
    }

    #[test]
    fn repository_projection_preserves_derivations_and_every_continuation() {
        let derivation = json!({
            "rule": "gradle_project_dependency",
            "facts": {"sourceProject": ":app", "targetProject": ":core"}
        });
        let mut relation = repository_context_relation();
        relation["derivation"] = derivation.clone();
        let mut context_result = repository_result("context_relationship", "ANSWERED");
        context_result["contextRelations"] = json!([relation]);

        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), context_result),
            AgentResultView::Compact,
        )
        .result
        .expect("context projection");

        assert_eq!(
            projected["context"]["relations"][0]["derivation"],
            derivation
        );

        let relationship_with_continuation = |continuation| {
            let mut relationship = repository_relationship();
            relationship["evidenceTruncated"] = json!(true);
            relationship["evidenceContinuation"] = json!(continuation);
            relationship
        };
        let mut truncated_result = repository_result("outgoing_impact", "ANSWERED");
        truncated_result["truncated"] = json!(true);
        truncated_result["continuation"] = json!("traversal-next");
        truncated_result["edges"] = json!([
            relationship_with_continuation("evidence-b"),
            relationship_with_continuation("evidence-a"),
            relationship_with_continuation("evidence-b")
        ]);

        let compact = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result.clone()),
            AgentResultView::Compact,
        )
        .result
        .expect("compact continuation projection");
        assert_eq!(compact["continuation"], "traversal-next");
        assert_eq!(
            compact["continuations"],
            json!(["evidence-a", "evidence-b"])
        );

        let selected = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result.clone()),
            AgentResultView::Fields(vec![AgentRepositoryField::Continuation]),
        )
        .result
        .expect("selected continuation projection");
        assert_eq!(selected["continuation"], "traversal-next");
        assert_eq!(
            selected["continuations"],
            json!(["evidence-a", "evidence-b"])
        );
        assert!(selected.get("relationships").is_none(), "{selected}");
        assert!(selected.get("edges").is_none(), "{selected}");

        truncated_result["truncated"] = json!(false);
        let invalid = project_repository_envelope(
            result_envelope("repository/query".to_string(), truncated_result),
            AgentResultView::Compact,
        );
        assert!(!invalid.ok, "untruncated result accepted continuations");
    }

    #[test]
    fn repository_projection_rejects_missing_query_syntax() {
        for query_syntax in ["NATURAL_LANGUAGE", "REGEX"] {
            let mut result = repository_result("resolve", "EMPTY");
            result["queryPlan"]["querySyntax"] = json!(query_syntax);
            let projected = project_repository_envelope(
                result_envelope("repository/query".to_string(), result),
                AgentResultView::Compact,
            );

            assert!(projected.ok, "{query_syntax} evidence was rejected");
            assert_eq!(
                projected.result.expect("projection")["querySyntax"],
                query_syntax
            );
        }

        let mut missing = repository_result("resolve", "EMPTY");
        missing["queryPlan"]
            .as_object_mut()
            .expect("query plan")
            .remove("querySyntax");
        let projected = project_repository_envelope(
            result_envelope("repository/query".to_string(), missing),
            AgentResultView::Compact,
        );

        assert!(!projected.ok, "missing query syntax evidence was accepted");
    }

    fn repository_result(intent: &str, status: &str) -> Value {
        let complete = status != "QUALIFIED_EMPTY";
        json!({
            "type": "KAST_REPOSITORY_QUERY_RESULT",
            "canonicalResultModel": true,
            "status": status,
            "question": "repository question",
            "intent": intent,
            "queryPlan": {"querySyntax": "NATURAL_LANGUAGE"},
            "workspaceIdentity": {"canonicalRoot": "/workspace"},
            "generation": 1,
            "inventoryGeneration": 1,
            "graphGeneration": 1,
            "scope": {},
            "coverage": {
                "complete": complete,
                "eligibleForCompleteNegative": complete,
                "total": 1,
                "indexed": usize::from(complete),
                "excluded": usize::from(!complete),
                "failed": 0,
                "stale": 0,
                "accounted": 1,
                "eligibilityProven": true,
                "pendingUpdateCount": 0
            },
            "appliedFilters": {},
            "bounds": {"depth": 2, "results": 10, "evidence": 1},
            "ordering": "canonicalKey ascending",
            "truncated": false,
            "qualification": (!complete).then_some("scope coverage is incomplete"),
            "schemaVersion": SCHEMA_VERSION,
            "identityCollisions": 0
        })
    }

    fn repository_node(canonical_key: &str, name: &str) -> Value {
        json!({
            "canonicalKey": canonical_key,
            "kind": "FUNCTION",
            "name": name,
            "fqName": format!("sample.{name}"),
            "path": "src/main/kotlin/sample.kt",
            "gradleProjects": ["gradle:/workspace#:app"],
            "sourceSets": ["main"],
            "declarationRange": {"startOffset": 0, "endOffset": 1, "line": 1}
        })
    }

    fn repository_candidate(canonical_key: &str, name: &str) -> Value {
        let mut candidate = repository_node(canonical_key, name);
        candidate["rank"] = json!(1);
        candidate["matchScore"] = json!(1);
        candidate
    }

    fn repository_relationship() -> Value {
        json!({
            "sourceKey": "callable:sample.source",
            "sourceName": "source",
            "targetKey": "callable:sample.target",
            "targetName": "target",
            "kind": "CALLS",
            "direction": "OUTGOING",
            "context": "CALL",
            "occurrenceCount": 1,
            "occurrences": [],
            "evidenceClass": "compiler",
            "evidenceTruncated": false
        })
    }

    fn repository_finding(node: Value) -> Value {
        json!({
            "rank": 1,
            "type": "ARCHITECTURE_HUB",
            "name": "hub",
            "summary": "hub finding",
            "projection": "call_graph",
            "metric": "degree",
            "trigger": {},
            "graphGeneration": 1,
            "representativeSymbols": [node.clone()],
            "supportingSubgraph": {
                "nodes": [node],
                "edges": [],
                "truncated": false
            },
            "relationComposition": {},
            "evidenceClass": "derived",
            "derivation": {},
            "relationTypes": [],
            "scope": {}
        })
    }

    fn repository_context_relation() -> Value {
        json!({
            "sourcePath": "README.md",
            "sourceKind": "markdown",
            "targetKey": "callable:sample.answer",
            "targetName": "answer",
            "kind": "DOCUMENTS",
            "direction": "INCOMING",
            "sourceLocation": {"line": 1, "startOffset": 0, "endOffset": 1},
            "evidenceClass": "extracted"
        })
    }

    fn command_envelope(method: &str, steps: Vec<Value>) -> AgentEnvelope {
        result_envelope(
            method.to_string(),
            json!({
                "type": "KAST_AGENT_COMMAND",
                "ok": true,
                "steps": steps,
                "issues": [],
                "schemaVersion": SCHEMA_VERSION
            }),
        )
    }

    fn diagnostic_location() -> Value {
        json!({
            "filePath": "/workspace/App.kt",
            "startOffset": 0,
            "endOffset": 1,
            "startLine": 1,
            "startColumn": 1,
            "preview": "x"
        })
    }
