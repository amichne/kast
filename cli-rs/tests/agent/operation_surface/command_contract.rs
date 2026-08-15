use serde_json::{Value, json};
use support::*;

#[test]
fn applied_mutation_requires_idempotency_key_before_runtime_discovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path();
    let content_file = temp.path().join("Added.kt");
    std::fs::write(&content_file, "class Added\n").expect("content");
    let content = content_file.to_str().expect("content").to_string();
    let cases = [
        vec![
            "rename".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--new-name".to_string(),
            "Renamed".to_string(),
        ],
        vec![
            "add-implementation".to_string(),
            "--inside-scope".to_string(),
            "sample.Example".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "add-statement".to_string(),
            "--inside-scope".to_string(),
            "sample.Example.run".to_string(),
            "--at".to_string(),
            "body-end".to_string(),
            "--content-file".to_string(),
            content.clone(),
        ],
        vec![
            "replace-declaration".to_string(),
            "--symbol".to_string(),
            "sample.Example".to_string(),
            "--content-file".to_string(),
            content,
        ],
    ];

    for args in cases {
        let output = kast(&home, &config_home)
            .args(["--output", "json", "agent"])
            .args(args)
            .args([
                "--workspace-root",
                workspace.to_str().expect("workspace root"),
            ])
            .arg("--apply")
            .output()
            .expect("applied mutation");

        assert!(!output.status.success(), "missing key must fail");
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("structured usage error");
        assert_eq!(stdout["error"]["code"], "AGENT_USAGE", "{stdout}");
        assert!(
            stdout["error"]["message"]
                .as_str()
                .is_some_and(|message| message.contains("--idempotency-key")),
            "{stdout}"
        );
    }
}

#[test]
fn asynchronous_operation_commands_are_absent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");

    let output = kast(&home, &config_home)
        .args(["agent", "operation", "status"])
        .output()
        .expect("removed operation command");
    assert!(
        !output.status.success(),
        "removed operation command must fail"
    );
    let diagnostic = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        diagnostic.contains("unrecognized subcommand 'operation'"),
        "{diagnostic}"
    );
}

#[test]
fn applied_add_file_submits_typed_mutation_request() {
    let fixture = MutationFixture::new();
    let backend = spawn_operation_backend(
        &fixture.home,
        &fixture.config_home,
        &fixture.workspace,
        &fixture.temp.path().join("indexer.sock"),
        false,
    );
    let target = fixture.target();
    let plan = fixture.plan();
    assert!(
        plan.status.success(),
        "plan should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    assert!(!target.exists(), "planning must not create the target");
    let plan = decode_public_result(&plan);
    let plan_id = plan["planId"].as_str().expect("plan id");
    assert_eq!(plan["stage"], "AWAITING_APPROVAL");
    assert_eq!(plan["planVersion"], 0);

    let output = fixture.apply(plan_id);

    assert!(
        output.status.success(),
        "apply should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let receipt = decode_public_result(&output);
    assert_eq!(receipt["outcome"], "VERIFIED");
    assert_eq!(receipt["planId"], plan_id);
    assert_eq!(receipt["planVersion"], 5);
    assert_eq!(
        std::fs::read_to_string(&target).expect("server-authored target"),
        fixture.content,
    );

    let requests = backend.join().expect("backend");
    let semantic_methods = requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_methods,
        ["change/plan-add-file", "change/apply-add-file"],
    );
    let plan_request = requests
        .iter()
        .find(|request| request["method"] == "change/plan-add-file")
        .expect("typed plan request");
    assert_eq!(
        plan_request["params"]["workspaceRoot"],
        fixture.workspace.display().to_string(),
    );
    assert_eq!(
        plan_request["params"]["targetPath"],
        target.display().to_string(),
    );
    assert_eq!(plan_request["params"]["proposedContent"], fixture.content);
    let apply_request = requests
        .iter()
        .find(|request| request["method"] == "change/apply-add-file")
        .expect("typed apply request");
    assert_eq!(
        apply_request["params"]["workspaceRoot"],
        fixture.workspace.display().to_string(),
    );
    assert_eq!(apply_request["params"]["planId"], plan_id);
    assert_eq!(apply_request["params"]["expectedVersion"], 0);
    assert_eq!(apply_request["params"]["mode"], "APPLY");
    assert_eq!(
        apply_request["params"]["approvalEvidence"]["approvedBy"],
        "kast-public-cli",
    );
    let approval_sha256 = source_sha256(
        format!(
            "kast-public-cli\nworkspaceRoot={}\nplanId={plan_id}\nexpectedVersion=0\n",
            fixture.workspace.display(),
        )
        .as_bytes(),
    );
    assert_eq!(
        apply_request["params"]["approvalEvidence"]["evidenceSha256"],
        approval_sha256,
    );
}
