#[cfg(unix)]
#[test]
fn repository_query_enforces_routed_root_authority() {
    use std::os::unix::fs::symlink;

    let (temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    let outside_document = temp.path().join("outside.md");
    std::fs::write(
        &outside_document,
        "# Outside\n\nSemanticGraphSha256 must never be read through the workspace.\n",
    )
    .expect("outside context document");
    let linked_document = workspace.join("docs/outside.md");
    symlink(&outside_document, &linked_document).expect("outside context symlink");

    let request = || {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "root-authority",
            "method": "repository/query",
            "params": {
                "question": "Which document explains SemanticGraphSha256?",
                "intent": "context_relationship",
                "scope": {"language": "kotlin", "sources": ["markdown"]},
                "limits": {"depth": 1, "results": 10, "evidence": 1}
            }
        })
    };

    let mut conflicting_request = request();
    conflicting_request["params"]["workspaceRoot"] = serde_json::json!(temp.path());
    let (conflict_status, conflict) = rpc(&home, &config_home, &workspace, conflicting_request);
    let mut missing_request = request();
    missing_request["params"]["workspaceRoot"] =
        serde_json::json!(temp.path().join("missing-workspace"));
    let (missing_status, missing) = rpc(&home, &config_home, &workspace, missing_request);
    let (escape_status, escape) = rpc(&home, &config_home, &workspace, request());
    let toon_escape_output = rpc_output(&home, &config_home, &workspace, "toon", &request());
    let toon_escape_raw =
        String::from_utf8(toon_escape_output.stdout).expect("context error TOON UTF-8");
    let toon_escape: serde_json::Value =
        toon_format::decode_default(toon_escape_raw.trim()).expect("context error TOON");

    std::fs::remove_file(&linked_document).expect("remove outside context symlink");
    let outside_directory = temp.path().join("outside-docs");
    std::fs::create_dir(&outside_directory).expect("outside context directory");
    std::fs::write(
        outside_directory.join("outside.md"),
        "# Outside directory\n\nSemanticGraphSha256 remains outside the workspace.\n",
    )
    .expect("outside directory context document");
    let linked_directory = workspace.join("docs/outside-directory");
    symlink(&outside_directory, &linked_directory).expect("outside context directory symlink");
    let (directory_escape_status, directory_escape) =
        rpc(&home, &config_home, &workspace, request());

    std::fs::remove_file(&linked_directory).expect("remove outside context directory symlink");
    std::fs::write(
        workspace.join("docs/inside.md"),
        "# Inside\n\nSemanticGraphSha256 is compiler-backed repository evidence.\n",
    )
    .expect("inside context document");
    let mut valid_request = request();
    valid_request["params"]["workspaceRoot"] = serde_json::json!(workspace);
    let (valid_status, valid) = rpc(&home, &config_home, &workspace, valid_request);

    assert_eq!(
        serde_json::json!({
            "rootConflict": {
                "success": conflict_status.success(),
                "ok": conflict["ok"],
                "code": conflict["code"]
            },
            "missingBodyRoot": {
                "success": missing_status.success(),
                "ok": missing["ok"],
                "code": missing["code"]
            },
            "symlinkEscape": {
                "success": escape_status.success(),
                "ok": escape["ok"],
                "code": escape["code"]
            },
            "toonSymlinkEscape": {
                "success": toon_escape_output.status.success(),
                "ok": toon_escape["ok"],
                "code": toon_escape["code"],
                "actionable": toon_escape["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("remove the symlink")),
                "schemaVersionMatchesJson": toon_escape["schemaVersion"] == escape["schemaVersion"]
            },
            "directorySymlinkEscape": {
                "success": directory_escape_status.success(),
                "ok": directory_escape["ok"],
                "code": directory_escape["code"]
            },
            "validExactRoot": {
                "success": valid_status.success(),
                "status": valid["result"]["status"],
                "canonicalRoot": valid["result"]["workspaceIdentity"]["canonicalRoot"]
            }
        }),
        serde_json::json!({
            "rootConflict": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_WORKSPACE_ROOT_MISMATCH"
            },
            "missingBodyRoot": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_WORKSPACE_ROOT_MISMATCH"
            },
            "symlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE"
            },
            "toonSymlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE",
                "actionable": true,
                "schemaVersionMatchesJson": true
            },
            "directorySymlinkEscape": {
                "success": false,
                "ok": false,
                "code": "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE"
            },
            "validExactRoot": {
                "success": true,
                "status": "ANSWERED",
                "canonicalRoot": workspace
            }
        })
    );
}

#[test]
fn graph_coverage_rejects_conflicting_body_workspace_root() {
    let (temp, home, config_home, indexed_workspace, _fixture) = coverage_fixture();
    let routed_workspace = temp.path().join("routed-workspace");
    std::fs::create_dir(&routed_workspace).expect("routed workspace");
    std::fs::write(routed_workspace.join("settings.gradle.kts"), "")
        .expect("routed Gradle settings");
    let request = |workspace_root: &std::path::Path| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "coverage-root-authority",
            "method": "graph/coverage",
            "params": {
                "workspaceRoot": workspace_root,
                "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"}
            }
        })
    };

    let (conflict_status, conflict) = rpc(
        &home,
        &config_home,
        &routed_workspace,
        request(&indexed_workspace),
    );
    let (exact_status, exact) = rpc(
        &home,
        &config_home,
        &indexed_workspace,
        request(&indexed_workspace),
    );

    assert_eq!(
        serde_json::json!({
            "conflict": {
                "commandSucceeded": conflict_status.success(),
                "ok": conflict["ok"],
                "code": conflict["code"]
            },
            "exact": {
                "commandSucceeded": exact_status.success(),
                "generation": exact["result"]["generation"]
            }
        }),
        serde_json::json!({
            "conflict": {
                "commandSucceeded": false,
                "ok": false,
                "code": "REPOSITORY_WORKSPACE_ROOT_MISMATCH"
            },
            "exact": {
                "commandSucceeded": true,
                "generation": 41
            }
        }),
        "conflict={conflict:#} exact={exact:#}"
    );
}
