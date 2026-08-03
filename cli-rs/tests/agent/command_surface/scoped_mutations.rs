#[test]
fn agent_scope_mutations_without_apply_return_typed_request_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let cases = [
        (
            "add-implementation",
            vec![
                "agent",
                "add-implementation",
                "--inside-scope",
                "sample.Greeter",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-implementation",
            "symbol/add-implementation",
        ),
        (
            "add-statement",
            vec![
                "agent",
                "add-statement",
                "--inside-scope",
                "sample.greet",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-statement",
            "symbol/add-statement",
        ),
    ];

    for (name, args, agent_method, request_method) in cases {
        let plan = kast(&home, &config_home)
            .arg("--output")
            .arg("json")
            .args(args)
            .args(["--workspace-root", workspace.to_str().expect("workspace")])
            .output()
            .unwrap_or_else(|error| panic!("{name} plan failed to launch: {error}"));

        assert!(
            plan.status.success(),
            "{name} plan should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr)
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&plan.stdout).unwrap_or_else(|error| {
                panic!(
                    "{name} plan should emit json: {error}; stdout={}",
                    String::from_utf8_lossy(&plan.stdout)
                )
            });
        assert_eq!(stdout["method"], agent_method, "{stdout}");
        assert_eq!(
            stdout["result"]["type"], "KAST_AGENT_MUTATION_RESULT",
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["execution"]["outcome"],
            format!(
                "PLANNED_{}",
                request_method
                    .strip_prefix("symbol/")
                    .unwrap()
                    .replace('-', "_")
                    .to_ascii_uppercase()
            ),
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["plan"]["method"], request_method,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["plan"]["contentFile"],
            content_file.to_str().expect("snippet"),
            "{stdout}"
        );
    }
}

#[test]
fn selector_handle_replace_declaration_preserves_plan_and_distinct_apply_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("replace-handle.sock");
    let content_file = temp.path().join("replacement.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"replace-handle\"\n",
    )
    .expect("Gradle workspace marker");
    let source = "class OrderService { fun process(): String = \"old\" }\n";
    let proposed = "fun process(): String = \"😀\" + helper()\n";
    std::fs::write(workspace.join("Keywords.kt"), source).expect("source fixture");
    std::fs::write(workspace.join("Helpers.kt"), "fun helper() = \"ok\"\n")
        .expect("helper fixture");
    std::fs::write(&content_file, proposed).expect("replacement");
    let selector_handle = "ksh1.replace-handle";
    let canonical_workspace = workspace.canonicalize().expect("canonical workspace");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "selector/identity",
                json!({
                    "type": "AVAILABLE",
                    "identity": {
                        "fqName": "io.example.OrderService.process",
                        "kind": "FUNCTION",
                        "declarationFile": workspace.join("Keywords.kt").display().to_string(),
                        "declarationStartOffset": 10,
                        "containingType": "io.example.OrderService",
                    },
                    "schemaVersion": api_schema_version(),
                }),
            ),
            (
                "raw/plan-replacement",
                exact_replacement_preview(&canonical_workspace, source, proposed),
            ),
        ],
    );

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "replace-declaration",
            "--selector-handle",
            selector_handle,
            "--content-file",
            content_file.to_str().expect("content"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("replace declaration plan");
    assert!(
        plan.status.success(),
        "replace plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    let plan: Value = serde_json::from_slice(&plan.stdout).expect("replace plan json");
    assert_eq!(plan["result"]["type"], "KAST_AGENT_MUTATION_RESULT");
    assert_eq!(
        plan["result"]["execution"]["outcome"],
        "PLANNED_REPLACE_DECLARATION",
    );
    assert_eq!(
        plan["result"]["plan"]["type"],
        "REPLACE_DECLARATION_BY_SELECTOR_HANDLE_REQUEST",
    );
    assert_eq!(plan["result"]["plan"]["selectorHandle"], selector_handle,);
    assert_eq!(
        plan["result"]["plan"]["preview"]["proof"]["target"]["fqName"],
        "io.example.OrderService.process",
    );
    assert!(
        plan["result"]["plan"].get("symbol").is_none(),
        "handle plan must not reconstruct a symbol selector: {plan}",
    );
    let requests = backend.join().expect("replace handle backend");
    assert_eq!(requests[2]["method"], "selector/identity");
    assert_eq!(requests[2]["params"]["family"], "REPLACE_DECLARATION",);
    assert_eq!(requests[3]["method"], "raw/plan-replacement");

    let missing_key = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "replace-declaration",
            "--selector-handle",
            selector_handle,
            "--content-file",
            content_file.to_str().expect("content"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--apply",
        ])
        .output()
        .expect("replace without idempotency key");
    assert!(
        !missing_key.status.success(),
        "apply must require authority"
    );
    let missing_key: Value =
        serde_json::from_slice(&missing_key.stdout).expect("missing key error json");
    assert_eq!(missing_key["error"]["code"], "AGENT_USAGE");

    let apply = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "replace-declaration",
            "--selector-handle",
            selector_handle,
            "--content-file",
            content_file.to_str().expect("content"),
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--apply",
            "--idempotency-key",
            "issue-392-replace",
        ])
        .output()
        .expect("replace declaration without workspace lease");
    assert!(
        !apply.status.success(),
        "replace submission must require a workspace lease: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let apply: Value = serde_json::from_slice(&apply.stdout).expect("lease error json");
    assert_eq!(apply["error"]["code"], "WORKSPACE_LEASE_REQUIRED");
}

#[test]
fn relative_file_targets_are_canonical_in_mutation_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let target_parent = workspace.join("src/generated");
    std::fs::create_dir_all(&target_parent).expect("target parent");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let expected_target = target_parent
        .canonicalize()
        .expect("canonical target parent")
        .join("New File.kt")
        .display()
        .to_string();

    let cases = [
        (
            "add-implementation",
            vec![
                "agent",
                "add-implementation",
                "--inside-file",
                "src/generated/New File.kt",
                "--at",
                "body-end",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["placement", "scope", "insideFile"][..],
        ),
    ];

    for (name, args, target_path) in cases {
        let plan = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(args)
            .args(["--workspace-root", workspace.to_str().expect("workspace")])
            .output()
            .unwrap_or_else(|error| panic!("{name} plan: {error}"));
        let document: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");
        let plan_result = &document["result"]["plan"];
        let target = target_path
            .iter()
            .fold(plan_result, |value, segment| &value[*segment]);

        assert!(
            plan.status.success(),
            "{name}: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr),
        );
        assert_eq!(target, &expected_target, "{name}: {document:#}");
        assert_eq!(
            plan_result["contentFile"],
            content_file.to_str().expect("snippet"),
            "{name}: {document:#}",
        );
    }
}
