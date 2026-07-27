fn assert_context_output_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
) {
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
    let (_, context) = rpc(home, config_home, workspace, context_request.clone());
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
    let toon = rpc_output(home, config_home, workspace, "toon", &context_request);
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
    let markdown = rpc_output(home, config_home, workspace, "human", &context_request);
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
    for (id, question) in [
        (
            "path-only-kotlin-context",
            "Which exact Kotlin model carries semantic graph hashing evidence?",
        ),
        (
            "path-only-exact-context",
            "Which exact model carries semantic graph hashing evidence?",
        ),
    ] {
        let (_, inferred_target) = rpc(
            home,
            config_home,
            workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "repository/query",
                "params": {
                    "question": question,
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
    }
}
