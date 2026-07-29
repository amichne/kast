#[test]
fn graph_coverage_continuation_rejects_tampering_and_drift() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(3);

    let mut legacy = graph_coverage_page_request("legacy", None, 2);
    legacy["params"]["afterPath"] = serde_json::json!("src/main/kotlin/sample/Source0000.kt");
    let (legacy_status, legacy_response) = rpc(&home, &config_home, &workspace, legacy);
    assert!(!legacy_status.success(), "{legacy_response:#}");
    assert_eq!(
        legacy_response["code"], "INVALID_GRAPH_COVERAGE_REQUEST",
        "{legacy_response:#}"
    );
    assert!(
        legacy_response["message"]
            .as_str()
            .is_some_and(|message| message.contains("unknown field")),
        "{legacy_response:#}"
    );

    let (first_status, first) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("first", None, 2),
    );
    assert!(first_status.success(), "{first:#}");
    let first_paths = first["result"]["files"]
        .as_array()
        .expect("first coverage files")
        .iter()
        .map(|file| file["path"].as_str().expect("coverage path").to_owned())
        .collect::<Vec<_>>();
    assert_eq!(
        first_paths,
        [
            "src/main/kotlin/sample/Source0000.kt",
            "src/main/kotlin/sample/Source0001.kt"
        ]
    );
    assert_eq!(first["result"]["truncated"], true);
    assert!(first["result"].get("nextAfterPath").is_none(), "{first:#}");
    let continuation = first["result"]["continuation"]
        .as_str()
        .expect("coverage continuation")
        .to_owned();

    let mut tampered = continuation.clone();
    let final_character = tampered.pop().expect("continuation character");
    tampered.push(if final_character == '0' { '1' } else { '0' });
    for (id, request) in [
        (
            "tampered",
            graph_coverage_page_request("tampered", Some(&tampered), 2),
        ),
        (
            "limit-mismatch",
            graph_coverage_page_request("limit-mismatch", Some(&continuation), 1),
        ),
    ] {
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{id}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_GRAPH_COVERAGE_CONTINUATION",
            "{id}: {response:#}"
        );
    }
    let mut scope_mismatch = graph_coverage_page_request("scope-mismatch", Some(&continuation), 2);
    scope_mismatch["params"]["scope"]["module"] = serde_json::json!(".#:app");
    let (scope_status, scope_response) = rpc(&home, &config_home, &workspace, scope_mismatch);
    assert!(!scope_status.success(), "{scope_response:#}");
    assert_eq!(
        scope_response["code"], "INVALID_GRAPH_COVERAGE_CONTINUATION",
        "{scope_response:#}"
    );

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (generation_status, generation_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("generation-drift", Some(&continuation), 2),
    );
    assert!(!generation_status.success(), "{generation_response:#}");
    assert_eq!(
        generation_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{generation_response:#}"
    );
    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 41", [])
        .expect("restore graph generation");

    let original_hash: String = fixture
        .connection()
        .query_row(
            "SELECT content_hash FROM file_manifest WHERE filename = 'Source0002.kt'",
            [],
            |row| row.get(0),
        )
        .expect("original persisted source hash");
    fixture
        .connection()
        .execute(
            "UPDATE file_manifest SET content_hash = ? WHERE filename = 'Source0002.kt'",
            params!["d".repeat(64)],
        )
        .expect("advance persisted source hash");
    let (composition_status, composition_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("composition-drift", Some(&continuation), 2),
    );
    assert!(!composition_status.success(), "{composition_response:#}");
    assert_eq!(
        composition_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{composition_response:#}"
    );
    fixture
        .connection()
        .execute(
            "UPDATE file_manifest SET content_hash = ? WHERE filename = 'Source0002.kt'",
            params![original_hash],
        )
        .expect("restore persisted source hash");

    std::fs::create_dir_all(workspace.join("included")).expect("included build");
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path)
                 SELECT prefix_id, filename, 'included', project_path
                 FROM file_gradle_projects
                 WHERE build_root = '.' AND project_path = ':app';
             INSERT INTO file_gradle_source_sets(
                 prefix_id, filename, build_root, project_path, source_set_name
             )
                 SELECT prefix_id, filename, 'included', project_path, source_set_name
                 FROM file_gradle_source_sets
                 WHERE build_root = '.' AND project_path = ':app';",
        )
        .expect("add ambiguous resolved scope");
    let (ambiguous_status, ambiguous_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("ambiguous-scope-drift", Some(&continuation), 2),
    );
    assert!(!ambiguous_status.success(), "{ambiguous_response:#}");
    assert_eq!(
        ambiguous_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{ambiguous_response:#}"
    );
    fixture
        .connection()
        .execute_batch(
            "DELETE FROM file_gradle_source_sets
                 WHERE build_root = 'included' AND project_path = ':app';
             DELETE FROM file_gradle_projects
                 WHERE build_root = 'included' AND project_path = ':app';",
        )
        .expect("remove ambiguous resolved scope");

    fixture
        .connection()
        .execute_batch(
            "PRAGMA defer_foreign_keys = ON;
             BEGIN;
             UPDATE file_gradle_projects
                 SET build_root = 'included'
                 WHERE project_path = ':app';
             UPDATE file_gradle_source_sets
                 SET build_root = 'included'
                 WHERE project_path = ':app';
             COMMIT;",
        )
        .expect("move resolved scope");
    let (resolved_status, resolved_response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("resolved-scope-drift", Some(&continuation), 2),
    );
    assert!(!resolved_status.success(), "{resolved_response:#}");
    assert_eq!(
        resolved_response["code"], "STALE_GRAPH_COVERAGE_CONTINUATION",
        "{resolved_response:#}"
    );
    fixture
        .connection()
        .execute_batch(
            "PRAGMA defer_foreign_keys = ON;
             BEGIN;
             UPDATE file_gradle_projects
                 SET build_root = '.'
                 WHERE project_path = ':app';
             UPDATE file_gradle_source_sets
                 SET build_root = '.'
                 WHERE project_path = ':app';
             COMMIT;",
        )
        .expect("restore resolved scope");

    let (second_status, second) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("second", Some(&continuation), 2),
    );
    assert!(second_status.success(), "{second:#}");
    let second_paths = second["result"]["files"]
        .as_array()
        .expect("second coverage files")
        .iter()
        .map(|file| file["path"].as_str().expect("coverage path").to_owned())
        .collect::<Vec<_>>();
    assert_eq!(
        second_paths,
        ["src/main/kotlin/sample/Source0002.kt"],
        "{second:#}"
    );
    assert_eq!(
        first_paths
            .into_iter()
            .chain(second_paths)
            .collect::<Vec<_>>(),
        [
            "src/main/kotlin/sample/Source0000.kt",
            "src/main/kotlin/sample/Source0001.kt",
            "src/main/kotlin/sample/Source0002.kt"
        ]
    );
    assert_eq!(second["result"]["truncated"], false);
    assert_eq!(second["result"]["continuation"], serde_json::Value::Null);
    assert!(
        second["result"].get("nextAfterPath").is_none(),
        "{second:#}"
    );
}
