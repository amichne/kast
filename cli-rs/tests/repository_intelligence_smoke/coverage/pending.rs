#[test]
fn repository_persisted_pending_update_blocks_exact_negative_for_selected_scope() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture.seed_pending_update("Source0000.kt", false);

    for scope in [
        serde_json::json!({"language": "kotlin"}),
        serde_json::json!({"language": "kotlin", "module": "app"}),
    ] {
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": "pending-update",
                "method": "repository/query",
                "params": {
                    "question": "Does DefinitelyMissing exist?",
                    "intent": "resolve",
                    "scope": scope,
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        );

        assert!(status.success(), "{response:#}");
        assert_eq!(response["result"]["status"], "QUALIFIED_EMPTY", "{response:#}");
        assert_eq!(response["result"]["coverage"]["complete"], false, "{response:#}");
        assert_eq!(response["result"]["coverage"]["pending"], 0, "{response:#}");
        assert_eq!(
            response["result"]["coverage"]["pendingUpdateCount"],
            1,
            "{response:#}"
        );
    }
}

#[test]
fn repository_scoped_pending_updates_ignore_only_proven_nonmembers() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    let connection = fixture.connection();
    connection
        .execute(
            "DELETE FROM file_gradle_source_sets WHERE filename = 'Source0001.kt'",
            [],
        )
        .expect("remove original source-set evidence");
    connection
        .execute(
            "DELETE FROM file_gradle_projects WHERE filename = 'Source0001.kt'",
            [],
        )
        .expect("remove original project evidence");
    drop(connection);
    fixture.insert_project_evidence(1, "Source0001.kt", ".", ":other", "main");
    fixture.seed_pending_update("Source0001.kt", false);

    let query = |id: &str, scope: serde_json::Value| {
        rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": "Does DefinitelyMissing exist?",
                    "intent": "resolve",
                    "scope": scope,
                    "limits": {"depth": 1, "results": 10, "evidence": 2}
                }
            }),
        )
    };

    let (status, unscoped) = query("pending-unscoped", serde_json::json!({
        "language": "kotlin"
    }));
    assert!(status.success(), "{unscoped:#}");
    assert_eq!(unscoped["result"]["status"], "QUALIFIED_EMPTY", "{unscoped:#}");
    assert_eq!(unscoped["result"]["coverage"]["pendingUpdateCount"], 1);

    let app_scope = serde_json::json!({"language": "kotlin", "module": "app"});
    let (status, app) = query("pending-proven-nonmember", app_scope.clone());
    assert!(status.success(), "{app:#}");
    assert_eq!(app["result"]["status"], "EMPTY", "{app:#}");
    assert_eq!(app["result"]["coverage"]["complete"], true, "{app:#}");
    assert_eq!(app["result"]["coverage"]["pendingUpdateCount"], 0);

    fixture.seed_pending_update("New.kt", false);
    let (status, unknown) = query("pending-unknown", app_scope);
    assert!(status.success(), "{unknown:#}");
    assert_eq!(unknown["result"]["status"], "QUALIFIED_EMPTY", "{unknown:#}");
    assert_eq!(unknown["result"]["coverage"]["complete"], false, "{unknown:#}");
    assert_eq!(unknown["result"]["coverage"]["pendingUpdateCount"], 1);
}
