#[test]
fn documented_workspace_file_arguments_reach_the_typed_boundary() {
    let workspace = std::fs::canonicalize(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .expect("workspace parent"),
    )
    .expect("canonical workspace");
    let workspace = workspace.to_str().expect("UTF-8 workspace");
    let stdout = assert_typed_boundary(&[
        "--workspace-root",
        workspace,
        "--module",
        "gradle:included/tools#:app",
        "--source-set",
        "integrationTest",
        "--kind",
        "source",
        "--package",
        "named:例子.`when`",
        "--dirty",
        "dirty",
        "--drift",
        "not-applicable",
        "--path-prefix",
        "src/main",
        "--glob",
        "**/*.kt",
        "--limit",
        "200",
        "--page-token",
        "123e4567-e89b-42d3-a456-426614174000",
        "--fields",
        "path,evidence",
    ]);
    let query = &stdout["error"]["details"]["admittedQuery"];
    assert_eq!(query["canonicalWorkspaceRoot"], workspace, "{stdout:#}");
    assert!(query.get("backend").is_none(), "{stdout:#}");
    assert_eq!(
        query["filters"]["package"], "named:例子.`when`",
        "{stdout:#}"
    );
    assert_eq!(query["filters"]["packageName"], "例子.when", "{stdout:#}");
    assert_eq!(query["filters"]["kind"], "source", "{stdout:#}");
    assert_eq!(query["kindDomain"], "source-only", "{stdout:#}");
    assert_eq!(query["view"], "fields", "{stdout:#}");
    assert_eq!(
        query["orderedFields"],
        serde_json::json!(["path", "evidence"])
    );
    assert_eq!(query["limit"], 200, "{stdout:#}");
    assert!(query.get("pageHandle").is_none(), "{stdout:#}");
    assert_eq!(
        stdout["error"]["details"]["pageHandle"]["token"], "123e4567-e89b-42d3-a456-426614174000",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["nextAction"]["arguments"],
        serde_json::json!([
            "agent",
            "verify",
            "--workspace-root",
            workspace,
        ]),
        "{stdout:#}"
    );
}
