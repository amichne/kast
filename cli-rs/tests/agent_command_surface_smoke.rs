mod support;

use serde_json::{Value, json};
use support::metrics::seed_source_index;
use support::*;

fn symbol_result(workspace: &Path, fq_name: &str) -> Value {
    json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": fq_name,
            "kind": "FUNCTION",
            "location": {
                "filePath": workspace.join("Keywords.kt").display().to_string(),
                "startOffset": 10,
                "endOffset": 16,
                "startLine": 1,
                "startColumn": 1,
                "preview": "fun when()"
            }
        }
    })
}

fn run_agent_symbol(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    extra_args: &[&str],
) -> std::process::Output {
    let mut command = kast(home, config_home);
    command.args([
        "--output",
        "json",
        "agent",
        "symbol",
        "--query",
        "`when`",
        "--workspace-root",
        workspace.to_str().expect("workspace"),
    ]);
    command.args(extra_args).output().expect("agent symbol")
}

#[test]
fn agent_symbol_defaults_to_exact_and_returns_compiler_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let handle = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![("symbol/resolve", symbol_result(&workspace, "sample.when"))],
    );

    let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_SYMBOL_LOOKUP");
    assert_eq!(stdout["result"]["mode"], "exact");
    assert_eq!(stdout["result"]["outcome"]["type"], "RESOLVED");
    assert_eq!(stdout["result"]["outcome"]["source"], "compiler");
    assert_eq!(
        stdout["result"]["outcome"]["symbol"]["fqName"],
        "sample.when"
    );
    let requests = handle.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
    assert_eq!(requests[2]["params"]["symbol"], "`when`");
}

#[test]
fn agent_symbol_not_found_and_ambiguous_do_not_discover() {
    for result in [
        json!({"type":"RESOLVE_NOT_FOUND","ok":true,"source":"compiler"}),
        json!({
            "type":"RESOLVE_AMBIGUOUS",
            "ok":true,
            "source":"compiler",
            "candidates":[{"fqName":"alpha.Parser.parse"},{"fqName":"beta.Parser.parse"}]
        }),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("idea.sock");
        let handle = spawn_scripted_idea_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            vec![("symbol/resolve", result)],
        );

        let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("symbol json");
        assert!(matches!(
            stdout["result"]["outcome"]["type"].as_str(),
            Some("NOT_FOUND" | "AMBIGUOUS")
        ));
        let requests = handle.join().expect("scripted backend");
        assert_eq!(
            requests.len(),
            3,
            "expected only runtime probes plus resolve"
        );
        assert_eq!(requests[2]["method"], "symbol/resolve");
    }
}

#[test]
fn agent_symbol_discovery_requests_lexical_mode_explicitly() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Foo",
            "--mode",
            "discovery",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("discovery");

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("discovery json");
    assert_eq!(stdout["result"]["mode"], "discovery");
    assert_eq!(stdout["result"]["outcome"]["type"], "DISCOVERED");
    assert_eq!(stdout["result"]["outcome"]["source"], "fuzzy");
    assert_eq!(
        stdout["result"]["request"]["params"]["modes"],
        json!(["lexical"])
    );
}

#[test]
fn agent_symbol_relations_use_canonical_compiler_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let handle = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("symbol/resolve", symbol_result(&workspace, "sample.when")),
            (
                "symbol/references",
                json!({"type":"REFERENCES_SUCCESS","ok":true,"references":[]}),
            ),
            (
                "symbol/callers",
                json!({"type":"CALLERS_SUCCESS","ok":true,"calls":[]}),
            ),
        ],
    );

    let output = run_agent_symbol(
        &home,
        &config_home,
        &workspace,
        &["--references", "--callers", "incoming"],
    );

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let requests = handle.join().expect("scripted backend");
    assert_eq!(requests[3]["params"]["symbol"], "sample.when");
    assert_eq!(requests[4]["params"]["symbol"], "sample.when");
}

#[test]
fn agent_symbol_discovery_rejects_relation_flags_before_io() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let output = run_agent_symbol(
        &home,
        &config_home,
        &workspace,
        &["--mode", "discovery", "--references"],
    );

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("usage json");
    assert_eq!(stdout["error"]["code"], "AGENT_USAGE");
}

#[test]
fn agent_symbol_uses_indexed_exact_only_when_compiler_is_unavailable() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Parser",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("indexed exact fallback");

    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
    assert_eq!(stdout["result"]["outcome"]["type"], "AMBIGUOUS");
    assert_eq!(stdout["result"]["outcome"]["source"], "indexed-exact");
    assert_eq!(
        stdout["result"]["request"]["params"]["modes"],
        json!(["exact"])
    );
    assert_eq!(
        stdout["result"]["outcome"]["candidates"]
            .as_array()
            .expect("candidates")
            .len(),
        2
    );
    assert!(
        stdout["result"]["outcome"]["compilerFallback"]["code"]
            .as_str()
            .is_some_and(|code| !code.is_empty()),
        "{stdout}"
    );
}

#[test]
fn agent_symbol_indexed_exact_cardinality_ignores_presentation_limit() {
    for limit in ["0", "1"] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&home).expect("home");
        seed_source_index(&workspace);
        support::metrics::seed_exact_lookup_symbols(&workspace);

        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "symbol",
                "--query",
                "Parser",
                "--limit",
                limit,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("indexed exact fallback");

        assert!(
            output.status.success(),
            "limit={limit} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
        assert_eq!(stdout["result"]["outcome"]["type"], "AMBIGUOUS");
        assert_eq!(
            stdout["result"]["outcome"]["candidates"]
                .as_array()
                .expect("candidates")
                .len(),
            2
        );
    }
}

#[test]
fn agent_symbol_indexed_file_hint_is_literal_and_suffix_equivalent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    for (file_hint, expected_outcome) in [
        ("lib/AlphaParser.kt", "RESOLVED"),
        ("lib/*Parser.kt", "NOT_FOUND"),
    ] {
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "symbol",
                "--query",
                "Parser",
                "--file-hint",
                file_hint,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("indexed exact file hint");

        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("fallback json");
        assert_eq!(stdout["result"]["outcome"]["type"], expected_outcome);
    }
}

#[test]
fn agent_symbol_relations_preserve_compiler_unavailability() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "`when`",
            "--references",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compiler unavailable relations");

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("failure json");
    assert!(stdout["error"]["code"].as_str().is_some());
    assert!(stdout["result"].is_null(), "{stdout}");
}

#[test]
fn agent_symbol_containing_type_never_weakens_to_indexed_exact() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    seed_source_index(&workspace);
    support::metrics::seed_exact_lookup_symbols(&workspace);

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "Parser",
            "--containing-type",
            "sample.Container",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("containing type fail closed");

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("failure json");
    assert!(stdout["error"]["code"].as_str().is_some());
    assert!(stdout["result"].is_null(), "{stdout}");
}

#[test]
fn agent_symbol_operational_resolve_failure_never_falls_back() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let handle = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "symbol/resolve",
            json!({"type":"RESOLVE_FAILURE","ok":false,"message":"compiler failed"}),
        )],
    );

    let output = run_agent_symbol(&home, &config_home, &workspace, &[]);

    assert!(!output.status.success());
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("failure json");
    assert_eq!(stdout["error"]["code"], "RESOLVE_FAILURE");
    assert_eq!(handle.join().expect("scripted backend").len(), 3);
}

fn assert_removed_agent_workflow(stdout: &serde_json::Value) {
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["method"], "agent/workflow", "{stdout}");
    assert_eq!(stdout["error"]["code"], "AGENT_COMMAND_REMOVED", "{stdout}");
    let replacements = stdout["error"]["details"]["replacements"]
        .as_array()
        .expect("workflow replacements");
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast agent verify --workspace-root <repo>"),
        "{stdout}"
    );
    assert!(
        replacements
            .iter()
            .any(|replacement| replacement == "kast repair --apply"),
        "{stdout}"
    );
}

#[test]
fn removed_agent_workflow_package_verify_fails_closed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let workflow = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "package-verify",
            "--dry-run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent workflow package-verify");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn removed_agent_workflow_write_validate_fails_before_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let workflow = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workflow",
            "write-validate",
            "--mode",
            "create",
            "--file-path",
            temp.path()
                .join("Example.kt")
                .to_str()
                .expect("example path"),
            "--content",
            "class Example",
        ])
        .output()
        .expect("agent workflow write-validate");

    assert!(
        !workflow.status.success(),
        "removed workflow should fail before mutation: stdout={}, stderr={}",
        String::from_utf8_lossy(&workflow.stdout),
        String::from_utf8_lossy(&workflow.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&workflow.stdout).expect("workflow removal json");
    assert_removed_agent_workflow(&stdout);
}

#[test]
fn agent_rename_without_apply_returns_identity_first_plan() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let plan = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--symbol",
            "io.example.OrderService.process",
            "--new-name",
            "processSafely",
            "--kind",
            "function",
        ])
        .output()
        .expect("agent rename plan");

    assert!(
        plan.status.success(),
        "rename plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan json");
    let request = &stdout["result"]["request"];
    assert_eq!(stdout["method"], "agent/rename", "{stdout}");
    assert_eq!(
        stdout["result"]["type"], "KAST_AGENT_RENAME_PLAN",
        "{stdout}"
    );
    assert_eq!(stdout["result"]["applyRequired"], true, "{stdout}");
    assert_eq!(request["method"], "symbol/rename", "{stdout}");
    assert_eq!(
        request["params"]["type"], "RENAME_BY_SYMBOL_REQUEST",
        "{stdout}"
    );
    assert_eq!(
        request["params"]["symbol"], "io.example.OrderService.process",
        "{stdout}"
    );
    assert_eq!(request["params"]["kind"], "function", "{stdout}");
    assert!(
        !request.to_string().contains("offset"),
        "public rename plan must not expose offsets: {stdout}"
    );
}

#[test]
fn agent_scope_mutations_without_apply_return_typed_request_plans() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "fun added() = Unit\n").expect("snippet");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let target_file = workspace.join("Added.kt");

    let cases = [
        (
            "add-file",
            vec![
                "agent",
                "add-file",
                "--file-path",
                target_file.to_str().expect("target"),
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-file",
            "symbol/add-file",
        ),
        (
            "add-declaration",
            vec![
                "agent",
                "add-declaration",
                "--inside-file",
                target_file.to_str().expect("target"),
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/add-declaration",
            "symbol/add-declaration",
        ),
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
        (
            "replace-declaration",
            vec![
                "agent",
                "replace-declaration",
                "--symbol",
                "sample.greet",
                "--kind",
                "function",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            "agent/replace-declaration",
            "symbol/replace-declaration",
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
            stdout["result"]["type"], "KAST_AGENT_MUTATION_PLAN",
            "{stdout}"
        );
        assert_eq!(stdout["result"]["applyRequired"], true, "{stdout}");
        assert_eq!(
            stdout["result"]["request"]["method"], request_method,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["request"]["params"].get("type"),
            None,
            "{stdout}"
        );
        assert_eq!(
            stdout["result"]["request"]["params"]["contentFile"],
            content_file.to_str().expect("snippet"),
            "{stdout}"
        );
    }
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
            "add-file",
            vec![
                "agent",
                "add-file",
                "--file-path",
                "src/generated/New File.kt",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["filePath"][..],
        ),
        (
            "add-declaration",
            vec![
                "agent",
                "add-declaration",
                "--inside-file",
                "src/generated/New File.kt",
                "--at",
                "file-bottom",
                "--content-file",
                content_file.to_str().expect("snippet"),
            ],
            &["placement", "scope", "insideFile"][..],
        ),
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
        let params = &document["result"]["request"]["params"];
        let target = target_path
            .iter()
            .fold(params, |value, segment| &value[*segment]);

        assert!(
            plan.status.success(),
            "{name}: stdout={}, stderr={}",
            String::from_utf8_lossy(&plan.stdout),
            String::from_utf8_lossy(&plan.stderr),
        );
        assert_eq!(target, &expected_target, "{name}: {document:#}");
        assert_eq!(
            params["contentFile"],
            content_file.to_str().expect("snippet"),
            "{name}: {document:#}",
        );
    }
}

#[test]
fn relative_file_target_requires_explicit_workspace_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let content_file = temp.path().join("snippet.kt");
    std::fs::write(&content_file, "class Added\n").expect("snippet");

    let plan = kast(&home, &config_home)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--file-path",
            "Added.kt",
            "--content-file",
            content_file.to_str().expect("snippet"),
        ])
        .output()
        .expect("relative add-file plan");
    let document: serde_json::Value = serde_json::from_slice(&plan.stdout).expect("plan JSON");

    assert!(!plan.status.success(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE",
        "{document:#}",
    );
}

#[test]
fn ready_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let install_dir = install_root.join("current/lib/backends/headless/headless-0.0.1");
    let runtime_libs = install_dir.join("runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["backend:headless"],
            "managedPaths": ["current/lib/backends/headless"],
            "backends": [{
                "name": "headless",
                "version": "0.0.1",
                "installDir": install_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let ready = kast(&home, &config_home)
        .args(["--output", "json", "ready"])
        .output()
        .expect("ready");
    let stdout = String::from_utf8_lossy(&ready.stdout);

    assert!(
        !ready.status.success(),
        "ready should fail for stale backend"
    );
    assert!(stdout.contains("\"ok\": false"), "{stdout}");
    assert!(stdout.contains("\"minimumBackendVersion\""), "{stdout}");
    assert!(stdout.contains("0.0.1"), "{stdout}");
    assert!(stdout.contains("older than required"), "{stdout}");
}
