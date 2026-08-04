#[test]
fn repository_nodes_preserve_build_qualified_ownership() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_included_build_app(&fixture);
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (40, 'callable:includedOwnership', 2, NULL, 'FUNCTION',
              'includedOwnership', 'included.includedOwnership',
              'included.includedOwnership|-|||0', 0, 20, 1);
             INSERT INTO semantic_edge_occurrences
             (id, source_id, target_id, source_file_id, kind, context,
              resolved_target_id, start_offset, end_offset, line)
             VALUES
             (80, 3, 40, 1, 'REFERENCES', 'RETURN_TYPE', 40, 190, 195, 19);",
        )
        .expect("included-build semantic proof");
    std::fs::create_dir_all(workspace.join("included/app")).expect("included project directory");
    std::fs::write(
        workspace.join("included/app/build.gradle.kts"),
        "plugins { kotlin(\"jvm\") }\n",
    )
    .expect("included project build script");

    let resolve = |id: &str, canonical_key: &str| {
        rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": "Resolve the exact compiler identity.",
                    "intent": "resolve",
                    "canonicalKey": canonical_key,
                    "scope": {"language": "kotlin"},
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        )
        .1
    };
    let root = resolve("root-owner", "callable:semanticGraphOperation");
    let included = resolve("included-owner", "callable:includedOwnership");

    assert_eq!(
        root["result"]["nodes"][0]["gradleProjects"],
        serde_json::json!([".#:app"]),
        "{root:#}"
    );
    assert_eq!(
        root["result"]["nodes"][0]["sourceSets"],
        serde_json::json!([".#:app[main]"]),
        "{root:#}"
    );
    assert_eq!(
        included["result"]["nodes"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{included:#}"
    );
    assert_eq!(
        included["result"]["nodes"][0]["sourceSets"],
        serde_json::json!(["included#:app[main]"]),
        "{included:#}"
    );
    assert!(
        root["result"]["nodes"][0].get("module").is_none()
            && root["result"]["nodes"][0].get("sourceSet").is_none(),
        "{root:#}"
    );

    let discovery = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-discovery",
            "method": "repository/query",
            "params": {
                "question": "Resolve includedOwnership.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        discovery["result"]["candidates"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{discovery:#}"
    );

    let architecture = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-architecture",
            "method": "repository/query",
            "params": {
                "question": "Which Gradle ownership boundaries are crossed?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "MODULE_DEPENDENCIES"
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        architecture["result"]["findings"][0]["trigger"]["sourceModule"], ".#:app",
        "{architecture:#}"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["trigger"]["targetModule"], "included#:app",
        "{architecture:#}"
    );

    let context = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ownership-context",
            "method": "repository/query",
            "params": {
                "question": "Resolve includedOwnership context.",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["gradle"]},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1;
    assert_eq!(
        context["result"]["contextRelations"][0]["derivation"]["facts"]["gradleProject"],
        "included#:app",
        "{context:#}"
    );

    let compact = published_semantic_command(&home, &config_home, &workspace)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve includedOwnership exactly.",
            "--intent",
            "resolve",
            "--canonical-key",
            "callable:includedOwnership",
        ])
        .output()
        .expect("compact repository ownership");
    assert!(
        compact.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact.stdout),
        String::from_utf8_lossy(&compact.stderr)
    );
    let compact: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&compact.stdout).trim())
            .expect("compact repository ownership TOON");
    assert_eq!(
        compact["result"]["identities"][0]["gradleProjects"],
        serde_json::json!(["included#:app"]),
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["identities"][0]["sourceSets"],
        serde_json::json!(["included#:app[main]"]),
        "{compact:#}"
    );
}

#[test]
fn repository_discovery_ignores_unadmitted_neighbor_evidence() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_out_of_scope_repository_target(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE semantic_symbols
             SET name = 'uniqueNeighborEvidence'
             WHERE id = 10",
            [],
        )
        .expect("unique out-of-scope neighbor name");

    let resolve = |id: &str, scope: serde_json::Value| {
        rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": "Find the declaration related to unique neighbor evidence.",
                    "intent": "resolve",
                    "scope": scope,
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        )
        .1
    };

    let out_of_scope = resolve(
        "out-of-scope-neighbor",
        serde_json::json!({
            "language": "kotlin",
            "module": "app",
            "sourceSet": "main"
        }),
    );
    assert_eq!(out_of_scope["result"]["status"], "EMPTY", "{out_of_scope:#}");
    assert_eq!(
        out_of_scope["result"]["candidates"],
        serde_json::json!([]),
        "{out_of_scope:#}"
    );

    fixture
        .connection()
        .execute(
            "UPDATE file_manifest SET content_hash = ? WHERE filename = 'OutsideScope.kt'",
            params!["a".repeat(64)],
        )
        .expect("advance persisted neighbor hash");
    let stale = resolve(
        "stale-neighbor",
        serde_json::json!({"language": "kotlin"}),
    );
    assert_eq!(stale["result"]["status"], "QUALIFIED_EMPTY", "{stale:#}");
    assert_eq!(
        stale["result"]["candidates"],
        serde_json::json!([]),
        "{stale:#}"
    );
}
