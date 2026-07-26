mod support;

use rusqlite::params;
use sha2::{Digest, Sha256};
use support::workspace_files::WorkspaceIndexFixture;
use support::*;

fn coverage_fixture() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    WorkspaceIndexFixture,
) {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let data = default_install_root(&home).join("state/data/workspaces");
    std::fs::create_dir_all(data.join("local")).expect("workspace data");
    std::fs::write(
        data.join("local-workspaces.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            workspace.display().to_string(): "repository-intelligence"
        }))
        .expect("workspace registry JSON"),
    )
    .expect("workspace registry");
    let mut sanitized = String::new();
    for character in workspace.display().to_string().chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized.push(character);
        } else if !sanitized.ends_with('-') {
            sanitized.push('-');
        }
    }
    let sanitized = sanitized
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    let database = data
        .join("local")
        .join(format!("{sanitized}--repository-intelligence"))
        .join("cache/source-index.db");
    let fixture = WorkspaceIndexFixture::at_database_path(&workspace, &database);
    fixture.seed_high_cardinality_sources(1);
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let path = "src/main/kotlin/sample/Source0000.kt";
    let content = std::fs::read(workspace.join(path)).expect("Kotlin source");
    fixture
        .connection()
        .execute_batch(
            "CREATE TABLE semantic_files (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                package_name TEXT,
                module_name TEXT,
                content_hash TEXT,
                refresh_status TEXT NOT NULL,
                diagnostics_json TEXT NOT NULL
            );",
        )
        .expect("semantic graph schema");
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_files(path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("semantic graph file");
    (temp, home, config_home, workspace, fixture)
}

fn rpc(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: serde_json::Value,
) -> (std::process::ExitStatus, serde_json::Value) {
    let output = rpc_output(home, config_home, workspace, "json", &request);
    let response = serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "rpc JSON: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

fn rpc_output(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    output_format: &str,
    request: &serde_json::Value,
) -> std::process::Output {
    kast(home, config_home)
        .args([
            "--output",
            output_format,
            "rpc",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--request",
            &request.to_string(),
        ])
        .output()
        .expect("rpc")
}

#[test]
fn rpc_exposes_generation_pinned_complete_graph_coverage() {
    let (_temp, home, config_home, workspace, _fixture) = coverage_fixture();
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "coverage",
        "method": "graph/coverage",
        "params": {
            "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"}
        }
    });

    let (status, response) = rpc(&home, &config_home, &workspace, request);

    assert!(status.success(), "{response:#}");
    assert_eq!(response["id"], "coverage");
    assert_eq!(response["result"]["generation"], 41);
    assert_eq!(response["result"]["inventoryGeneration"], 41);
    assert_eq!(response["result"]["graphGeneration"], 41);
    assert_eq!(response["result"]["coverage"]["total"], 1);
    assert_eq!(response["result"]["coverage"]["indexed"], 1);
    assert_eq!(response["result"]["coverage"]["excluded"], 0);
    assert_eq!(response["result"]["coverage"]["failed"], 0);
    assert_eq!(response["result"]["coverage"]["stale"], 0);
    assert_eq!(response["result"]["coverage"]["complete"], true);
    assert_eq!(
        response["result"]["coverage"]["eligibleForCompleteNegative"],
        true
    );
    assert_eq!(response["result"]["appliedFilters"]["module"], "app");
    assert_eq!(response["result"]["appliedFilters"]["sourceSet"], "main");
}

#[test]
fn repository_negative_answers_follow_coverage_state() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    let request = |scope: serde_json::Value| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "negative",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": scope,
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };

    let (status, complete) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{complete:#}");
    assert_eq!(complete["result"]["status"], "EMPTY");
    assert_eq!(complete["result"]["coverage"]["complete"], true);

    std::fs::write(
        workspace.join("src/main/kotlin/sample/Source0000.kt"),
        "package sample\nclass Changed\n",
    )
    .expect("stale source");
    let (status, stale) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{stale:#}");
    assert_eq!(stale["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(stale["result"]["coverage"]["stale"], 1);
    assert!(stale["result"]["qualification"].is_string());

    fixture
        .connection()
        .execute("DELETE FROM semantic_files", [])
        .expect("remove semantic graph file");
    let (status, failed) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{failed:#}");
    assert_eq!(failed["result"]["coverage"]["failed"], 1);
    assert_eq!(failed["result"]["coverage"]["complete"], false);
    let (_, failed_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "failed-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        failed_coverage["result"]["files"][0]["diagnostics"][0]["code"],
        "SEMANTIC_GRAPH_MISSING"
    );

    fixture
        .connection()
        .execute("DELETE FROM file_metadata", [])
        .expect("remove compilation ownership evidence");
    let (status, excluded) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{excluded:#}");
    assert_eq!(excluded["result"]["coverage"]["excluded"], 1);
    assert_eq!(excluded["result"]["coverage"]["failed"], 0);
    assert_eq!(excluded["result"]["coverage"]["eligibilityProven"], false);
    assert_eq!(excluded["result"]["coverage"]["complete"], false);
    let (_, excluded_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "excluded-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        excluded_coverage["result"]["files"][0]["reasonCode"],
        "SOURCE_INDEX_METADATA_UNAVAILABLE"
    );

    let (status, deliberate_partial) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({
            "language": "kotlin",
            "fixture": "incomplete-coverage"
        })),
    );
    assert!(status.success(), "{deliberate_partial:#}");
    assert_eq!(deliberate_partial["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(
        deliberate_partial["result"]["coverage"]["eligibleForCompleteNegative"],
        false
    );
}

#[test]
fn repository_paths_carry_exact_identity_occurrences_and_derivations() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    fixture
        .connection()
        .execute_batch(
            "
            CREATE TABLE semantic_types (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                classifier TEXT,
                nullability TEXT NOT NULL,
                debug_text TEXT NOT NULL,
                flexible_lower_id INTEGER,
                flexible_upper_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER
            );
            CREATE TABLE semantic_symbols (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                file_id INTEGER NOT NULL,
                owner_id INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                fq_name TEXT,
                signature TEXT,
                visibility TEXT NOT NULL DEFAULT 'PUBLIC',
                modality TEXT,
                origin TEXT NOT NULL DEFAULT 'SOURCE',
                is_expect INTEGER NOT NULL DEFAULT 0,
                is_actual INTEGER NOT NULL DEFAULT 0,
                is_override INTEGER NOT NULL DEFAULT 0,
                is_sealed INTEGER NOT NULL DEFAULT 0,
                is_delegated INTEGER NOT NULL DEFAULT 0,
                declared_type_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            );
            CREATE TABLE semantic_symbol_annotations (
                symbol_id INTEGER NOT NULL,
                annotation_name TEXT NOT NULL,
                PRIMARY KEY(symbol_id, annotation_name)
            );
            CREATE TABLE semantic_edge_occurrences (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                resolved_target_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            );
            INSERT INTO semantic_types
                (id, stable_key, kind, classifier, nullability, debug_text)
                VALUES (1, 'type:kotlin.String', 'CLASS', 'kotlin.String', 'NON_NULL', 'String');
            INSERT INTO semantic_symbols
                (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, return_type_id, start_offset, end_offset, line)
                VALUES
                (1, 'class:SemanticGraphSha256', 1, NULL, 'CLASS', 'SemanticGraphSha256', 'sample.SemanticGraphSha256', NULL, NULL, 0, 100, 1),
                (2, 'object:SemanticGraphSha256.Companion', 1, 1, 'OBJECT', 'Companion', 'sample.SemanticGraphSha256.Companion', NULL, NULL, 10, 90, 2),
                (3, 'callable:semanticGraphOperation', 1, NULL, 'FUNCTION', 'semanticGraphOperation', 'sample.semanticGraphOperation', 'sample.semanticGraphOperation|-|||0', NULL, 100, 200, 10),
                (4, 'callable:buildSemanticGraphSnapshot', 1, NULL, 'FUNCTION', 'buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot|-|||0', NULL, 210, 400, 20),
                (5, 'local:hash', 1, 4, 'PROPERTY', 'hash', NULL, NULL, NULL, 250, 300, 25),
                (6, 'callable:SemanticGraphSha256.parse', 1, 2, 'MEMBER_FUNCTION', 'parse', 'sample.SemanticGraphSha256.Companion.parse', 'sample.SemanticGraphSha256.Companion.parse|-||kotlin.String|0', 1, 40, 80, 4),
                (7, 'callable:calls', 1, NULL, 'FUNCTION', 'calls', 'sample.calls', 'sample.calls|-|||0', NULL, 410, 420, 41),
                (8, 'callable:other.parse', 1, NULL, 'FUNCTION', 'parse', 'sample.parse', 'sample.parse|-||kotlin.String|0', 1, 430, 440, 43);
            INSERT INTO semantic_edge_occurrences
                (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
                VALUES
                (1, 3, 4, 1, 'CALLS', 'CALL', 4, 150, 170, 15),
                (2, 4, 5, 1, 'CONTAINS', 'NONE', 5, 250, 300, 25),
                (3, 5, 6, 1, 'CALLS', 'CALL', 6, 270, 280, 27),
                (4, 5, 6, 1, 'CALLS', 'CALL', 6, 281, 290, 28),
                (5, 5, 6, 1, 'CALLS', 'CALL', 6, 291, 300, 29);
            ",
        )
        .expect("semantic graph facts");

    let (_, exact) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact",
            "method": "repository/query",
            "params": {
                "question": "Resolve SemanticGraphSha256.parse exactly.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact["result"]["status"], "ANSWERED", "{exact:#}");
    assert_eq!(exact["result"]["nodes"][0]["name"], "parse");
    assert_eq!(
        exact["result"]["nodes"][0]["ownerName"],
        "SemanticGraphSha256"
    );
    assert_eq!(
        exact["result"]["nodes"][0]["parameterTypes"],
        serde_json::json!(["kotlin.String"])
    );

    let (_, path) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "path",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 1}
            }
        }),
    );
    assert_eq!(path["result"]["status"], "ANSWERED", "{path:#}");
    assert!(path["result"]["paths"].as_array().is_some_and(|paths| {
        paths.iter().any(|path| {
            path["nodes"].as_array().is_some_and(|nodes| {
                nodes
                    .first()
                    .is_some_and(|node| node["name"] == "semanticGraphOperation")
                    && nodes.last().is_some_and(|node| node["name"] == "parse")
            })
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().all(|edge| {
            edge["occurrences"]
                .as_array()
                .is_some_and(|values| !values.is_empty())
                || edge["derivation"].is_object()
        })
    }));
    assert!(path["result"]["edges"].as_array().is_some_and(|edges| {
        edges.iter().any(|edge| {
            edge["evidenceClass"] == "compiler"
                && edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER"
        })
    }));
    let derived_edge = path["result"]["edges"]
        .as_array()
        .and_then(|edges| {
            edges
                .iter()
                .find(|edge| edge["derivation"]["rule"] == "LIFT_LOCAL_CALL_TO_CALLABLE_OWNER")
        })
        .expect("derived path edge");
    assert_eq!(derived_edge["occurrenceCount"], 3);
    assert_eq!(derived_edge["evidenceTruncated"], true);
    let continuation = derived_edge["evidenceContinuation"].clone();
    assert!(continuation.is_object());

    let (_, remaining_evidence) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "remaining-evidence",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 10},
                "evidenceContinuation": continuation
            }
        }),
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["occurrences"]
            .as_array()
            .map(Vec::len),
        Some(2),
        "{remaining_evidence:#}"
    );
    assert_eq!(
        remaining_evidence["result"]["edges"][0]["evidenceTruncated"],
        false
    );
    assert!(remaining_evidence["result"]["continuation"].is_null());

    let (_, discovery) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "discovery",
            "method": "repository/query",
            "params": {
                "question": "Find the function that builds a semantic graph snapshot.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(discovery["result"]["status"], "ANSWERED", "{discovery:#}");
    assert_eq!(discovery["result"]["queryPlan"]["discovery"], "LEXICAL");
    assert_eq!(
        discovery["result"]["candidates"][0]["name"],
        "buildSemanticGraphSnapshot"
    );
    assert!(
        discovery["result"]["candidates"][0]["matchReasons"]
            .as_array()
            .is_some_and(|reasons| !reasons.is_empty())
    );

    let canonical_key = discovery["result"]["candidates"][0]["canonicalKey"]
        .as_str()
        .expect("discovery candidate has canonical identity");
    let (_, exact_key) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact-key",
            "method": "repository/query",
            "params": {
                "question": "This prose must not affect exact-key lookup.",
                "intent": "resolve",
                "canonicalKey": canonical_key,
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact_key["result"]["status"], "ANSWERED", "{exact_key:#}");
    assert_eq!(exact_key["result"]["queryPlan"]["discovery"], "EXACT_KEY");
    assert_eq!(
        exact_key["result"]["selectedIdentity"],
        serde_json::Value::String(canonical_key.to_string())
    );
    assert_eq!(
        exact_key["result"]["candidates"].as_array().map(Vec::len),
        Some(1)
    );

    let (_, ambiguous) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ambiguous",
            "method": "repository/query",
            "params": {
                "question": "Resolve parse.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(ambiguous["result"]["status"], "AMBIGUOUS", "{ambiguous:#}");
    assert!(ambiguous["result"]["selectedIdentity"].is_null());
    assert!(
        ambiguous["result"]["candidates"]
            .as_array()
            .is_some_and(|candidates| candidates.len() == 2)
    );

    let (_, architecture) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "architecture",
            "method": "repository/query",
            "params": {
                "question": "Which internal declarations are incoming runtime call hubs?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "RUNTIME_CALLS",
                    "direction": "INCOMING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        architecture["result"]["status"], "ANSWERED",
        "{architecture:#}"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["type"],
        "HIGH_CENTRALITY_INTERNAL_IMPLEMENTATION"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["projection"],
        "RUNTIME_CALLS"
    );
    assert!(
        architecture["result"]["findings"][0]["supportingSubgraph"]["edges"]
            .as_array()
            .is_some_and(|edges| !edges.is_empty())
    );

    std::fs::create_dir_all(workspace.join("docs/explanation")).expect("context fixture directory");
    std::fs::write(
        workspace.join("docs/explanation/compiler-evidence.md"),
        "# Compiler evidence\n\nSemanticGraphSha256 has an exact compiler identity.\n",
    )
    .expect("context fixture document");
    let context_request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "context",
        "method": "repository/query",
        "params": {
            "question": "Which document explains SemanticGraphSha256?",
            "intent": "context_relationship",
            "scope": {"language": "kotlin", "sources": ["markdown"]},
            "limits": {"depth": 6, "results": 10, "evidence": 5}
        }
    });
    let (_, context) = rpc(&home, &config_home, &workspace, context_request.clone());
    assert_eq!(context["result"]["status"], "ANSWERED", "{context:#}");
    assert_eq!(
        context["result"]["canonicalResultModel"], true,
        "{context:#}"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["kind"],
        "DOCUMENTS"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["targetName"],
        "SemanticGraphSha256"
    );
    assert_eq!(
        context["result"]["contextRelations"][0]["evidenceClass"],
        "extracted"
    );
    let toon = rpc_output(&home, &config_home, &workspace, "toon", &context_request);
    assert!(toon.status.success());
    let toon_response: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&toon.stdout).trim())
            .expect("TOON repository response");
    for pointer in [
        "/result/canonicalResultModel",
        "/result/status",
        "/result/question",
        "/result/intent",
        "/result/contextRelations",
        "/result/nodes",
        "/result/evidenceClasses",
    ] {
        assert_eq!(
            toon_response.pointer(pointer),
            context.pointer(pointer),
            "{pointer}"
        );
    }
    let markdown = rpc_output(&home, &config_home, &workspace, "human", &context_request);
    assert!(markdown.status.success());
    let markdown = String::from_utf8_lossy(&markdown.stdout);
    assert!(
        markdown.contains("Kast repository intelligence"),
        "{markdown}"
    );
    assert!(
        markdown.contains("docs/explanation/compiler-evidence.md"),
        "{markdown}"
    );
    assert!(
        markdown.contains("Reproducible query descriptor"),
        "{markdown}"
    );

    std::fs::write(
        workspace.join("docs/explanation/compiler-evidence.md"),
        "# Compiler evidence\n\nThe compiler model lives under `src/main/kotlin/sample/`.\n",
    )
    .expect("path-only context document");
    let (_, inferred_target) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "path-only-context",
            "method": "repository/query",
            "params": {
                "question": "Which exact Kotlin model carries semantic graph hashing evidence?",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        inferred_target["result"]["status"], "ANSWERED",
        "{inferred_target:#}"
    );
    assert!(
        inferred_target["result"]["contextRelations"]
            .as_array()
            .is_some_and(|relations| relations.iter().any(|relation| {
                relation["sourcePath"] == "docs/explanation/compiler-evidence.md"
                    && relation["targetName"] == "SemanticGraphSha256"
                    && relation["kind"] == "DOCUMENTS"
                    && relation["evidenceClass"] == "extracted"
            })),
        "{inferred_target:#}"
    );

    let (_, wrong_direction) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "wrong-direction",
            "method": "repository/query",
            "params": {
                "question": "List outgoing CALLS made by SemanticGraphSha256.parse.",
                "intent": "outgoing_impact",
                "scope": {"language": "kotlin", "relations": ["CALLS"], "maxDepth": 1},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        wrong_direction["result"]["status"], "EMPTY",
        "{wrong_direction:#}"
    );
}
