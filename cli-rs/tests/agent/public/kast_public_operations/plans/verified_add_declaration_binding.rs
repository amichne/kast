#[test]
fn change_add_declaration_apply_uses_verified_operation_binding_without_raw_bypass() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    std::fs::write(&target, "class Existing\n").expect("existing source");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let declaration = "class Added";
    let plan_id = "4".repeat(64);
    let postimage_sha256 = source_sha256(b"class Existing\n\nclass Added\n");
    let plan_result = json!({
        "planId": plan_id,
        "planVersion": 0,
        "stage": "AWAITING_APPROVAL",
        "operation": "add-declaration",
        "preview": {
            "targetPath": target,
            "proposedDeclaration": declaration,
            "generation": 7,
        },
        "schemaVersion": api_schema_version(),
    });
    let verified_receipt = json!({
        "outcome": "VERIFIED",
        "planId": plan_id,
        "planVersion": 5,
        "operation": "add-declaration",
        "publication": {
            "generation": 8,
            "workspaceStateIdentity": "verified-add-declaration-g1",
        },
        "identity": {
            "targetPath": target,
            "sourceRange": {"startOffset": 16, "endOffset": 27},
            "packageName": "",
            "declarationName": "Added",
            "declarationKind": "CLASS",
        },
        "postimageSha256": postimage_sha256,
        "schemaVersion": api_schema_version(),
    });
    let backend = support::spawn_verified_add_declaration_binding_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("verified-add-declaration.sock"),
        plan_result,
        verified_receipt.clone(),
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut plan = installed_public_kast(&binary, &home, &config_home, &workspace);
    plan.args([
        "change",
        "plan",
        "add-declaration",
        "--file",
        target.to_str().expect("target"),
    ]);
    let plan = run_with_stdin(plan, declaration);
    assert!(
        plan.status.success(),
        "operation-specific plan failed: stdout={} stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    let public_plan = decode(&plan);
    assert_eq!(public_plan["planId"], plan_id);
    assert_eq!(public_plan["planVersion"], 0);
    assert!(
        public_plan["planId"]
            .as_str()
            .is_some_and(|value| value.len() == 64
                && value.bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())),
        "Kotlin must issue the canonical lowercase SHA-256 plan identity: {public_plan:#}",
    );

    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified add-declaration apply");
    assert!(applied.status.success(), "{applied:?}");
    assert_eq!(
        decode(&applied),
        verified_receipt,
        "the public receipt must be the server-issued durable v5 receipt",
    );
    let requests = backend.join().expect("strict add-declaration backend");
    let methods = requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
        .collect::<Vec<_>>();
    assert_eq!(
        methods,
        [
            "change/plan-add-declaration",
            "change/apply-add-declaration",
        ],
        "generic plan/apply/CAS/refresh/diagnostics/postcondition bypasses are forbidden",
    );
}
